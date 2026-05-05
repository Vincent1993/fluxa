package com.fluxa.app.domain.usecase

import com.fluxa.app.data.repository.ArticleRepository
import javax.inject.Inject

class AddHighlightUseCase @Inject constructor(
    private val repository: ArticleRepository
) {
    suspend operator fun invoke(articleId: String, text: String) {
        repository.addHighlight(articleId, text)
        repository.syncPendingActions()
    }
}
