package com.fluxa.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.api.InoreaderApi
import com.fluxa.app.data.api.model.*
import com.fluxa.app.data.local.*
import com.fluxa.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import okhttp3.ResponseBody.Companion.toResponseBody

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RepositoryTest {
    private lateinit var db: FluxaDatabase
    private lateinit var api: TestApi
    private lateinit var repository: InoreaderArticleRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FluxaDatabase::class.java)
            .allowMainThreadQueries().build()
        api = TestApi()
        repository = InoreaderArticleRepository(api, TestAuth(), db)
    }
    @After fun tearDown() { db.close() }

    @Test fun refreshRetainsOlderCachedArticles() = runBlocking {
        db.articleDao().upsertAll(listOf(cached("old")))
        api.items = listOf(remote("new"))
        repository.refresh()
        assertEquals(setOf("old", "new"), repository.getPagedArticles().first().map { it.id }.toSet())
    }

    @Test fun offlineReadAndStarSurviveRepositoryRestartAndReplayInOrder() = runBlocking {
        db.articleDao().upsertAll(listOf(cached("one")))
        api.offline = true
        repository.markRead("one")
        repository.toggleStar("one")
        repository.toggleStar("one")
        assertTrue(db.articleDao().getById("one")!!.isRead)
        assertFalse(db.articleDao().getById("one")!!.isStarred)
        assertEquals(3, db.pendingActionDao().getAllOrdered().size)
        val restarted = InoreaderArticleRepository(api, TestAuth(), db)
        api.offline = false
        restarted.syncPendingActions()
        assertEquals(listOf("read", "star", "unstar"), api.edits)
        assertTrue(db.pendingActionDao().getAllOrdered().isEmpty())
    }

    @Test fun failedReplayKeepsQueueAndLocalState() = runBlocking {
        db.articleDao().upsertAll(listOf(cached("one")))
        api.offline = true
        repository.toggleStar("one")
        try { repository.syncPendingActions(); fail("Expected offline failure") } catch (_: IOException) { }
        assertEquals(1, db.pendingActionDao().getAllOrdered().size)
        assertTrue(db.articleDao().getById("one")!!.isStarred)
    }

    @Test fun writeRateLimitPreservesPendingFlagsWhileReadsStillRefresh() = runBlocking {
        db.articleDao().upsertAll(listOf(cached("one")))
        api.writeLimited = true
        repository.toggleStar("one")
        api.items = listOf(remote("one"), remote("new"))
        repository.refresh()
        assertTrue(db.articleDao().getById("one")!!.isStarred)
        assertNotNull(db.articleDao().getById("new"))
        assertEquals(1, db.pendingActionDao().getAllOrdered().size)
        api.writeLimited = false
        repository.syncPendingActions()
        assertTrue(db.pendingActionDao().getAllOrdered().isEmpty())
    }

    @Test(timeout = 10000) fun refreshCannotOverwriteActionMadeWhileRequestIsInFlight() = runBlocking {
        db.articleDao().upsertAll(listOf(cached("one")))
        api.items = listOf(remote("one"))
        api.requestStarted = CompletableDeferred()
        api.releaseRequest = CompletableDeferred()
        val refresh = async { repository.refresh() }
        api.requestStarted!!.await()
        val star = async { repository.toggleStar("one") }
        while (db.pendingActionDao().getAllOrdered().isEmpty()) yield()
        api.releaseRequest!!.complete(Unit)
        refresh.await()
        star.await()
        assertTrue(db.articleDao().getById("one")!!.isStarred)
        assertEquals(listOf("star"), api.edits)
    }

    @Test fun paginationCursorSurvivesRestartAndIsScopedByStream() = runBlocking {
        api.cursor = "page2"
        repository.refresh()
        val restarted = InoreaderArticleRepository(api, TestAuth(), db)
        api.cursor = null
        restarted.loadMore()
        assertEquals("page2", api.requestedCursor)
        restarted.selectSource("feed/https://example.org/rss")
        assertEquals("feed/https://example.org/rss", api.requestedStream)
        assertNull(api.requestedCursor)
    }

    @Test fun serverChangesAreAcceptedWhenNoLocalActionIsPending() = runBlocking {
        db.articleDao().upsertAll(listOf(cached("one").copy(isStarred = true)))
        api.items = listOf(remote("one"))
        repository.refresh()
        assertFalse(db.articleDao().getById("one")!!.isStarred)
    }

    @Test fun serverFlagsRecognizeConcreteUserIdAndAliasTags() = runBlocking {
        api.items = listOf(
            remote("concrete").copy(categories = listOf("user/123/state/com.google/read",
                "user/123/state/com.google/starred")),
            remote("alias").copy(categories = listOf(InoreaderArticleRepository.READ_TAG,
                InoreaderArticleRepository.STARRED_TAG)),
            remote("label").copy(categories = listOf("user/123/label/state/com.google/starred"))
        )
        repository.refresh()
        assertTrue(db.articleDao().getById("concrete")!!.isRead)
        assertTrue(db.articleDao().getById("concrete")!!.isStarred)
        assertTrue(db.articleDao().getById("alias")!!.isRead)
        assertTrue(db.articleDao().getById("alias")!!.isStarred)
        assertFalse(db.articleDao().getById("label")!!.isStarred)
    }

    @Test fun failedSubscriptionRefreshRetainsCache() = runBlocking {
        db.subscriptionDao().upsertAll(listOf(SubscriptionEntity("feed/one", "One", "https://example.org")))
        api.offline = true
        try { repository.refreshSubscriptions(); fail("Expected offline failure") } catch (_: IOException) { }
        assertEquals("One", repository.observeSubscriptions().first().single().title)
    }

    @Test fun invalidFeedUrlDoesNotReachNetwork() = runBlocking {
        try { repository.addSubscription("file:///private/data"); fail("Expected invalid URL") }
        catch (_: IllegalArgumentException) { }
        assertNull(api.addedFeed)
    }
}

