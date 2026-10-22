package com.fluxa.app.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.feedlist.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class TriageStage3Test {
    private val now = Instant.ofEpochSecond(200000)
    private val engine = TriageEngine(Clock.fixed(now, ZoneOffset.UTC))
    private fun article(id: String, seconds: Long = 200000, source: String = "direct-rss:a", read: Boolean = false,
        body: String = "<p>Alpha &amp; beta</p>", url: String = "https://example.org/$id") =
        Article(id, "Title $id", source, Instant.ofEpochSecond(seconds), read, false, body, source, url)

    @Test fun inclusiveCutoffNoFutureAndStablePerSourceOrderAndHideRead() {
        val all = listOf(article("b"), article("a"), article("cutoff", 113600), article("old", 113599), article("future", 200001), article("invalid", 0))
        assertEquals(listOf("a", "b", "cutoff"), engine.apply(all, SearchScope(filter = ArticleFilter.Last24Hours), TriageConfig()).articles.map { it.id })
        val config = TriageConfig(sources = mapOf("direct-rss:a" to SourceTriage(oldestFirst = true, hideRead = true)))
        assertEquals(listOf("invalid", "old", "cutoff", "a", "b", "future"), engine.apply(all.reversed(), SearchScope(source = "direct-rss:a"), config).articles.map { it.id })
        val read = listOf(article("a", read = true), article("b", source = "direct-rss:b", read = true))
        assertEquals(listOf("b"), engine.apply(read, SearchScope(), config).articles.map { it.id })
        assertEquals(2, engine.apply(read, SearchScope(showHidden = true), config).articles.size)
    }

    @Test fun searchIsDecodedProseNotTagsCssOrBase64AndCountsRespectScope() {
        val all = listOf(article("a", body = "<style>secretCSS</style><script>secretScript</script><p>Alpha &amp; beta</p><img src='data:image/png;base64,secretPayload' title='secretAttr'>"),
            article("b", source = "direct-rss:b", read = true).copy(isStarred = true))
        for (q in listOf("secretCSS", "secretScript", "secretPayload", "secretAttr", "<p>"))
            assertTrue(engine.apply(all, SearchScope(query = q), TriageConfig()).articles.isEmpty())
        assertEquals(2, engine.apply(all, SearchScope(query = " ALPHA & beta "), TriageConfig()).articles.size)
        val result = engine.apply(all, SearchScope(source = "direct-rss:b"), TriageConfig())
        assertEquals(0, result.unread); assertEquals(1, result.starred)
        assertTrue(engine.apply(all, SearchScope(source = "missing"), TriageConfig()).articles.isEmpty())
    }

    @Test fun canonicalGroupingKeepsMeaningfulQueriesCaseAndEveryUnderlyingState() {
        assertEquals("https://example.org/Article?item=2", displayCanonical("https://EXAMPLE.org/Article?utm_source=x&item=2&fbclid=x#fragment"))
        assertNotEquals(displayCanonical("https://example.org/a?item=1"), displayCanonical("https://example.org/a?item=2"))
        assertNotEquals(displayCanonical("https://example.org/A"), displayCanonical("https://example.org/a"))
        assertNull(displayCanonical("https://user:password@example.org/a")); assertNull(displayCanonical("javascript:alert(1)"))
        val rows = listOf(article("b", url = "https://example.org/a?gclid=x", read = true),
            article("a", source = "direct-rss:b", url = "https://example.org/a#part").copy(readLater = true), article("c", url = "https://example.org/a?item=1"))
        val result = engine.apply(rows, SearchScope(), TriageConfig(collapse = true))
        assertEquals(listOf("a", "c"), result.articles.map { it.id })
        assertEquals(listOf("a", "b"), result.groups.getValue("a").map { it.id })
        assertTrue(result.groups.getValue("a")[1].isRead); assertFalse(result.articles[0].isRead)
        assertEquals(3, rows.size)
        assertEquals(2, engine.apply(rows, SearchScope(filter = ArticleFilter.Unread), TriageConfig(collapse = true)).articles.size)
    }

    @Test fun rulesPreviewDisableAndDeleteUndoArePureAndBlankCannotBlockEverything() {
        val rows = listOf(article("a").copy(categories = listOf("早报"), author = "Editor"), article("b"))
        val keep = TriageRule("keep", "direct-rss:a", RuleKind.KeepCategory, "早报")
        val block = TriageRule("block", null, RuleKind.BlockAuthor, "editor")
        assertTrue(keep.matches(rows[0], plainBody(rows[0].contentHtml))); assertFalse(keep.matches(rows[1], ""))
        assertTrue(keep.matches(rows[1], "历史早报正文"))
        assertFalse(keep.matches(rows[1].copy(categories = listOf("Other")), "历史早报正文"))
        val config = TriageConfig(rules = listOf(keep, block))
        assertTrue(engine.apply(rows, SearchScope(), config).articles.isEmpty())
        assertEquals(listOf("a"), engine.apply(rows, SearchScope(), config.copy(rules = listOf(keep, block.copy(enabled = false)))).articles.map { it.id })
        val removed = config.copy(rules = emptyList())
        assertEquals(rows, engine.apply(rows, SearchScope(), removed).articles)
        assertTrue(engine.apply(rows, SearchScope(), removed.copy(rules = config.rules)).articles.isEmpty())
        assertEquals(rows, engine.apply(rows, SearchScope(showHidden = true), config).articles)
        assertTrue(runCatching { TriageRule("bad", null, RuleKind.BlockKeyword, " ") }.isFailure)
        assertTrue(TriageRule("keyword", "direct-rss:a", RuleKind.KeepKeyword, "alpha & beta").matches(rows[1], plainBody(rows[1].contentHtml)))
    }

    @Test fun fullSavedSearchAndRulesReopenMissingSourceDoesNotBroadenAndBoundsEnforced() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("rss_triage_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val scope = SearchScope("beta", "missing-source", ArticleFilter.Starred, 5, 100, true)
        val config = TriageConfig(mapOf("missing-source" to SourceTriage(true, true)), listOf(SavedSearch("s", "Owned", scope)),
            listOf(TriageRule("r", null, RuleKind.BlockKeyword, "blocked", false)), true)
        TriageSettings(context).save(config)
        assertEquals(config, TriageSettings(context).load())
        assertTrue(engine.apply(listOf(article("a")), TriageSettings(context).load().saved.single().scope, config).articles.isEmpty())
        assertTrue(runCatching { SearchScope("a".repeat(257)) }.isFailure)
        assertTrue(runCatching { SavedSearch("bad", "a".repeat(81), SearchScope()) }.isFailure)
        assertTrue(runCatching { TriageSettings(context).save(config.copy(saved = List(51) { SavedSearch("$it", "Owned", scope) })) }.isFailure)
        assertEquals(config, TriageSettings(context).load())
    }
}
