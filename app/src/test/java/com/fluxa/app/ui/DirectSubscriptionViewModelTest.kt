package com.fluxa.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.fluxa.app.data.repository.ArticleRepository
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.feedlist.FeedListViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class DirectSubscriptionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { store.clear(); Dispatchers.resetMain() }
    @Test fun failedLocalAttemptRemainsRetryableAndOnlySuccessChangesCloseToken() = runTest(dispatcher) {
        val repository = LocalStub()
        val vm = FeedListViewModel(repository, SavedStateHandle(), triageRuntime = com.fluxa.app.ui.feedlist.TriageRuntime(dispatcher, java.time.Clock.systemUTC())).also { store.put("local", it) }
        runCurrent()
        vm.addLocalSubscription("https://www.ifanr.com/feed", true)
        vm.addLocalSubscription("https://www.ifanr.com/feed", true)
        runCurrent()
        assertEquals(1, repository.attempts)
        assertEquals(0, vm.state.value.subscriptionSuccess)
        assertEquals("离线重试", vm.state.value.message)
        assertFalse(vm.state.value.busy)
        repository.fail = false
        vm.addLocalSubscription("https://www.ifanr.com/feed", true)
        runCurrent()
        assertEquals(2, repository.attempts)
        assertEquals(1, vm.state.value.subscriptionSuccess)
        assertEquals(0, repository.remoteAdds)
        assertTrue(repository.daily)
    }
    private class LocalStub : ArticleRepository {
        var fail = true; var attempts = 0; var remoteAdds = 0; var daily = false
        override fun getPagedArticles() = flowOf(emptyList<Article>())
        override suspend fun refresh() = Unit
        override suspend fun loadMore() = Unit
        override suspend fun markRead(id: String) = Unit
        override suspend fun toggleStar(id: String) = Unit
        override suspend fun addSubscription(url: String) { remoteAdds++ }
        override suspend fun addLocalSubscription(url: String, dailyOnly: Boolean) {
            attempts++; daily = dailyOnly
            if (fail) throw IllegalStateException("离线重试")
        }
    }
}
