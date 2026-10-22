package com.fluxa.app.ui

import com.fluxa.app.data.directrss.*
import com.fluxa.app.ui.article.*
import com.fluxa.app.ui.feedlist.*
import com.fluxa.app.domain.model.Article
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class Stage4ReaderPolicyTest {
    @Test fun plainChunkBoundsAndSurrogatesExcludeAttributesAndScripts() {
        val source = "<p title='private attribute'>" + "汉字😀 &amp; ".repeat(1000) + "</p><script>never read this</script>"
        val chunks = speechChunks(source, 100)
        assertTrue(chunks.all { it.length < 100 && !Character.isHighSurrogate(it.last()) && !Character.isLowSurrogate(it.first()) })
        assertEquals(Jsoup.parseBodyFragment(source).also { it.select("script").remove() }.body().text(), chunks.joinToString(""))
        assertFalse(chunks.joinToString("").contains("private attribute"))
    }
    @Test fun stopPauseResumeAndLateCompletionCannotAdvanceAnotherLease() {
        val cursor = SpeechCursor(3)
        val first = cursor.start()!!
        val second = cursor.complete(first)!!
        assertEquals(1, cursor.index)
        assertNull(cursor.complete(first)); assertEquals(1, cursor.index)
        cursor.pause(); assertNull(cursor.complete(second)); assertFalse(cursor.playing)
        val resumed = cursor.start()!!
        assertNotEquals(second, resumed); assertEquals(1, cursor.index)
        cursor.stop(); assertNull(cursor.complete(resumed)); assertEquals(0, cursor.index)
        val next = cursor.start()!!
        assertNotEquals(first, next)
        cursor.complete(next)?.let { cursor.complete(it) }?.let { cursor.complete(it) }
        assertFalse(cursor.playing); assertEquals(3, cursor.index)
        assertNull(SpeechCursor(0).start())
    }
    @Test fun repeatedParagraphModeChangedContentAndNoteEscapingNeverAttachWrongQuote() {
        val html = "<p>repeat &amp; quote</p><p>different</p><p>repeat &amp; quote <a href='https://example.org'>link</a></p><p>repeat &amp; quote</p>"
        val choices = ReaderQuotes.paragraphs(html)
        val chosen = choices.last()
        assertEquals(1, chosen.occurrence); assertEquals(2, chosen.occurrences)
        val note = DirectReaderNote("id", "article", "RSS", chosen.text, chosen.hash, chosen.occurrence, chosen.occurrences, "<script>alert(1)</script>")
        ReaderQuotes.validate(note)
        val marked = Jsoup.parseBodyFragment(ReaderQuotes.highlight(html, "RSS", listOf(note)))
        assertEquals(1, marked.select("mark").size)
        assertEquals(chosen.text, marked.select("p").last()!!.select("mark").text())
        assertEquals("https://example.org", marked.select("a").attr("href"))
        assertTrue(marked.select("script").isEmpty())
        assertEquals(html, ReaderQuotes.highlight(html, "FULL_TEXT", listOf(note)))
        assertTrue(Jsoup.parseBodyFragment(ReaderQuotes.highlight("<p>repeat &amp; quote</p>", "RSS", listOf(note))).select("mark").isEmpty())
        assertTrue(Jsoup.parseBodyFragment(ReaderQuotes.highlight("<p>changed</p>", "RSS", listOf(note))).select("mark").isEmpty())
        assertEquals(note, note.copy()) // unmatched note metadata is not rewritten by rendering
        try { ReaderQuotes.validate(note.copy(quoteHash = "not a digest")); fail("Invalid anchor") } catch (_: IllegalArgumentException) { }
    }
    @Test fun readLaterScopeIsIndependentFromStar() {
        val rows = listOf(
            Article("a", "later", "feed", Instant.EPOCH, true, false, "", readLater = true),
            Article("b", "star", "feed", Instant.EPOCH, false, true, ""))
        val engine = TriageEngine()
        assertEquals(listOf("a"), engine.apply(rows, SearchScope(filter = ArticleFilter.ReadLater), TriageConfig()).articles.map { it.id })
        assertEquals(listOf("b"), engine.apply(rows, SearchScope(filter = ArticleFilter.Starred), TriageConfig()).articles.map { it.id })
    }
}
