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
    private val settings: BackgroundSyncSettings
) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String,
        workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == NewsBlurSyncWorker::class.java.name)
            NewsBlurSyncWorker(appContext, workerParameters, repository.get(), auth.get(), settings)
        else null
}
