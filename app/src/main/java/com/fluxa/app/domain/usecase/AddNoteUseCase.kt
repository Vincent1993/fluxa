package com.fluxa.app.domain.usecase

import com.fluxa.app.data.repository.ArticleRepository
import javax.inject.Inject

class AddNoteUseCase @Inject constructor(
    private val repository: ArticleRepository
) {
    suspend operator fun invoke(articleId: String, note: String) {
        repository.addNote(articleId, note)
        repository.syncPendingActions()
    }
}
