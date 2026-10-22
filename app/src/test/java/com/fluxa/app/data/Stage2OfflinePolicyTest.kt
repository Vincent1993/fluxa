package com.fluxa.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import com.fluxa.app.data.directrss.*
import com.fluxa.app.data.sync.DirectRssSyncScheduler
import com.fluxa.app.data.sync.DirectRssSyncWorker
import com.fluxa.app.data.sync.BackgroundSyncScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class Stage2OfflinePolicyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun byteAccountingAndImageRemovalRetainOriginalAndProse() {
        val html = "<p>汉字</p><img alt=\"图\" src=\"data:image/png;base64,AAAA\" data-original=\"https://example.org/i\">"
        assertEquals(html.toByteArray(Charsets.UTF_8).size.toLong() + 6, payloadBytes(html, "汉字"))
        val stripped = withoutEmbeddedImages(html)
        assertTrue(stripped.contains("<p>汉字</p>"))
        assertTrue(stripped.contains("src=\"https://example.org/i\""))
        assertTrue(stripped.contains("alt=\"图\""))
        assertFalse(stripped.contains("base64"))
    }

    @Test fun disabledImagePolicyUsesZeroRequestAllowanceAndReusesSavedPicture() {
        val cache = DirectImageCache(downloadAllowed = { false })
        val body = "<img src=\"https://example.org/i\">"
        val budget = DirectImageCache.Budget()
        assertEquals(0, cache.cache(body, "", budget).cached)
        assertEquals(0, budget.requests); assertEquals(0, budget.bytes)
        val previous = "<img src=\"data:image/png;base64,AAAA\" data-original=\"https://example.org/i\">"
        assertEquals(1, cache.cache(body, previous, budget).cached)
        assertEquals(0, budget.requests)
    }

    @Test fun workerHasIndependentIdentityConstraintsAndFiniteClassifiedRetries() {
        val work = DirectRssSyncScheduler.request(LocalOfflinePolicy()).workSpec
        assertNotEquals(BackgroundSyncScheduler.WORK_NAME, DirectRssSyncScheduler.WORK_NAME)
        assertEquals(3_600_000L, work.intervalDuration)
        assertEquals(NetworkType.UNMETERED, work.constraints.requiredNetworkType)
        assertTrue(work.constraints.requiresBatteryNotLow()); assertTrue(work.constraints.requiresStorageNotLow())
        assertTrue(DirectRssSyncWorker.retryAllowed(DirectHttpException(429), 1))
        assertTrue(DirectRssSyncWorker.retryAllowed(DirectHttpException(503), 0))
        assertFalse(DirectRssSyncWorker.retryAllowed(DirectHttpException(503), 2))
        assertFalse(DirectRssSyncWorker.retryAllowed(DirectHttpException(404), 0))
        assertFalse(DirectRssSyncWorker.retryAllowed(DirectPayloadException("limit"), 0))
        assertFalse(DirectRssSyncWorker.retryAllowed(IllegalArgumentException("invalid XML"), 0))
    }

    @Test fun localWorkerNeedsNoAuthSkipsPausedSourcesAndSerialStaleChecksDoNotDoubleFetch() = runBlocking {
        context.getSharedPreferences("direct_rss_offline", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = LocalOfflineSettings(context)
        val db = Room.inMemoryDatabaseBuilder(context, DirectRssDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.dao().putSubscription(DirectSubscription("active", "A", "https://example.org/active", false))
            db.dao().putSubscription(DirectSubscription("paused", "P", "https://example.org/paused", false, paused = true))
            db.dao().putSubscription(DirectSubscription("archived", "X", "https://example.org/archived", false, archived = true))
            var calls = 0
            val repo = DirectRssRepository(db, DirectRssClient(readFeed = { url ->
                assertEquals("https://example.org/active", url)
                calls++
                "<rss><channel><title>A</title><item><guid>one</guid><title>Owned</title><description>Owned body</description></item></channel></rss>".toByteArray()
            }), clock = { 4_000_000L }, offline = settings)
            val worker = androidx.work.testing.TestListenableWorkerBuilder<DirectRssSyncWorker>(context)
                .setWorkerFactory(object : androidx.work.WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: androidx.work.WorkerParameters) =
                        DirectRssSyncWorker(appContext, workerParameters, repo, settings)
                }).build()
            assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
            repo.refreshOnResume(); repo.refreshOnResume()
            assertEquals(1, calls)
            assertEquals(1, db.dao().articles().size)
            assertTrue(db.dao().subscription("active")!!.lastSuccess > 0)
            assertEquals(0L, db.dao().subscription("paused")!!.lastSuccess)
        } finally { db.close() }
    }

    @Test fun actualNotesProtectBothPayloadsUntilLastNoteIsDeleted() = runBlocking {
        context.getSharedPreferences("direct_rss_offline", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = LocalOfflineSettings(context)
        settings.update(LocalOfflinePolicy(capacityMiB = 64, retainedReadDays = 7))
        val db = Room.inMemoryDatabaseBuilder(context, DirectRssDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = DirectRssRepository(db, clock = { 100L * 86_400_000 }, offline = settings)
            val dao = db.dao()
            // Protected excess forces both eviction passes, including full-body removal.
            repeat(76) { i -> dao.putArticle(DirectArticle("budget-$i", "$i", "s", "Budget", 0,
                "p".repeat(900_000), "https://example.org/$i", isRead = true, isStarred = true)) }
            val rss = "<p>RSS prose survives</p><img src=\"data:image/png;base64,AAAA\" data-original=\"https://example.org/i\">"
            val full = "<p>Full text anchor</p><img src=\"data:image/png;base64,AAAA\" data-original=\"https://example.org/f\">"
            for (id in listOf("noted", "control")) {
                dao.putArticle(DirectArticle(id, id, "s", id, 0, rss, "https://example.org/$id", isRead = true))
                dao.putFullBody(DirectFullBody(id, full))
                settings.recordRead(id, true, 1)
            }
            val notedBefore = dao.article("noted")!!
            val note = DirectReaderNote("full-note", "noted", "FULL_TEXT", "Full text anchor", stableHash("Full text anchor"), 0, 1, "Owned note")
            repo.saveNote(note)
            repo.saveNote(note.copy(id = "rss-note", mode = "RSS", quote = "RSS prose survives", quoteHash = stableHash("RSS prose survives")))
            val usage = repo.trimPayloads()
            assertTrue(usage.excessBytes > 0)
            assertEquals(notedBefore, dao.article("noted"))
            assertEquals(full, dao.fullBody("noted")!!.body)
            assertFalse(dao.article("control")!!.body.contains("base64"))
            assertTrue(dao.article("control")!!.body.contains("RSS prose survives"))
            assertNull(dao.fullBody("control"))
            repo.deleteNote("full-note", "noted")
            repo.trimPayloads() // Even an RSS-only note protects both payloads.
            assertEquals(notedBefore, dao.article("noted"))
            assertEquals(full, dao.fullBody("noted")!!.body)
            repo.deleteNote("rss-note", "noted")
            repo.trimPayloads()
            assertTrue(dao.allNotes().isEmpty())
            assertNull(dao.fullBody("noted"))
            val after = dao.article("noted")!!
            assertFalse(after.body.contains("base64"))
            assertTrue(after.body.contains("RSS prose survives"))
            assertEquals(notedBefore.copy(body = after.body, cachedImages = 0), after)
        } finally { db.close() }
    }

    @Test fun protectedExcessAndOldestEligibleImagesOnlyThenBulkRevisionUndo() = runBlocking {
        context.getSharedPreferences("direct_rss_offline", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = LocalOfflineSettings(context)
        settings.update(LocalOfflinePolicy(capacityMiB = 64, retainedReadDays = 7))
        assertEquals(settings.policy(), LocalOfflineSettings(context).policy())
        val db = Room.inMemoryDatabaseBuilder(context, DirectRssDatabase::class.java).allowMainThreadQueries().build()
        try {
            val now = 100L * 86_400_000
            val repo = DirectRssRepository(db, clock = { now }, offline = settings)
            // < 1 MiB per row; protected content alone exceeds 64 MiB, never deleted.
            val protectedBody = "<p>" + "p".repeat(900_000) + "</p>"
            repeat(76) { index -> db.dao().putArticle(DirectArticle("protected-$index", "$index", "s", "Title", 0,
                protectedBody, "https://example.org/$index", isRead = true, isStarred = true)) }
            val image = "<p>RSS survives</p><img src=\"data:image/png;base64,${"A".repeat(100_000)}\" data-original=\"https://example.org/i\">"
            db.dao().putArticle(DirectArticle("old", "old", "s", "Old", 0, image, "https://example.org/old", isRead = true))
            settings.recordRead("old", true, 1)
            db.dao().putFullBody(DirectFullBody("old", "<p>Optional extracted body</p>"))
            val before = repo.trimPayloads(evict = false)
            val after = repo.trimPayloads()
            assertTrue(after.excessBytes > 0)
            assertTrue(after.protectedBytes > after.limitBytes)
            assertTrue(after.payloadBytes < before.payloadBytes)
            assertEquals(protectedBody, db.dao().article("protected-0")!!.body)
            assertTrue(db.dao().article("old")!!.body.contains("RSS survives"))
            assertFalse(db.dao().article("old")!!.body.contains("base64"))
            assertNull(db.dao().fullBody("old"))
            db.dao().putArticle(DirectArticle("a", "a", "s", "A", 1, "body", "https://example.org/a"))
            db.dao().putArticle(DirectArticle("b", "b", "s", "B", 2, "body", "https://example.org/b"))
            val receipt = repo.bulkRead(listOf("a", "b", "a"))
            assertEquals(2, receipt.changes.size)
            repo.toggleStar("b")
            assertEquals(listOf("a"), repo.undoRead(receipt).changes.map { it.id })
            assertFalse(db.dao().article("a")!!.isRead)
            assertTrue(db.dao().article("b")!!.isRead)
            assertTrue(repo.undoRead(receipt).changes.isEmpty())
        } finally { db.close() }
    }
}
