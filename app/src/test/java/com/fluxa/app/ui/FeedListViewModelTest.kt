package com.fluxa.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.fluxa.app.data.repository.ArticleRepository
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.feedlist.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FeedListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private fun model(repository: ArticleRepository, state: SavedStateHandle = SavedStateHandle()) =
        FeedListViewModel(repository, state, triageRuntime = TriageRuntime(dispatcher, java.time.Clock.systemUTC())).also { store.put("test", it) }
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun editorEchoIsSynchronousEvenBeforeProjectionRunsAndScopeRestoreCannotEchoOldText() = runTest(dispatcher) {
        val saved = SavedStateHandle(); val vm = model(CachedRepository(), saved)
        runCurrent()
        for (length in 1.."Correction".length) {
            val value = "Correction".take(length)
            vm.setQuery(value)
            assertEquals(value, vm.editorQuery.value) // no scheduler/debounce advancement
            assertEquals(value, saved.get<String>("query"))
        }
        runCurrent(); assertEquals("Correction", vm.editorQuery.value)
        vm.applyScope(SearchScope(query = "KOTLIN", source = "feed/one"))
        assertEquals("KOTLIN", vm.editorQuery.value)
        vm.setQuery(""); assertEquals("", vm.editorQuery.value)
        advanceTimeBy(300); runCurrent()
        assertEquals("", vm.state.value.query); assertEquals("feed/one", vm.state.value.source)
    }

    @Test fun refreshFailureKeepsCachedArticlesVisible() = runTest(dispatcher) {
        val repository = CachedRepository()
        val vm = model(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        assertEquals(2, vm.state.value.articles.size)
        assertNotNull(vm.state.value.message)
        assertFalse(vm.state.value.busy)
    }

    @Test fun queryUnreadStarredAndSourceFiltersCombine() = runTest(dispatcher) {
        val vm = model(CachedRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        vm.setFilter(ArticleFilter.Unread)
        vm.setQuery("KOTLIN")
        advanceTimeBy(251)
        runCurrent()
        assertEquals("one", vm.state.value.articles.single().id)
        vm.setFilter(ArticleFilter.Starred)
        runCurrent()
        assertTrue(vm.state.value.articles.isEmpty())
        vm.setQuery("")
        vm.selectSource("feed/two")
        runCurrent()
        assertEquals("two", vm.state.value.articles.single().id)
    }

    @Test fun unreadProjectionStaysCurrentWithoutUiSubscriberThenReconnects() = runTest(dispatcher) {
        val repository = CachedRepository()
        val savedState = SavedStateHandle(mapOf(
            "query" to "KOTLIN", "filter" to "Unread", "source" to "feed/one"
        ))
        val survivor = repository.articles.value.first().copy(id = "survivor")
        repository.articles.value = repository.articles.value + survivor
        val vm = model(repository, savedState)
        val ui = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        advanceTimeBy(251)
        runCurrent()
        assertEquals(listOf("one", "survivor"), vm.state.value.articles.map { it.id })

        ui.cancel()
        runCurrent()
        advanceTimeBy(6_000) // Exceed the former WhileSubscribed stop timeout during reading.
        runCurrent()
        repository.articles.value = repository.articles.value.map {
            if (it.id == "one") it.copy(isRead = true) else it
        }
        runCurrent()
        // Assert before reconnect: Compose must never receive the consumed anchor as its first frame.
        assertEquals(listOf("survivor"), vm.state.value.articles.map { it.id })
        assertEquals("KOTLIN", vm.state.value.query)
        assertEquals(ArticleFilter.Unread, vm.state.value.filter)
        assertEquals("feed/one", vm.state.value.source)
        assertEquals("KOTLIN", savedState.get<String>("query"))
        assertEquals("Unread", savedState.get<String>("filter"))
        assertEquals("feed/one", savedState.get<String>("source"))

        val reconnected = mutableListOf<FeedListState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { reconnected += it } }
        runCurrent()
        assertEquals(listOf("survivor"), reconnected.first().articles.map { it.id })
        assertEquals(vm.state.value, reconnected.last())
    }

    @Test fun concurrentRefreshClicksProduceOnlyOneRequest() = runTest(dispatcher) {
        val repository = CachedRepository()
        val vm = model(repository)
        repeat(5) { vm.refresh() }
        runCurrent()
        assertEquals(1, repository.refreshCalls)
    }

    @Test fun newerQueryCancelsDebouncedResultWithoutNetworkOrSourceBroadening() = runTest(dispatcher) {
        val repository = CachedRepository()
        val vm = model(repository)
        runCurrent()
        val calls = repository.refreshCalls
        vm.setQuery("KOTLIN")
        advanceTimeBy(100)
        vm.setQuery("News")
        advanceTimeBy(251); runCurrent()
        assertEquals(listOf("two"), vm.state.value.articles.map { it.id })
        assertEquals(calls, repository.refreshCalls)
        vm.applyScope(SearchScope(query = "News", source = "missing"))
        runCurrent()
        assertTrue(vm.state.value.articles.isEmpty())
        assertEquals("missing", vm.state.value.source)
        assertEquals(calls, repository.refreshCalls)
    }

    private class CachedRepository : ArticleRepository {
        val articles = MutableStateFlow(listOf(
            Article("one", "Kotlin", "Dev", Instant.EPOCH, false, false, "<p>Content</p>", "feed/one"),
            Article("two", "News", "World", Instant.EPOCH, true, true, "<p>Content</p>", "feed/two")
        ))
        var refreshCalls = 0
        override fun getPagedArticles() = articles
        override suspend fun refresh() { refreshCalls++; throw IOException("Offline") }
        override suspend fun loadMore() = Unit
        override suspend fun markRead(id: String) = Unit
        override suspend fun toggleStar(id: String) = Unit
    }
}
