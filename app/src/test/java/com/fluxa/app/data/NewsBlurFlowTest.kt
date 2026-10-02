package com.fluxa.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.api.*
import com.fluxa.app.data.local.*
import com.fluxa.app.data.repository.NewsBlurArticleRepository
import com.fluxa.app.di.NetworkModule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NewsBlurFlowTest {
    private lateinit var db: FluxaDatabase
    private lateinit var server: MockWebServer
    private lateinit var repository: NewsBlurArticleRepository
    private var read = false
    private var starred = false
    private var limited = false
    private var businessRefusal = false
    private val calls = mutableListOf<String>()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FluxaDatabase::class.java)
            .allowMainThreadQueries().build()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                calls += path
                if (businessRefusal && path.contains("mark_story"))
                    return MockResponse().setBody("""{"code":-1,"message":"服务端拒绝操作，登录后可重试"}""")
                if (limited && path.contains("mark_story"))
                    return MockResponse().setResponseCode(429).setBody("{}")
                val body = when {
                    path == "/reader/feeds" -> """{"feeds":{"12":{"feed_title":"Daily","feed_address":"https://example.org/rss","active":true}}}"""
                    path == "/reader/add_url" -> """{"code":1}"""
                    path == "/reader/mark_story_hashes_as_read" -> { read = true; """{"code":1}""" }
                    path == "/reader/mark_story_hash_as_starred" -> { starred = true; """{"code":1}""" }
                    path == "/reader/mark_story_hash_as_unstarred" -> { starred = false; """{"code":1}""" }
                    path == "/reader/feed/12" && request.requestUrl!!.queryParameter("page") == "2" ->
                        """{"stories":[]}"""
                    path == "/reader/feed/12" -> """{"stories":[{"story_hash":"12:abc123","story_feed_id":12,
                        "story_title":"Cached story","story_content":"<p>Offline body</p>",
                        "story_timestamp":"1700000000","read_status":${if (read) 1 else 0},"starred":$starred}]}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(NetworkModule.provideMoshi()))
            .build().create(NewsBlurApi::class.java)
        repository = NewsBlurArticleRepository(api, TestAuth(), db)
    }
    @After fun teardown() { server.shutdown(); db.close() }

    @Test fun backgroundRefreshFetchesAllFeedsAndKeepsForegroundSelection() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                calls += path
                if (path == "/reader/feeds") return MockResponse().setBody(
                    """{"feeds":{"12":{"feed_title":"One","active":true},"13":{"feed_title":"Two","active":true}}}""")
                return MockResponse().setBody("""{"stories":[{"story_hash":"${path.takeLast(2)}:fixture","story_feed_id":${path.takeLast(2)},"story_title":"Fixture","story_timestamp":"1700000000"}]}""")
            }
        }
        repository.selectSource("12")
        calls.clear()
        repository.refreshInBackground()
        assertTrue(calls.contains("/reader/feed/12"))
        assertTrue(calls.contains("/reader/feed/13"))
        calls.clear()
        repository.loadMore()
        assertEquals(listOf("/reader/feed/12"), calls)
    }

    @Test fun subscriptionRefreshReadStarOfflineReplayAndPaginationWorkTogether() = runBlocking {
        repository.addSubscription("https://example.org/rss")
        repository.refresh()
        assertEquals("Daily", repository.observeSubscriptions().first().single().title)
        val article = repository.getPagedArticles().first().single()
        assertEquals("<p>Offline body</p>", article.contentHtml)
        assertEquals("12", article.source)
        repository.markRead(article.id)
        assertEquals(1, db.pendingActionDao().getAllOrdered().size)
        limited = true
        repository.toggleStar(article.id)
        assertTrue(db.articleDao().getById(article.id)!!.isStarred)
        assertEquals(2, db.pendingActionDao().getAllOrdered().size)
        repository.refresh() // 429 writes do not erase flags or prevent article reads.
        assertTrue(db.articleDao().getById(article.id)!!.isRead)
        assertTrue(db.articleDao().getById(article.id)!!.isStarred)
        limited = false
        repository.syncPendingActions()
        assertTrue(read)
        assertTrue(starred)
        assertTrue(db.pendingActionDao().getAllOrdered().isEmpty())
        repository.toggleStar(article.id)
        assertFalse(starred)
        repository.loadMore()
        assertNull(db.syncStateDao().getBySource("12")!!.cursor)
        assertEquals(1, repository.getPagedArticles().first().size)
        assertFalse(calls.any { it.contains("river_stories") })
    }

    @Test fun loginContractUsesFormAndReceivesCookieWithoutApiKey() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse()
                .addHeader("Set-Cookie", "newsblur_sessionid=fixture-session; Path=/; Secure; HttpOnly")
                .setBody("""{"code":1,"errors":null}""")
        }
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(NetworkModule.provideMoshi()))
            .build().create(NewsBlurApi::class.java)
        val response = api.login("fixture-reader", "fixture-password")
        assertEquals(1, response.body()!!.code)
        assertTrue(response.headers()["Set-Cookie"]!!.contains("newsblur_sessionid"))
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("/api/login", request.path)
        assertEquals("username=fixture-reader&password=fixture-password", request.body.readUtf8())
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun emptyAccountFeedsArrayIsAccepted() {
        val result = NetworkModule.provideMoshi().adapter(NewsBlurFeeds::class.java)
            .fromJson("""{"feeds":[],"folders":[]}""")!!
        assertTrue(result.feeds.isEmpty())
    }

    @Test fun nonPremiumAccountUsesAllowedFeedPaginationWithoutCodeOrServerSearch() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                calls += url.encodedPath
                check(url.queryParameter("query") == null)
                check(url.queryParameter("search") == null)
                if (url.encodedPath == "/reader/feeds") return MockResponse().setBody(
                    """{"is_premium":false,"is_trial":false,"feeds":{"12":{"feed_title":"Free account","feed_address":"https://example.org/rss","active":true}}}""")
                if (url.encodedPath != "/reader/feed/12") return MockResponse().setResponseCode(403)
                val page = url.queryParameter("page")!!.toInt()
                val stories = if (page > 2) "" else (1..6).joinToString(",") { item ->
                    val id = (page - 1) * 6 + item
                    """{"story_hash":"12:free$id","story_feed_id":12,"story_title":"Free story $id","story_content":"<p>Cached search body $id</p>","story_timestamp":1700000000,"read_status":0,"starred":false}"""
                }
                // The successful free single-feed response has no code field.
                return MockResponse().setBody("""{"stories":[$stories]}""")
            }
        }
        repository.refresh()
        assertEquals(6, repository.getPagedArticles().first().size)
        repository.loadMore()
        assertEquals(12, repository.getPagedArticles().first().size)
        repository.loadMore()
        assertEquals(12, repository.getPagedArticles().first().size)
        assertNull(db.syncStateDao().getBySource("12")!!.cursor)
        assertTrue(calls.all { it == "/reader/feeds" || it == "/reader/feed/12" })
    }

    @Test fun http200BusinessRejectionPreservesStateAndQueueThenCanRecover() = runBlocking {
        repository.refresh()
        val article = repository.getPagedArticles().first().single()
        businessRefusal = true
        val failure = runCatching { repository.toggleStar(article.id) }.exceptionOrNull()
        assertTrue(failure is NewsBlurApiException)
        assertEquals("服务端拒绝操作，登录后可重试", failure!!.message)
        assertTrue(db.articleDao().getById(article.id)!!.isStarred)
        assertEquals(1, db.pendingActionDao().getAllOrdered().size)
        businessRefusal = false
        repository.syncPendingActions()
        assertTrue(starred)
        assertTrue(db.pendingActionDao().getAllOrdered().isEmpty())
    }
}
