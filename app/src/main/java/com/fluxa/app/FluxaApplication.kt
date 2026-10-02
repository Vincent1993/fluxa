package com.fluxa.app

import android.app.Application
import androidx.work.Configuration
import com.fluxa.app.data.sync.BackgroundSyncScheduler
import com.fluxa.app.data.sync.SyncWorkerFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FluxaApplication : Application(), Configuration.Provider {
    @Inject lateinit var syncWorkerFactory: SyncWorkerFactory
    @Inject lateinit var syncScheduler: BackgroundSyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(syncWorkerFactory).build()

    override fun onCreate() {
        super.onCreate()
        syncScheduler.restore()
    }
}
