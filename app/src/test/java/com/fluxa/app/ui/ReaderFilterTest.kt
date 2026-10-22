package com.fluxa.app.ui

import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.feedlist.*
import org.junit.Test
import org.junit.Assert.*
import java.time.Instant
import java.time.ZoneId

class ReaderFilterTest {
    private val article = Article("one", "Title", "Source", Instant.parse("2026-10-12T23:30:00Z"), false, true,
        "<p>Body</p>", "feed/one", author = "Ada", tags = listOf("Tech"), savedAtEpochSeconds = 100, annotationCount = 1)
    @Test fun tagDatesSourceUnreadStarredAndSearchCombineWithoutLosingBoundaries() {
        val state = FeedListState(query = "ada", filter = ArticleFilter.Starred, source = "feed/one", tag = "tech", dateFrom = "2026-10-12", dateTo = "2026-10-12")
        assertTrue(matchesReaderFilter(article, state, ZoneId.of("UTC")))
        assertFalse(matchesReaderFilter(article, state.copy(tag = "Other"), ZoneId.of("UTC")))
        assertFalse(matchesReaderFilter(article, state, ZoneId.of("Asia/Shanghai")))
        assertTrue(matchesReaderFilter(article, state.copy(dateFrom = "2026-10-13", dateTo = "2026-10-13"), ZoneId.of("Asia/Shanghai")))
    }
    @Test fun archivedSavedAndAnnotatedCollectionsRespectLocalState() {
        val archived = article.copy(archivedAtEpochSeconds = 10)
        assertFalse(matchesReaderFilter(archived, FeedListState()))
        assertTrue(matchesReaderFilter(archived, FeedListState(filter = ArticleFilter.Archived)))
        assertTrue(matchesReaderFilter(article, FeedListState(filter = ArticleFilter.Saved)))
        assertFalse(matchesReaderFilter(article.copy(savedAtEpochSeconds = null), FeedListState(filter = ArticleFilter.Saved)))
        assertTrue(matchesReaderFilter(article, FeedListState(filter = ArticleFilter.Annotated)))
    }
    @Test fun savedAliasCombinesRemoteMetadataAndLocalReadLaterWithoutDuplicateChoices() {
        assertEquals(ArticleFilter.ReadLater, ArticleFilter.Saved)
        assertEquals(ArticleFilter.ReadLater, ArticleFilter.fromPersisted("Saved"))
        assertEquals(ArticleFilter.entries.size, ArticleFilter.entries.map { it.label }.distinct().size)
        val local = article.copy(id = "local", savedAtEpochSeconds = null, readLater = true)
        val neither = article.copy(id = "neither", savedAtEpochSeconds = null)
        val scope = SearchScope(filter = ArticleFilter.fromPersisted("Saved"))
        assertEquals(setOf(article.id, local.id), TriageEngine().apply(listOf(article, local, neither),
            scope, TriageConfig()).articles.map { it.id }.toSet())
        assertTrue(matchesReaderFilter(local, FeedListState(filter = scope.filter)))
        assertFalse(matchesReaderFilter(neither, FeedListState(filter = scope.filter)))
    }
    @Test fun mergedTriageKeepsPublishedArchivesAndLocalTagsReachable() {
        val archived = article.copy(archivedAtEpochSeconds = 10)
        val engine = TriageEngine()
        assertTrue(engine.apply(listOf(archived), SearchScope(), TriageConfig()).articles.isEmpty())
        assertEquals(listOf(archived), engine.apply(listOf(archived),
            SearchScope(filter = ArticleFilter.Archived, tag = "tech"), TriageConfig()).articles)
        assertTrue(engine.apply(listOf(archived), SearchScope(filter = ArticleFilter.Archived, tag = "other"),
            TriageConfig()).articles.isEmpty())
        assertEquals(listOf(article), engine.apply(listOf(article),
            SearchScope(filter = ArticleFilter.Annotated, query = "ada"), TriageConfig()).articles)
    }
}
