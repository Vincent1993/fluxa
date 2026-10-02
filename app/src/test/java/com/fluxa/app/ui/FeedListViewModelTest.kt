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
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun refreshFailureKeepsCachedArticlesVisible() = runTest(dispatcher) {
        val repository = CachedRepository()
        val vm = FeedListViewModel(repository, SavedStateHandle()).also { store.put("test", it) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        assertEquals(2, vm.state.value.articles.size)
        assertNotNull(vm.state.value.message)
        assertFalse(vm.state.value.busy)
    }

    @Test fun queryUnreadStarredAndSourceFiltersCombine() = runTest(dispatcher) {
        val vm = FeedListViewModel(CachedRepository(), SavedStateHandle()).also { store.put("test", it) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        vm.setFilter(ArticleFilter.Unread)
        vm.setQuery("KOTLIN")
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

    @Test fun concurrentRefreshClicksProduceOnlyOneRequest() = runTest(dispatcher) {
        val repository = CachedRepository()
        val vm = FeedListViewModel(repository, SavedStateHandle()).also { store.put("test", it) }
        repeat(5) { vm.refresh() }
        runCurrent()
        assertEquals(1, repository.refreshCalls)
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
