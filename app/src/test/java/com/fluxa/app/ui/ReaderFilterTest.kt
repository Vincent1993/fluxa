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
}
