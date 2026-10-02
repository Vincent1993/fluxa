package com.fluxa.app.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fluxa.app.data.api.NewsBlurApiException
import com.fluxa.app.data.repository.ArticleRepository
import com.fluxa.app.data.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

class NewsBlurSyncWorker(
    context: Context,
    parameters: WorkerParameters,
    private val repository: ArticleRepository,
    private val auth: AuthRepository,
    private val settings: BackgroundSyncSettings
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        try {
            if (!settings.enabled || !auth.hasSession()) return Result.success()
            repository.refreshInBackground()
            return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: NewsBlurApiException) {
            // Business rejection is not fixed by an immediate retry.
            return Result.success()
        } catch (http: HttpException) {
            return if (http.code() == 429 || http.code() >= 500) retryWithinBudget() else Result.success()
        } catch (offline: IOException) {
            return retryWithinBudget()
        } catch (invalidState: IllegalStateException) {
            // Expired/missing session or unavailable local credentials: foreground login can resolve it.
            return Result.success()
        }
    }

    private fun retryWithinBudget() = if (runAttemptCount < 2) Result.retry() else Result.success()
}
