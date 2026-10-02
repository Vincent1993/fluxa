package com.fluxa.app.data.sync

import android.content.Context
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackgroundSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: BackgroundSyncSettings
) {
    fun setEnabled(enabled: Boolean) {
        settings.setEnabled(enabled)
        if (enabled) schedule() else WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun restore() {
        if (settings.enabled) schedule()
        else if (settings.configured) WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private fun schedule() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request()
        )
    }

    companion object {
        const val WORK_NAME = "newsblur_periodic_sync"

        internal fun request() = PeriodicWorkRequestBuilder<NewsBlurSyncWorker>(1, TimeUnit.HOURS)
            .setInitialDelay(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
    }
}
