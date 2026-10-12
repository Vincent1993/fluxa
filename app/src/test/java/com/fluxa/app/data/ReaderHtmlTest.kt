package com.fluxa.app.data

import com.fluxa.app.data.reader.*
import org.jsoup.Jsoup
import org.junit.Test
import org.junit.Assert.*

class ReaderHtmlTest {
    @Test fun highlightsEscapeUserTextAndCannotEnableActiveFeedContent() {
        val annotation = ReaderAnnotation("id", "article", "highlight", "A & B", "", "red;url(javascript:evil)", 1, 1)
        val result = ReaderHtml.render("<p>A &amp; B</p><script>alert(1)</script><iframe src='https://evil.test'></iframe><a href='javascript:evil'>Link</a>", "https://example.test/story", listOf(annotation))
        val doc = Jsoup.parse(result)
        assertEquals("A & B", doc.selectFirst("mark")!!.text())
        assertTrue(doc.select("script,iframe").isEmpty())
        assertFalse(doc.selectFirst("a")!!.hasAttr("href"))
        assertEquals("background-color:#f1d28a;color:#25231e", doc.selectFirst("mark")!!.attr("style"))
    }
    @Test fun highlightsSpanInlineMarkupAndPreserveFormatting() {
        val annotation = ReaderAnnotation("id", "article", "highlight", "Hello reader", "", "#f1d28a", 1, 1)
        val result = Jsoup.parse(ReaderHtml.render("<p>Hello <b>reader</b> and text</p>", "", listOf(annotation)))
        assertEquals("Hello reader and text", result.text())
        assertEquals("reader", result.selectFirst("b mark")!!.text())
        assertEquals(2, result.select("mark").size)
    }
    @Test fun metadataTextAndWordCountHandleCjkAndIgnoreScriptContent() {
        val text = ReaderHtml.text("<p>Hello reader 世界</p><script>secret payload</script>")
        assertEquals("Hello reader 世界", text)
        assertEquals(4, ReaderHtml.wordCount(text))
    }
}
