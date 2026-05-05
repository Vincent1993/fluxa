package com.fluxa.app.domain.usecase

import com.fluxa.app.data.repository.ArticleRepository
import javax.inject.Inject

class SaveForLaterUseCase @Inject constructor(
    private val repository: ArticleRepository
) {
    suspend operator fun invoke(articleId: String) {
        repository.saveForLater(articleId)
        repository.syncPendingActions()
    }
}
