package com.fluxa.app.data

import com.fluxa.app.data.directrss.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DirectRssDiscoveryTest {
    private lateinit var server: MockWebServer
    private val client = DirectRssClient()
    private val xml = "<rss><channel><title>Fixture source</title><item><title>Actual fixture article</title><guid>1</guid></item></channel></rss>"
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun close() { server.shutdown() }
    private fun reject(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: Exception) { } }

    @Test fun redirectedMalformedHtmlUsesFinalUrlDeduplicatesAndNeverFetchesCandidates() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/nested/page").setHeader("Set-Cookie", "private=no"))
        server.enqueue(MockResponse().setBody("""<!doctype html><html><head><base href='https://ignored.example/'>
            <link REL='alternate stylesheet' TYPE='application/rss+xml' href='feed' title='Fixture RSS'>
            <link rel=alternate type=application/rss+xml href='./feed'>
            <link rel=alternate type=application/atom+xml href='/atom' title='Fixture Atom'>
            <link rel=alternate type=application/rss+xml href='https://user:secret@example.org/private'>
            <link rel=alternate type=text/html href='/not-feed'><body><p>Unclosed page"""))
        val result = client.discover(server.url("/start").toString())
        assertNull(result.directFeed)
        assertEquals(listOf(server.url("/nested/feed").toString(), server.url("/atom").toString()), result.candidates.map { it.url })
        assertEquals(2, server.requestCount)
        server.takeRequest(); val request = server.takeRequest()
        assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
        server.enqueue(MockResponse().setBody(xml))
        assertEquals("Actual fixture article", client.fetch(result.candidates[1].url).items.single().title)
        assertEquals("/atom", server.takeRequest().path)
    }
    @Test fun directRssIsPreviewedWithOneRequestAndHtmlIsCapped() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(xml))
        val result = client.discover(server.url("/feed").toString())
        assertEquals("Fixture source", result.directFeed!!.title)
        assertEquals("Actual fixture article", result.directFeed!!.items.single().title)
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setBody("<html><head>" + (1..30).joinToString("") {
            "<link rel=alternate type=application/rss+xml href='/feed/$it'>"
        } + "</head></html>"))
        assertEquals(12, client.discover(server.url("/page").toString()).candidates.size)
        assertEquals(2, server.requestCount)
    }
    @Test fun emptyInvalidAndOversizeInputsAreNotCandidateSuccesses() {
        listOf("garbage", "<html><p>No feeds</html>", "<rss><channel/></rss>", "<rss><channel><item></channel>",
            "<!DOCTYPE rss><rss><channel><item/></channel></rss>",
            "<rss><channel>" + "<item/>".repeat(501) + "</channel></rss>").forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            reject { client.discover(server.url("/bad").toString()) }
        }
        server.enqueue(MockResponse().setChunkedBody("x".repeat(DirectRssClient.MAX_BYTES + 1), 8192))
        reject { client.discover(server.url("/large").toString()) }
        server.enqueue(MockResponse().setBody("x".repeat(DirectRssClient.MAX_BYTES + 1)))
        reject { client.discover(server.url("/large-known").toString()) }
    }
    @Test fun redirectValidationRejectsDowngradeCredentialsFragmentsAndNonHttp() {
        val from = "https://example.org/path/page"
        assertEquals("https://example.org/feed", redirectUrl(from, "../feed"))
        listOf("http://example.org/feed", "https://user:pass@example.org/feed", "https://example.org/feed#fragment", "file:///etc/passwd", null).forEach {
            reject { redirectUrl(from, it) }
        }
    }
    @Test fun utf16AtomIsDirectRatherThanHtml() {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(
            "<feed xmlns='http://www.w3.org/2005/Atom'><title>Fixture Atom</title><entry><id>1</id><title>Fixture item</title></entry></feed>".toByteArray(Charsets.UTF_16))))
        assertEquals("Fixture Atom", client.discover(server.url("/atom").toString()).directFeed!!.title)
        assertEquals(1, server.requestCount)
    }
    @Test fun invalidRedirectsAndLoopsHaveFiniteRequestBudget() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://user:pass@example.org/feed"))
        reject { client.discover(server.url("/bad").toString()) }
        assertEquals(1, server.requestCount)
        repeat(6) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/loop")) }
        reject { client.discover(server.url("/loop").toString()) }
        assertEquals(7, server.requestCount)
    }
}
