package com.fluxa.app.data.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dagger.Lazy
import com.fluxa.app.data.repository.ArticleRepository
import com.fluxa.app.data.repository.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncWorkerFactory @Inject constructor(
    private val repository: Lazy<ArticleRepository>,
    private val auth: Lazy<AuthRepository>,
    private val settings: BackgroundSyncSettings,
    private val direct: Lazy<com.fluxa.app.data.directrss.DirectRssRepository>,
    private val offline: com.fluxa.app.data.directrss.LocalOfflineSettings
) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String,
        workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == NewsBlurSyncWorker::class.java.name)
            NewsBlurSyncWorker(appContext, workerParameters, repository.get(), auth.get(), settings)
        else if (workerClassName == DirectRssSyncWorker::class.java.name)
            DirectRssSyncWorker(appContext, workerParameters, direct.get(), offline)
        else null
}
