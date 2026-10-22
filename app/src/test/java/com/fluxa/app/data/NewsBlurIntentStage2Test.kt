package com.fluxa.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.api.*
import com.fluxa.app.data.local.*
import com.fluxa.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NewsBlurIntentStage2Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private class Auth : AuthRepository {
        var session = true
        override fun hasSession() = session
        override suspend fun exchangeCode(code: String) = Unit
        override suspend fun refreshIfNeeded(): String? = null
        override suspend fun logout() { session = false }
    }
    private class Api : NewsBlurApi {
        var serverRead = false
        var serverStar = false
        var responseRead = false
        var responseStar = false
        var rejection: String? = null
        var entered: CompletableDeferred<Unit>? = null
        var release: CompletableDeferred<Unit>? = null
        var writes = 0
        var readEntered: CompletableDeferred<Unit>? = null
        var releaseRead: CompletableDeferred<Unit>? = null
        override suspend fun login(username: String, password: String) = Response.success(NewsBlurResult(1))
        override suspend fun feeds(icons: Boolean, counts: Boolean) = NewsBlurFeeds(mapOf("1" to NewsBlurFeed("Feed")))
        override suspend fun stories(id: String, page: Int, filter: String, hidden: Boolean): NewsBlurStories {
            val read = responseRead; val star = responseStar
            entered?.complete(Unit); release?.await()
            return NewsBlurStories(listOf(NewsBlurStory("1:a", 1, "Owned", "<p>Owned body</p>", "1", if (read) 1 else 0, star)))
        }
        private fun result(change: () -> Unit): NewsBlurResult {
            writes++
            return rejection?.let { NewsBlurResult(-1, it) } ?: NewsBlurResult(1).also { change() }
        }
        override suspend fun addFeed(url: String) = NewsBlurResult(1)
        override suspend fun markRead(hashes: List<String>): NewsBlurResult {
            readEntered?.complete(Unit); releaseRead?.await()
            return result { serverRead = true }
        }
        override suspend fun markUnread(hash: String) = result { serverRead = false }
        override suspend fun star(hash: String) = result { serverStar = true }
        override suspend fun unstar(hash: String) = result { serverStar = false }
    }

    @Test fun acknowledgedIntentSurvivesDiskReopenAndStaleFetchUntilMatchingConfirmation() = runBlocking {
        val name = "owned-intents-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, FluxaDatabase::class.java, name).allowMainThreadQueries().build()
        val api = Api(); val auth = Auth()
        var db = open()
        try {
            var repo = NewsBlurArticleRepository(api, auth, db)
            repo.refresh(); repo.markRead("1:a"); repo.toggleStar("1:a")
            assertEquals(0, repo.observePendingCount().first())
            assertEquals(2, db.pendingActionDao().getAllOrdered().count { it.actionType.startsWith("Ack") })
            db.close(); db = open(); repo = NewsBlurArticleRepository(api, auth, db)
            repo.refresh() // server response deliberately stale after successful acknowledgement
            assertTrue(db.articleDao().getById("1:a")!!.isRead)
            assertTrue(db.articleDao().getById("1:a")!!.isStarred)
            api.responseRead = true; api.responseStar = true
            repo.refresh()
            assertTrue(db.pendingActionDao().getAllOrdered().none { it.actionType.startsWith("Ack") })
            api.responseRead = false; api.responseStar = false
            repo.refresh() // once clean, subsequent server-only state is authoritative
            assertFalse(db.articleDao().getById("1:a")!!.isRead)
            assertFalse(db.articleDao().getById("1:a")!!.isStarred)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test(timeout = 10_000) fun inFlightFetchCannotConfirmOrOverwriteNewerReadStarAndUnread() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FluxaDatabase::class.java).allowMainThreadQueries().build()
        try {
            val api = Api(); val repo = NewsBlurArticleRepository(api, Auth(), db)
            repo.refresh()
            api.entered = CompletableDeferred(); api.release = CompletableDeferred()
            val fetch = async { repo.refresh() }
            api.entered!!.await()
            repo.markRead("1:a")
            val star = async { repo.toggleStar("1:a") }
            withTimeout(3_000) { while (db.pendingActionDao().getAllOrdered().none { it.actionType == "ToggleStar" }) yield() }
            repo.setReadState("1:a", false)
            assertEquals(1, db.pendingActionDao().getAllOrdered().count { it.actionType in setOf("MarkRead", "SetRead") })
            api.release!!.complete(Unit)
            fetch.await(); star.await()
            assertFalse(db.articleDao().getById("1:a")!!.isRead)
            assertTrue(db.articleDao().getById("1:a")!!.isStarred)
            assertFalse(api.serverRead); assertTrue(api.serverStar)
            assertEquals(2, db.pendingActionDao().getAllOrdered().count { it.actionType.startsWith("Ack") })
        } finally { db.close() }
    }

    @Test(timeout = 10_000) fun oldAcknowledgementCannotRemoveCompensatingUnreadAndLaterStarInvalidatesUndo() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FluxaDatabase::class.java).allowMainThreadQueries().build()
        try {
            val api = Api(); val repo = NewsBlurArticleRepository(api, Auth(), db)
            repo.refresh()
            val receipt = repo.bulkRead(listOf("1:a"))
            api.readEntered = CompletableDeferred(); api.releaseRead = CompletableDeferred()
            val replay = async { repo.syncPendingActions() }
            api.readEntered!!.await()
            repo.undoRead(receipt)
            val newer = db.pendingActionDao().getAllOrdered().single { it.actionType == "SetRead" }
            api.releaseRead!!.complete(Unit); replay.await()
            assertEquals(newer, db.pendingActionDao().getAllOrdered().single { it.actionType == "SetRead" })
            assertFalse(db.articleDao().getById("1:a")!!.isRead)
            repo.syncPendingActions(); assertFalse(api.serverRead)
            val second = repo.bulkRead(listOf("1:a"))
            repo.toggleStar("1:a")
            assertTrue(repo.undoRead(second).changes.isEmpty())
            assertTrue(db.articleDao().getById("1:a")!!.isRead)
        } finally { db.close() }
    }

    @Test fun rejectedExpiredUnreadRetainsMessageWaitsForManualRetryAndLogoutNeverWrites() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FluxaDatabase::class.java).allowMainThreadQueries().build()
        try {
            val api = Api(); val auth = Auth(); val repo = NewsBlurArticleRepository(api, auth, db)
            repo.refresh()
            val receipt = repo.bulkRead(listOf("1:a"))
            repo.syncPendingActions()
            assertTrue(api.serverRead)
            repo.undoRead(receipt)
            api.rejection = "Story too old (free account history window)"
            assertTrue(runCatching { repo.syncPendingActions() }.exceptionOrNull() is NewsBlurApiException)
            assertEquals(listOf(api.rejection), repo.observeSyncErrors().first())
            val writes = api.writes
            repo.refreshInBackground()
            assertEquals(writes, api.writes)
            assertFalse(db.articleDao().getById("1:a")!!.isRead)
            auth.logout()
            assertTrue(runCatching { repo.syncPendingActions() }.isFailure)
            assertEquals(writes, api.writes)
            auth.session = true; api.rejection = null
            repo.syncPendingActions()
            assertFalse(api.serverRead)
            assertTrue(repo.observeSyncErrors().first().isEmpty())
            assertTrue(repo.undoRead(receipt).changes.isEmpty())
        } finally { db.close() }
    }
}
