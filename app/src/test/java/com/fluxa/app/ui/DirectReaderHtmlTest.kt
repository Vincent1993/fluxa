package com.fluxa.app.ui

import com.fluxa.app.data.local.ReaderSettings
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.article.readerHtml
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DirectReaderHtmlTest {
    private val article = Article("direct-rss:test", "Title", "Source", Instant.EPOCH, false, false,
        "<h2>Body</h2>", originalUrl = "https://www.ifanr.com/123?a=1&b=2", sourceNote = "部分图片需联网")
    @Test fun originalLinkIsEscapedAndAccessibleAfterBody() {
        val html = readerHtml(article, ReaderSettings(), false)
        assertTrue(html.contains("href=\"https://www.ifanr.com/123?a=1&amp;b=2\""))
        assertTrue(html.indexOf("阅读原文") > html.indexOf("</main>"))
        assertTrue(html.contains("部分图片需联网"))
        assertTrue(html.contains("img-src https: http: data:"))
        assertTrue(html.contains("max-width:100%;height:auto"))
        val quietHtml = readerHtml(article.copy(sourceNote = ""), ReaderSettings(), false)
        assertTrue(quietHtml.contains("href=\"https://www.ifanr.com/123?a=1&amp;b=2\""))
        assertFalse(quietHtml.contains("<p></p>"))
        assertFalse(quietHtml.contains("部分图片需联网"))
    }
    @Test fun oldArticlesAndUnsafeLinksDoNotGetFooter() {
        listOf("", "javascript:bad()", "file:///private/file", "https://user:password@example.org/a", "https://example.org/#fragment").forEach {
            assertFalse(readerHtml(article.copy(originalUrl = it), ReaderSettings(), false).contains("<footer>"))
        }
    }
}
