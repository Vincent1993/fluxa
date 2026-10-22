package com.fluxa.app.data

import com.fluxa.app.data.directrss.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DirectOpmlShareTest {
    private fun parse(body: String, version: String = "2.0") = DirectOpml.parse(
        "<opml version=\"$version\"><body>$body</body></opml>".toByteArray())
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected bounded/invalid input rejection") } catch (_: Exception) { }
    }

    @Test fun nestedUnicodeInvalidAndDuplicateRecordsRemainVisibleWithoutIncludes() {
        for (version in listOf("1.0", "1.1", "2.0")) {
            val rows = parse("""<outline text="科技"><outline text="A &amp; B">
                <outline type="rss" text="新闻" xmlUrl="https://EXAMPLE.org/feed"/>
                <outline text="duplicate" xmlUrl="https://example.org/feed"/>
                <outline type="rss" text="unsafe" xmlUrl="https://user:pass@example.org/feed"/>
                <outline type="include" url="https://example.org/never-fetch"/>
                </outline></outline>""", version)
            assertEquals(3, rows.size)
            assertEquals("科技 / A & B", rows[0].folder)
            assertEquals(subscriptionIdentity("https://example.org/feed"), rows[0].identity)
            assertNotNull(rows[1].error); assertNotNull(rows[2].error)
        }
    }

    @Test fun ownExportRoundTripsLongPublisherTitleEscapesUnicodeDailyAndFolders() {
        val title = "长标题 & <新闻> \"引号\" '引用'" + "界".repeat(4000)
        val feeds = listOf(
            DirectSubscription("a", title, "https://example.org/feed?a=1&b=2", false, folderId = "folder"),
            DirectSubscription("b", "早报", IFANR_FEED, true),
            DirectSubscription("c", "全站", IFANR_FEED, false),
            DirectSubscription("d", "archived", "https://example.org/old", false, archived = true))
        val rows = DirectOpml.parse(DirectOpml.export(feeds, listOf(DirectFolder("folder", "科技 & 阅读"))))
        assertEquals(3, rows.size); assertTrue(rows.all { it.error == null })
        assertEquals(title, rows[0].name); assertEquals("科技 & 阅读", rows[0].folder)
        assertEquals(feeds.take(3).map { subscriptionIdentity(it.url, it.dailyOnly) }, rows.map { it.identity })
        assertNotEquals(rows[1].identity, rows[2].identity)
        assertFalse(parse("<outline text=\"generic\" xmlUrl=\"$IFANR_FEED\"/>").single().daily)
    }

    @Test fun rejectsDtdEvenUtf16AndStructuralAndEncodedLimits() {
        val dtd = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><!DOCTYPE opml [<!ENTITY x SYSTEM 'file:///not-read'>]><opml version=\"2.0\"><body><outline text=\"&x;\"/></body></opml>"
        rejects { DirectOpml.parse(dtd.toByteArray(Charsets.UTF_16)) }
        rejects { DirectOpml.parse("<opml version=\"2.0\"/>".toByteArray()) }
        rejects { parse("", "3.0") }
        rejects { parse("<outline text=\"${"x".repeat(81)}\"><outline xmlUrl=\"https://example.org\"/></outline>") }
        rejects { parse((1..501).joinToString("") { "<outline xmlUrl=\"https://example.org/$it\"/>" }) }
        rejects { parse("<outline text=\"x\">".repeat(65) + "</outline>".repeat(65)) }
        rejects { DirectOpml.read(ByteArray(DirectOpml.MAX_BYTES + 1).inputStream()) }
        rejects { DirectOpml.parse(ByteArray(DirectOpml.MAX_BYTES + 1)) }
    }

    @Test fun sendIntentRequiresSupportedActionAndMimeAndDecodesHtmlExtras() {
        fun intent(action: String = android.content.Intent.ACTION_SEND, mime: String = "text/html") =
            android.content.Intent(action).setType(mime).putExtra(android.content.Intent.EXTRA_TEXT,
                "<a href='https://example.org/a?x=1&amp;y=2'>Article</a>")
        assertEquals(listOf("https://example.org/a?x=1&y=2"), com.fluxa.app.sharedLinksFromIntent(intent())!!.urls)
        assertNull(com.fluxa.app.sharedLinksFromIntent(intent(android.content.Intent.ACTION_SEND_MULTIPLE)))
        assertNull(com.fluxa.app.sharedLinksFromIntent(intent(mime = "image/png")))
        assertTrue(com.fluxa.app.sharedLinksFromIntent(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain"))!!.urls.isEmpty())
    }

    @Test fun sharesRejectUnsafeAndAmbiguousInputsAreNotAutoSelected() {
        assertTrue(sharedLinks("file:///private javascript:alert(1) https://u:p@example.org/").urls.isEmpty())
        val links = sharedLinks("Title https://example.org/a and https://example.org/b", "<a href='https://example.org/a'>A</a><script>ignore()</script>")
        assertEquals(listOf("https://example.org/a", "https://example.org/b"), links.urls)
        assertNotNull(links.message)
        assertTrue(sharedLinks("x".repeat(65537)).urls.isEmpty())
        assertTrue(sharedLinks((1..34).joinToString(" ") { "https://example.org/$it" }).urls.isEmpty())
    }

    @Test fun identifiedArticleBodyExcludesPublisherHeaderWithoutDeletingBodyHeadingsOrResources() {
        val paragraph = "Legitimate publisher body with sufficient prose and detail. ".repeat(8)
        for (marker in listOf("class='entry-content'", "class='article-body'", "itemprop='articleBody'", "itemprop='text articleBody'")) {
            val page = WebPageExtractor.parse("""<html><title>Publisher title</title><main><article>
                <h1 class="entry-title">Publisher title</h1><div class="entry-meta">Author Name · October 9, 2026</div>
                <div $marker><h1>Legitimate body heading</h1><p>$paragraph</p><h2>Body section</h2>
                <p>$paragraph <a href="/reference">Body reference</a></p><img src="/photo.jpg" alt="Body photograph">
                <script>neverRun()</script></div></article></main></html>""".toByteArray(), "https://example.org/story")
            val body = org.jsoup.Jsoup.parseBodyFragment(page.body)
            assertEquals("Publisher title", page.title)
            assertFalse(body.text().contains("Publisher title"))
            assertFalse(body.text().contains("Author Name")); assertFalse(body.text().contains("October 9"))
            assertEquals("Legitimate body heading", body.selectFirst("h1")!!.text())
            assertEquals("Body section", body.selectFirst("h2")!!.text())
            assertEquals("https://example.org/reference", body.selectFirst("a")!!.attr("href"))
            assertEquals("https://example.org/photo.jpg", body.selectFirst("img")!!.attr("src"))
            assertEquals("Body photograph", body.selectFirst("img")!!.attr("alt"))
            assertTrue(body.select("script").isEmpty())
        }
    }

    @Test fun lowDensityBodyMarkerDoesNotSuppressGenericReadableBody() {
        val paragraph = "Generic content without a reliable body wrapper. ".repeat(8)
        for (container in listOf("article", "main", "div")) {
            val page = WebPageExtractor.parse("""<title>Generic title</title><$container>
                <div class="entry-content"><a href="/login">Sign in</a></div>
                <h1>Body heading</h1><p>$paragraph</p><h2>Body section</h2><p>$paragraph</p>
                <a href="/more">Read more</a><img src="/photo.jpg"></$container>""".toByteArray(), "https://example.org/story")
            val body = org.jsoup.Jsoup.parseBodyFragment(page.body)
            assertEquals("Body heading", body.selectFirst("h1")!!.text())
            assertEquals("Body section", body.selectFirst("h2")!!.text())
            assertTrue(body.text().contains(paragraph.trim()))
            assertEquals("https://example.org/photo.jpg", body.selectFirst("img")!!.attr("src"))
            assertTrue(body.select("a[href=https://example.org/more]").isNotEmpty())
        }
    }

    @Test fun extractionHandlesArticleMainDensityRelativeResourcesAndNeverKeepsExecutableHtml() {
        val paragraph = "Actual publisher sentence with sufficient detail to be plausible article content. ".repeat(3)
        for (container in listOf("article", "main", "div")) {
            val page = WebPageExtractor.parse("""<html><head><title>Document title</title><meta property="og:title" content="Publisher title"></head>
                <body><nav>Navigation</nav><$container><p>$paragraph</p><p>$paragraph</p>
                <img src="../photo.jpg"><img src="/pixel" width="1"><a href="next">Next</a>
                <script>alert(1)</script><iframe src="https://unsafe.example"></iframe><form>Login</form>
                </$container></body></html>""".toByteArray(), "https://example.org/news/current")
            assertEquals("Publisher title", page.title)
            assertTrue(page.body.contains("https://example.org/photo.jpg"))
            assertTrue(page.body.contains("https://example.org/news/next"))
            for (unsafe in listOf("<script", "<iframe", "<form", "Navigation", "/pixel")) assertFalse(page.body.contains(unsafe))
        }
        val malformed = WebPageExtractor.parse("<h1>Heading fallback</h1><main><p>$paragraph<p>$paragraph".toByteArray(), "https://example.org/")
        assertEquals("Heading fallback", malformed.title)
        rejects { WebPageExtractor.parse("<title>Login</title><main><p>Sign in</p></main>".toByteArray(), "https://example.org/") }
    }
}
