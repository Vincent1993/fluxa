package com.fluxa.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.directrss.*
import com.fluxa.app.data.repository.ArticleRepository
import com.fluxa.app.data.repository.CompositeArticleRepository
import com.fluxa.app.domain.model.Article
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DirectRssTest {
    private lateinit var db: DirectRssDatabase
    private lateinit var server: MockWebServer
    private lateinit var repo: DirectRssRepository
    private val client = DirectRssClient()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DirectRssDatabase::class.java).addMigrations(DirectRssDatabase.MIGRATION_1_2, DirectRssDatabase.MIGRATION_2_3, DirectRssDatabase.MIGRATION_3_4, DirectRssDatabase.MIGRATION_4_5, DirectRssDatabase.MIGRATION_5_6).allowMainThreadQueries().build()
        server = MockWebServer(); server.start()
        repo = DirectRssRepository(db)
    }
    @After fun close() { db.close(); server.shutdown() }
    private fun rss(body: String = "<h2>第一节</h2><p>真实形状的测试正文，不是 App 新闻数据</p>", extras: String = "", title: String = "早报｜测试") = """<rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><title>测试来源</title><item><guid>stable-guid</guid><title>$title</title><link>/article?utm_source=rss</link><pubDate>Thu, 08 Oct 2026 00:14:13 +0000</pubDate><category>早报</category><description>NOT FULL BODY</description><content:encoded><![CDATA[$body]]></content:encoded></item>$extras</channel></rss>"""
    private fun page(xml: String) = MockResponse().setHeader("Content-Type", "application/atom+xml").setBody(xml)
    private fun parse(xml: String) = client.parse(xml.toByteArray(), "https://www.ifanr.com/feed")
    private fun fails(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: Exception) { /* Expected rejection; AssertionError is not swallowed. */ } }

    @Test fun actualRssShapeContentWinsRegardlessOfMimeAndCredentialsAreAbsent() {
        server.enqueue(page(rss()))
        val feed = client.fetch(server.url("/feed").toString())
        val item = feed.items.single()
        assertEquals("测试来源", feed.title)
        assertTrue(item.body.contains("第一节")); assertFalse(item.body.contains("NOT FULL"))
        assertEquals(1791418453L, item.published)
        assertEquals(server.url("/article?utm_source=rss").toString(), item.url)
        assertTrue(isDaily(item))
        val request = server.takeRequest()
        assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
    }
    @Test fun redirectUsesFinalFeedBaseWithoutAcceptingCookiesAndCanonicalLinksDeduplicate() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/nested/feed.xml").setHeader("Set-Cookie", "session=not-an-account"))
        server.enqueue(page("""<rss><channel><item><title>A</title><link>article?utm_source=one</link></item><item><title>A</title><link>article?utm_source=two</link></item></channel></rss>"""))
        val item = client.fetch(server.url("/old").toString()).items.single()
        assertEquals(server.url("/nested/article?utm_source=one").toString(), item.url)
        assertEquals(server.url("/nested/article").toString(), item.key)
        server.takeRequest(); assertNull(server.takeRequest().getHeader("Cookie"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://user:secret@example.org/feed"))
        fails { client.fetch(server.url("/bad").toString()) }
    }
    @Test fun atomXhtmlContentSummaryAlternateCategoryAndRelativeUrls() {
        val feed = parse("""<feed xmlns="http://www.w3.org/2005/Atom" xml:base="https://example.org/base/"><title>Atom</title>
          <entry><id>urn:one</id><title>早报: Atom</title><link rel="self" href="self"/><link rel="alternate" href="article"/>
          <updated>2026-10-08T00:14:13Z</updated><category term="早报"/>
          <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><h2>Heading</h2><p>Hello <a href="other">link</a></p><img src="image.png"/></div></content></entry>
          <entry><id>urn:two</id><title>Text</title><summary type="html">&lt;p&gt;Summary&lt;/p&gt;</summary></entry></feed>""")
        assertEquals(2, feed.items.size)
        assertEquals("https://example.org/base/article", feed.items[0].url)
        assertTrue(feed.items[0].body.contains("https://example.org/base/image.png"))
        assertTrue(feed.items[0].body.contains("<h2>Heading</h2>"))
        assertEquals("<p>Summary</p>", feed.items[1].body)
        assertEquals(0L, feed.items[1].published)
        assertEquals("", feed.items[1].url)
    }
    @Test fun dailyCategoryTakesPriorityAndFallbackHasExplicitBoundaries() {
        val item = parse(rss()).items.single()
        listOf("早报｜消息", "早报|消息", "早报丨消息", "早报:消息", "早报：消息", "早报/消息", "早报 消息", "早报　消息").forEach {
            assertTrue(isDaily(item.copy(title = it, categories = emptyList())))
            assertFalse(isDaily(item.copy(title = it, categories = listOf("新闻"))))
        }
        assertFalse(isDaily(item.copy(title = "早报错配", categories = emptyList())))
        assertFalse(isDaily(item.copy(categories = listOf("", "早报精选"))))
        assertTrue(isIfanrFeed(IFANR_FEED)); assertFalse(isIfanrFeed("https://evil.example/feed"))
        assertFalse(isIfanrFeed("https://www.ifanr.com/category/ifanrnews/feed"))
    }
    @Test fun duplicateItemsStableGuidMissingFieldsAndInvalidUrls() {
        val duplicated = rss(extras = "<item><guid>stable-guid</guid><title>duplicate</title></item><item><link>/second</link></item>")
        val items = parse(duplicated).items
        assertEquals(2, items.size); assertEquals("stable-guid", items[0].key)
        assertEquals("（无标题）", items[1].title)
        assertEquals("https://www.ifanr.com/second", items[1].key)
        listOf("file:///tmp/file", "https://user:password@example.org/feed", "https://@example.org/feed", "https://example.org/feed#x", "javascript:alert(1)").forEach { assertNull(safeUrl(it)) }
    }
    @Test fun dtdMalformedEmptyNonXmlAndBothResponseLimitsReject() {
        listOf("<!DOCTYPE rss SYSTEM 'https://example.org/evil'><rss/>",
            "<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss>&x;</rss>",
            "<rss><channel><item></channel></rss>", "<html>oops</html>", "<rss><channel/></rss>", "not xml").forEach { xml -> fails { parse(xml) } }
        fails { client.parse("<!DOCTYPE rss><rss/>".toByteArray(Charsets.UTF_16), IFANR_FEED) }
        fails { client.parse(ByteArray(DirectRssClient.MAX_BYTES + 1), IFANR_FEED) }
        server.enqueue(MockResponse().setChunkedBody("x".repeat(DirectRssClient.MAX_BYTES + 1), 8192))
        fails { client.fetch(server.url("/feed").toString()) }
        server.enqueue(MockResponse().setBody("x".repeat(DirectRssClient.MAX_BYTES + 1)))
        fails { client.fetch(server.url("/feed").toString()) }
        val many = "<rss><channel>" + "<item><title>x</title></item>".repeat(501) + "</channel></rss>"
        fails { parse(many) }
    }
    @Test fun sanitizerPreservesStructureButNotActiveMarkupStylesOrSmileyWidth() {
        val html = DirectHtml.clean("""<script>alert(1)</script><style>p{display:none}</style><h2 style="font-size:90px" onclick="bad()">Title</h2><p>Text<img class="wp-smiley" src="/smile" alt="🙂"/><img src="/large" srcset="/small 768w, /large 2048w" width="999" onerror="bad()"/></p><table><tr><td>Cell</td></tr></table><a href="javascript:bad()">bad</a>""", "https://example.org/a")
        assertFalse(html.contains("script")); assertFalse(html.contains("style=")); assertFalse(html.contains("onerror")); assertFalse(html.contains("javascript:"))
        assertTrue(html.contains("🙂")); assertFalse(html.contains("/smile")); assertTrue(html.contains("https://example.org/small")); assertTrue(html.contains("<table>"))
    }
    @Test fun mergeIsIdempotentPreservesFlagsAndFailureKeepsBodyAndSuccessTime() = runBlocking {
        val url = server.url("/feed").toString()
        server.enqueue(page(rss())); repo.addSubscription(url)
        val first = db.dao().articles().single()
        repo.markRead(first.id); repo.toggleStar(first.id)
        server.enqueue(page(rss(title = "更新标题"))); repo.addSubscription(url)
        assertEquals(1, db.dao().subscriptions().size); assertEquals(1, db.dao().articles().size)
        val updated = db.dao().article(first.id)!!
        assertEquals("更新标题", updated.title); assertTrue(updated.isRead); assertTrue(updated.isStarred)
        val success = db.dao().subscriptions().single().lastSuccess
        server.enqueue(MockResponse().setResponseCode(503))
        try { repo.refresh(); fail() } catch (_: IllegalStateException) { }
        assertEquals(updated, db.dao().article(first.id)); assertEquals(success, db.dao().subscriptions().single().lastSuccess)
        assertTrue(db.dao().subscriptions().single().status.contains("失败"))
        server.enqueue(page("<rss>broken"))
        try { repo.refresh(); fail() } catch (_: IllegalStateException) { }
        assertEquals(updated, db.dao().article(first.id))
        try { repo.addSubscription(url, true); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun concurrentReadAndStarDuringNetworkAreMergedFromLatestTransaction() = runBlocking {
        val url = server.url("/feed").toString()
        server.enqueue(page(rss())); repo.addSubscription(url)
        val id = db.dao().articles().single().id
        val reached = CountDownLatch(1); val release = CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                reached.countDown(); check(release.await(10, TimeUnit.SECONDS)); return page(rss(title = "changed"))
            }
        }
        val job = async(Dispatchers.IO) { repo.refresh() }
        try { assertTrue(reached.await(10, TimeUnit.SECONDS)); repo.markRead(id); repo.toggleStar(id) }
        finally { release.countDown() }
        job.await()
        val row = db.dao().article(id)!!
        assertTrue(row.isRead); assertTrue(row.isStarred); assertEquals("changed", row.title)
    }
    @Test fun imageFailureKeepsHttpSuccessfulDataIsReusedAndRequestIsAnonymous() = runBlocking {
        val url = server.url("/feed").toString()
        val body = "<h2>Title</h2><img src=\"${server.url("/ok.png")}\"><img src=\"${server.url("/fail.png")}\">"
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
        server.enqueue(page(rss(body))); server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(png))); server.enqueue(MockResponse().setResponseCode(500))
        repo.addSubscription(url)
        val first = db.dao().articles().single()
        assertEquals(1, first.cachedImages); assertEquals(2, first.totalImages)
        assertTrue(first.body.contains("data:image/png;base64,")); assertTrue(first.body.contains("/fail.png"))
        server.takeRequest(); val image = server.takeRequest(); server.takeRequest()
        assertNull(image.getHeader("Cookie")); assertNull(image.getHeader("Authorization"))
        repo.markRead(first.id); repo.toggleStar(first.id)
        server.enqueue(page(rss(body))); server.enqueue(MockResponse().setResponseCode(404)); repo.refresh()
        assertEquals(5, server.requestCount) // one cached image was not downloaded again
        val row = db.dao().articles().single()
        assertEquals(first.body, row.body); assertTrue(row.isRead && row.isStarred)
        assertTrue(db.dao().subscriptions().single().status.startsWith("尝试 "))
        assertTrue(db.dao().subscriptions().single().status.endsWith(" · 已刷新 1 篇"))
        assertEquals(first.cachedImages, row.cachedImages); assertEquals(first.totalImages, row.totalImages)
    }
    @Test fun newerWindowNeverDeletesHistoricalCacheAndLocalFeedsFailIndependently() = runBlocking {
        server.enqueue(page(rss())); repo.addSubscription(server.url("/one").toString())
        server.enqueue(page(rss())); repo.addSubscription(server.url("/two").toString())
        val feeds = db.dao().subscriptions()
        val old = db.dao().articles()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(page(rss().replace("stable-guid", "new-window-guid")))
        try { repo.refresh(); fail() } catch (_: IllegalStateException) { }
        val after = db.dao().articles()
        assertEquals(3, after.size)
        assertTrue(old.all { original -> after.any { it.id == original.id && it.body == original.body } })
        assertTrue(db.dao().subscription(feeds[0].id)!!.status.contains("失败"))
        assertTrue(db.dao().subscription(feeds[1].id)!!.status.contains(" · 已刷新"))
    }
    @Test fun fileDatabaseReopensWithoutLosingSubscriptionBodyOrFlags() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "direct-rss-unit-${java.util.UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, DirectRssDatabase::class.java, name).addMigrations(DirectRssDatabase.MIGRATION_1_2, DirectRssDatabase.MIGRATION_2_3, DirectRssDatabase.MIGRATION_3_4, DirectRssDatabase.MIGRATION_4_5, DirectRssDatabase.MIGRATION_5_6).allowMainThreadQueries().build()
        try {
            val disk = open()
            val repository = DirectRssRepository(disk)
            server.enqueue(page(rss())); repository.addSubscription(server.url("/feed").toString())
            val row = disk.dao().articles().single()
            repository.markRead(row.id); repository.toggleStar(row.id); disk.close()
            val reopened = open()
            try { assertEquals(1, reopened.dao().subscriptions().size); assertEquals(row.copy(isRead = true, isStarred = true), reopened.dao().article(row.id)) }
            finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun compositeRoutesLocalActionsAndRefreshWithoutSessionButRetainsRemoteContract() = runBlocking {
        val remote = Remote()
        val composite = CompositeArticleRepository.forTest(remote, repo) { false }
        composite.refresh()
        assertEquals(0, remote.refreshes)
        val url = server.url("/feed").toString()
        server.enqueue(page(rss())); composite.addLocalSubscription(url)
        val row = db.dao().articles().single()
        composite.markRead(row.id); composite.toggleStar(row.id)
        assertEquals(0, remote.actions); assertEquals(7, composite.observePendingCount().first())
        server.enqueue(page(rss())); composite.selectSource(row.source)
        assertEquals(0, remote.refreshes)
        server.enqueue(page(rss())); composite.selectSource(null)
        assertEquals(0, remote.refreshes)
        composite.addSubscription("https://example.org/feed"); assertEquals(1, remote.adds)
        assertEquals(row.id, composite.getPagedArticles().first().single().id)
        try { composite.refreshInBackground(); fail() } catch (_: IOException) { }
        assertEquals(1, remote.refreshes)
    }
    @Test fun dailyAndAllSiteModesAreSeparateAndCategoryAbsenceAloneAllowsFallback() = runBlocking {
        val xml = rss(extras = """<item><guid>other</guid><title>早报｜not daily</title><category>新闻</category></item>
            <item><guid>fallback</guid><title>早报丨fallback</title></item>""")
        val local = DirectRssRepository(db, DirectRssClient(readFeed = { xml.toByteArray() }))
        local.addSubscription(IFANR_FEED, true)
        local.addSubscription("https://ifanr.com/feed/", true)
        local.addSubscription(IFANR_FEED, false)
        val feeds = db.dao().subscriptions()
        assertEquals(2, feeds.size)
        val daily = feeds.single { it.dailyOnly }
        assertEquals(2, db.dao().articles().count { it.source == daily.id })
        assertEquals(3, db.dao().articles().count { it.source != daily.id })
        assertTrue(db.dao().articles().all { it.id.startsWith(LOCAL_PREFIX) })
    }
    @Test fun allSourceRefreshKeepsPartialSuccessOnEitherSideAndSelectedRemoteNeverFetchesLocal() = runBlocking {
        val remote = Remote()
        val composite = CompositeArticleRepository.forTest(remote, repo) { true }
        server.enqueue(page(rss())); repo.addSubscription(server.url("/feed").toString())
        server.enqueue(page(rss(title = "local success")))
        try { composite.refresh(); fail() } catch (_: IllegalStateException) { }
        assertEquals("local success", db.dao().articles().single().title)
        assertEquals(1, remote.refreshes)
        remote.failRefresh = false
        server.enqueue(MockResponse().setResponseCode(500))
        try { composite.refresh(); fail() } catch (_: IllegalStateException) { }
        assertEquals(2, remote.refreshes)
        assertEquals("local success", db.dao().articles().single().title)
        val count = server.requestCount
        composite.selectSource("123")
        assertEquals(3, remote.refreshes); assertEquals(count, server.requestCount)
    }
    @Test fun imageOversizeAndRequestBudgetLeaveRemoteSourceWithoutClaimingCache() {
        val url = server.url("/huge.png").toString()
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("x".repeat(1024 * 1024 + 1)))
        val cache = DirectImageCache()
        val html = "<img src=\"$url\">"
        val failed = cache.cache(html, "", DirectImageCache.Budget())
        assertEquals(html, failed.html); assertEquals(0, failed.cached); assertEquals(1, failed.total)
        val count = server.requestCount
        assertEquals(0, cache.cache(html, "", DirectImageCache.Budget(requests = 80)).cached)
        assertEquals(0, cache.cache(html, "", DirectImageCache.Budget(bytes = 24 * 1024 * 1024)).cached)
        assertEquals(count, server.requestCount)
    }
    @Test fun independentRoomWritesDoNotTouchNewsBlurPendingOrOriginalArticles() = runBlocking {
        val news = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), com.fluxa.app.data.local.FluxaDatabase::class.java).allowMainThreadQueries().build()
        try {
            news.pendingActionDao().insert(com.fluxa.app.data.local.PendingActionEntity(articleId = "remote-original", actionType = "ToggleStar", payload = "true"))
            news.articleDao().upsertAll(listOf(com.fluxa.app.data.local.ArticleEntity(
                "remote-original", "Preserved NewsBlur article", "Account feed", 1L, true, true, "<p>original body</p>", "123")))
            val pending = news.pendingActionDao().getAllOrdered()
            val original = news.articleDao().observeAll().first()
            server.enqueue(page(rss())); repo.addSubscription(server.url("/feed").toString())
            val id = db.dao().articles().single().id
            repo.markRead(id); repo.toggleStar(id)
            assertEquals(pending, news.pendingActionDao().getAllOrdered())
            assertEquals(original, news.articleDao().observeAll().first())
        } finally { news.close() }
    }
    private class Remote : ArticleRepository {
        var failRefresh = true
        var refreshes = 0; var actions = 0; var adds = 0
        override fun getPagedArticles() = flowOf(emptyList<Article>())
        override fun observePendingCount() = flowOf(7)
        override suspend fun refresh() { refreshes++; if (failRefresh) throw IOException("missing session") }
        override suspend fun loadMore() = Unit
        override suspend fun markRead(id: String) { actions++ }
        override suspend fun toggleStar(id: String) { actions++ }
        override suspend fun addSubscription(url: String) { adds++ }
    }
}