internal fun cached(id: String) = ArticleEntity(id, "Cached $id", "Example", 1000, false, false, "<p>offline</p>")
internal fun remote(id: String) = StreamItem(id, "Remote $id", 2000, emptyList(),
    Origin("Example", "feed/https://example.org/rss"), Summary("<p>online</p>"))

internal class TestAuth : AuthRepository {
    override suspend fun exchangeCode(code: String) = Unit
    override suspend fun refreshIfNeeded() = "test-token"
    override fun hasSession() = true
    override suspend fun logout() = Unit
}

internal class TestApi : InoreaderApi {
    var offline = false
    var writeLimited = false
    var items = emptyList<StreamItem>()
    var cursor: String? = null
    var requestedCursor: String? = null
    var requestedStream: String? = null
    var addedFeed: String? = null
    var requestStarted: CompletableDeferred<Unit>? = null
    var releaseRequest: CompletableDeferred<Unit>? = null
    val edits = mutableListOf<String>()

    override suspend fun exchangeToken(grantType: String, code: String?, refreshToken: String?,
        clientId: String, clientSecret: String, redirectUri: String) =
        TokenResponse("test", "Bearer", 3600, "refresh")

    override suspend fun getReadingStream(streamId: String, count: Int, continuation: String?): StreamContentsResponse {
        if (offline) throw IOException("Offline")
        requestedCursor = continuation
        requestedStream = streamId
        requestStarted?.complete(Unit)
        releaseRequest?.await()
        return StreamContentsResponse(streamId, cursor, items)
    }
    override suspend fun editTag(itemId: String, addTag: String?, removeTag: String?) {
        if (offline) throw IOException("Offline")
        if (writeLimited) throw retrofit2.HttpException(retrofit2.Response.error<Unit>(429,
            "Limited".toResponseBody()))
        edits += when {
            addTag == InoreaderArticleRepository.READ_TAG -> "read"
            addTag != null -> "star"
            else -> "unstar"
        }
    }
    override suspend fun getSubscriptions(): SubscriptionResponse {
        if (offline) throw IOException("Offline")
        return SubscriptionResponse(listOf(SubscriptionDto("feed/one", "One", "https://example.org")))
    }
    override suspend fun addSubscription(feedId: String): AddSubscriptionResponse {
        addedFeed = feedId
        return AddSubscriptionResponse(1)
    }
}
