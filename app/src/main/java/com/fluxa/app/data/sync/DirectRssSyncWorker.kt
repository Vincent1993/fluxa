package com.fluxa.app.data.sync

import android.content.Context
import androidx.work.*
import com.fluxa.app.data.directrss.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Separate public-feed work: no ArticleRepository composite, session, cookies or NewsBlur replay. */
class DirectRssSyncWorker(
    context: Context, parameters: WorkerParameters,
    private val repository: DirectRssRepository,
    private val settings: LocalOfflineSettings
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!settings.policy().backgroundEnabled) return Result.success()
        return try {
            repository.refreshStale()
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (retryAllowed(failure, runAttemptCount)) Result.retry() else Result.failure()
        }
    }
    companion object {
        internal fun retryAllowed(failure: Throwable, attempts: Int) = attempts < 2 && isRetryableDirectFailure(failure)
    }
}

@Singleton
class DirectRssSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: LocalOfflineSettings
) {
    fun restore() {
        val manager = WorkManager.getInstance(context)
        if (!settings.policy().backgroundEnabled) manager.cancelUniqueWork(WORK_NAME)
        else manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request(settings.policy()))
    }
    companion object {
        const val WORK_NAME = "direct_rss_periodic_sync"
        internal fun request(policy: LocalOfflinePolicy) = PeriodicWorkRequestBuilder<DirectRssSyncWorker>(1, TimeUnit.HOURS)
            .setInitialDelay(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(if (policy.imagesWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
    }
}
