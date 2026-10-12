package com.fluxa.app.domain.usecase

import com.fluxa.app.data.reader.LocalReadingRepository
import javax.inject.Inject

class AddNoteUseCase @Inject constructor(private val local: LocalReadingRepository) {
    suspend operator fun invoke(id: String, text: String) = local.note(id, text)
}
class AddHighlightUseCase @Inject constructor(private val local: LocalReadingRepository) {
    suspend operator fun invoke(id: String, quote: String, note: String = "") = local.highlight(id, quote, note)
}
class ArchiveArticleUseCase @Inject constructor(private val local: LocalReadingRepository) {
    suspend operator fun invoke(id: String) = local.archive(id)
}
class SaveForLaterUseCase @Inject constructor(private val local: LocalReadingRepository) {
    suspend operator fun invoke(id: String) = local.save(id)
}
