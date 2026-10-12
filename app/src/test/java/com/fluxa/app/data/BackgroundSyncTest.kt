package com.fluxa.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.SynchronousExecutor
import com.fluxa.app.data.repository.*
import com.fluxa.app.data.api.NewsBlurApiException
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import com.fluxa.app.data.sync.*
import com.fluxa.app.domain.model.Article
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BackgroundSyncTest {
    private lateinit var context: Context
    private lateinit var settings: BackgroundSyncSettings
    private val repository = RecordingRepository()
    private var session = true
    private val auth = object : AuthRepository {
        override fun hasSession() = session
        override suspend fun exchangeCode(code: String) = Unit
        override suspend fun refreshIfNeeded(): String? = null
        override suspend fun logout() = Unit
    }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("fluxa_sync_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        settings = BackgroundSyncSettings(context)
    }

    private fun worker(attempt: Int = 0) = TestListenableWorkerBuilder<NewsBlurSyncWorker>(context)
        .setRunAttemptCount(attempt)
        .setWorkerFactory(SyncWorkerFactory(Lazy { repository }, Lazy { auth }, settings)).build()

    @Test fun disabledAndLoggedOutSessionsNeverRefresh() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        settings.setEnabled(true)
        session = false
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(0, repository.calls)
    }

    @Test fun enabledSessionUsesAllFeedRefresh() = runBlocking {
        settings.setEnabled(true)
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(1, repository.calls)
    }

    @Test fun networkFailuresHaveFiniteRetryBudget() = runBlocking {
        settings.setEnabled(true)
        repository.failure = IOException("offline fixture")
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals(ListenableWorker.Result.success(), worker(2).doWork())
    }

    @Test fun cancellationPropagates() = runBlocking {
        settings.setEnabled(true)
        repository.failure = CancellationException("stopped")
        try { worker().doWork(); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
    }

    @Test fun rateLimitsAndServerFailuresRetryButAuthenticationFailureDoesNot() = runBlocking {
        settings.setEnabled(true)
        for (code in listOf(429, 503, 401)) {
            repository.failure = HttpException(Response.error<Any>(code, "{}".toResponseBody()))
            val expected = if (code == 401) ListenableWorker.Result.success() else ListenableWorker.Result.retry()
            assertEquals(expected, worker().doWork())
        }
    }

    @Test fun businessRejectionDoesNotRetryAsAnOfflineFailure() = runBlocking {
        settings.setEnabled(true)
        repository.failure = NewsBlurApiException("fixture rejection")
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
    }

    @Test fun periodicWorkIsUniquePersistsPreferenceAndCancels() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val scheduler = BackgroundSyncScheduler(context, settings)
        assertFalse(settings.enabled)
        scheduler.setEnabled(true)
        scheduler.setEnabled(true)
        val manager = WorkManager.getInstance(context)
        val before = manager.getWorkInfosForUniqueWork(BackgroundSyncScheduler.WORK_NAME).get()
        assertEquals(1, before.size)
        assertTrue(BackgroundSyncSettings(context).enabled)
        scheduler.restore()
        assertEquals(before.single().id, manager.getWorkInfosForUniqueWork(BackgroundSyncScheduler.WORK_NAME).get().single().id)
        scheduler.setEnabled(false)
        assertFalse(BackgroundSyncSettings(context).enabled)
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfosForUniqueWork(BackgroundSyncScheduler.WORK_NAME).get().single().state)
    }

    @Test fun periodicRequestKeepsNetworkBatteryAndRetryBudget() {
        val work = BackgroundSyncScheduler.request().workSpec
        assertEquals(java.util.concurrent.TimeUnit.HOURS.toMillis(1), work.intervalDuration)
        assertEquals(java.util.concurrent.TimeUnit.HOURS.toMillis(1), work.initialDelay)
        assertEquals(java.util.concurrent.TimeUnit.MINUTES.toMillis(15), work.backoffDelayDuration)
        assertEquals(BackoffPolicy.EXPONENTIAL, work.backoffPolicy)
        assertEquals(NetworkType.UNMETERED, work.constraints.requiredNetworkType)
        assertTrue(work.constraints.requiresBatteryNotLow())
    }

    private class RecordingRepository : ArticleRepository {
        var calls = 0
        var failure: Exception? = null
        override fun getPagedArticles() = flowOf(emptyList<Article>())
        override suspend fun refresh() { error("Foreground refresh must not be used") }
        override suspend fun refreshInBackground() { calls++; failure?.let { throw it } }
        override suspend fun loadMore() = Unit
        override suspend fun markRead(id: String) = Unit
        override suspend fun toggleStar(id: String) = Unit
    }
}
