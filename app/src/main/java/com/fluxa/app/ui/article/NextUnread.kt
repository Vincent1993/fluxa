package com.fluxa.app.ui.article

import com.fluxa.app.data.local.ReaderPreferences
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.feedlist.*

/** Capture once. This object never observes refreshes and never mutates a pre-existing queue. */
internal class NextUnreadSelection private constructor(private val ids: List<String>) {
    internal val firstId: String? get() = ids.firstOrNull()
    suspend fun freeze(reader: ReaderPreferences): String? {
        val selected = ids.firstOrNull() ?: return null
        return selected.takeIf { reader.awaitAndFreeze(ids, selected) }
    }
    companion object {
        fun capture(all: List<Article>, current: String, archived: Set<String>, config: TriageConfig,
            engine: TriageEngine = TriageEngine()): NextUnreadSelection = NextUnreadSelection(
            engine.apply(all.filter { it.id != current && it.source !in archived },
                SearchScope(filter = ArticleFilter.Unread), config.copy(collapse = false))
                .articles.map { it.id }.distinct().toList())
    }
}
