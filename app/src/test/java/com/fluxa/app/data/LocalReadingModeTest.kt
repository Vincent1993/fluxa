package com.fluxa.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.directrss.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalReadingModeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val source = DirectSubscription("direct-rss:owned-mode", "Owned", "https://example.org/feed", false)
    private val article = DirectArticle("direct-rss:owned-article", "guid", source.id, "Owned", 123,
        "<p>RSS stays readable</p>", "https://example.org/page", true, true, 1, 2, "category", "summary", 3)
    private fun page(prose: String = "Extracted fixture") = "<html><title>Owned</title><article><p>${("$prose. ").repeat(30)}</p></article></html>".toByteArray()
    private fun open(name: String) = Room.databaseBuilder(context, DirectRssDatabase::class.java, name)
        .addMigrations(DirectRssDatabase.MIGRATION_1_2, DirectRssDatabase.MIGRATION_2_3, DirectRssDatabase.MIGRATION_3_4, DirectRssDatabase.MIGRATION_4_5, DirectRssDatabase.MIGRATION_5_6).allowMainThreadQueries().build()
    private suspend fun seed(db: DirectRssDatabase) { db.dao().putSubscription(source); db.dao().putArticle(article) }

    @Test fun modeChoiceIsLazyPersistsAcrossReopenAndFullTextIsIdempotentOffline() = runBlocking {
        val name = "owned-mode-${UUID.randomUUID()}"
        var requests = 0
        try {
            open(name).let { db -> try {
                seed(db)
                val repo = DirectRssRepository(db, DirectRssClient(readFeed = { requests++; page() }))
                assertEquals(LocalReadingMode.RSS, repo.readingMode(source.id).first())
                repo.setReadingMode(source.id, LocalReadingMode.FULL_TEXT)
                assertEquals(0, requests)
                assertNull(db.dao().fullBody(article.id))
                val full = repo.fullBody(article.id)
                assertTrue(full.contains("Extracted fixture")); assertEquals(1, requests)
                assertEquals(full, repo.fullBody(article.id)); assertEquals(1, requests)
                assertEquals(article, db.dao().article(article.id))
                // A later article shares the source policy without any eager network request.
                db.dao().putArticle(article.copy(id = "direct-rss:later"))
                assertEquals(LocalReadingMode.FULL_TEXT, repo.readingMode(source.id).first())
                assertNull(db.dao().fullBody("direct-rss:later"))
            } finally { db.close() } }
            open(name).let { db -> try {
                val offline = DirectRssRepository(db, DirectRssClient(readFeed = { error("No network permitted") }))
                assertEquals(LocalReadingMode.FULL_TEXT, offline.readingMode(source.id).first())
                assertTrue(offline.fullBody(article.id).contains("Extracted fixture"))
                offline.setReadingMode(source.id, LocalReadingMode.ORIGINAL)
                assertEquals(LocalReadingMode.ORIGINAL, offline.readingMode(source.id).first())
                assertEquals(article, db.dao().article(article.id))
            } finally { db.close() } }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun extractionFailureNeverReplacesRssOrCreatesFullBody() = runBlocking {
        val name = "owned-failure-${UUID.randomUUID()}"
        val db = open(name)
        try {
            seed(db)
            val repo = DirectRssRepository(db, DirectRssClient(readFeed = { error("Offline") }))
            try { repo.fullBody(article.id); fail("Expected failure") } catch (_: IllegalStateException) { }
            assertEquals(article, db.dao().article(article.id)); assertNull(db.dao().fullBody(article.id))
            db.dao().putMode(DirectReadingMode(source.id, "unknown-future-mode"))
            assertEquals(LocalReadingMode.RSS, repo.readingMode(source.id).first())
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun obsoleteAndCancelledExtractionsCannotBeatNewGeneration() = runBlocking {
        for (cancel in listOf(false, true)) {
            val name = "owned-race-${UUID.randomUUID()}"
            val db = open(name)
            val started = CountDownLatch(1); val release = CountDownLatch(1)
            val current = AtomicBoolean(true)
            try {
                seed(db)
                val old = DirectRssRepository(db, DirectRssClient(readFeed = {
                    started.countDown(); check(release.await(10, TimeUnit.SECONDS)); page("Obsolete body")
                }))
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val job = scope.launch { old.fullBody(article.id) { current.get() } }
                try {
                    assertTrue(started.await(10, TimeUnit.SECONDS))
                    current.set(false)
                    if (cancel) job.cancel()
                    val newer = DirectRssRepository(db, DirectRssClient(readFeed = { page("New body") }))
                    newer.fullBody(article.id)
                    release.countDown(); job.join()
                    assertTrue(db.dao().fullBody(article.id)!!.body.contains("New body"))
                    assertFalse(db.dao().fullBody(article.id)!!.body.contains("Obsolete body"))
                    assertEquals(article, db.dao().article(article.id))
                } finally { release.countDown(); job.cancelAndJoin(); scope.cancel() }
            } finally { release.countDown(); db.close(); context.deleteDatabase(name) }
        }
    }
    @Test fun obsoleteLeaseWithoutCompetingBodyCannotPersist() = runBlocking {
        val name = "owned-obsolete-${UUID.randomUUID()}"
        val db = open(name)
        try {
            seed(db)
            val repo = DirectRssRepository(db, DirectRssClient(readFeed = { page() }))
            try { repo.fullBody(article.id) { false }; fail("Obsolete result accepted") }
            catch (_: CancellationException) { }
            assertNull(db.dao().fullBody(article.id)); assertEquals(article, db.dao().article(article.id))
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun rssRefreshPreservesExtractedBodyAndReadStarMarks() = runBlocking {
        val name = "owned-refresh-${UUID.randomUUID()}"
        val db = open(name)
        var rssText = "Original RSS"
        try {
            val repo = DirectRssRepository(db, DirectRssClient(readFeed = { url ->
                if (url.endsWith("page")) page() else
                    "<rss><channel><title>Owned</title><item><guid>owned</guid><title>Owned</title><link>https://example.org/page</link><description>$rssText</description></item></channel></rss>".toByteArray()
            }))
            repo.addSubscription("https://example.org/feed")
            val row = db.dao().articles().single()
            repo.markRead(row.id); repo.toggleStar(row.id)
            val full = repo.fullBody(row.id)
            rssText = "Updated RSS"
            repo.refresh()
            val updated = db.dao().article(row.id)!!
            assertTrue(updated.body.contains("Updated RSS")); assertTrue(updated.isRead && updated.isStarred)
            assertEquals(full, repo.fullBody(row.id)); assertFalse(updated.body.contains("Extracted fixture"))
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun sameGuidChangedUrlInvalidatesOnlyItsOptionalBodyAndCanExtractNewPage() = runBlocking {
        val name = "owned-url-refresh-${UUID.randomUUID()}"
        val db = open(name)
        var url = "https://example.org/old-page"
        fun feed() = """<rss><channel><title>Owned</title><item><guid>stable-guid</guid><title>Owned</title>
            <link>$url</link><description>Current RSS fallback</description></item></channel></rss>""".toByteArray()
        try {
            val repo = DirectRssRepository(db, DirectRssClient(readFeed = {
                if (it.endsWith("/feed")) feed() else page(if (it.endsWith("old-page")) "Old page" else "New page")
            }))
            repo.addSubscription("https://example.org/feed")
            val row = db.dao().articles().single()
            repo.markRead(row.id); repo.toggleStar(row.id)
            repo.organize(row.source, "Owned alias", null, false, false)
            repo.setReadingMode(row.source, LocalReadingMode.FULL_TEXT)
            db.dao().putFullBody(DirectFullBody("unrelated-owned-id", "Unaffected"))
            val oldBody = repo.fullBody(row.id)
            repo.refresh() // Same GUID and URL must keep the exact cached optional body.
            assertEquals(oldBody, db.dao().fullBody(row.id)!!.body)
            url = "https://example.org/new-page"
            repo.refresh()
            assertNull(db.dao().fullBody(row.id))
            val current = db.dao().articles().single()
            assertEquals(row.id, current.id); assertEquals(row.guid, current.guid)
            assertEquals(url, current.originalUrl); assertEquals(row.body, current.body)
            assertTrue(current.isRead && current.isStarred)
            assertEquals("Owned alias", db.dao().subscription(row.source)!!.alias)
            assertEquals(LocalReadingMode.FULL_TEXT, repo.readingMode(row.source).first())
            assertEquals("Unaffected", db.dao().fullBody("unrelated-owned-id")!!.body)
            assertTrue(repo.fullBody(row.id).contains("New page"))
            assertFalse(db.dao().fullBody(row.id)!!.body.contains("Old page"))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun refreshChangingUrlWhileExtractionRunsRejectsObsoleteCommit() = runBlocking {
        val name = "owned-url-race-${UUID.randomUUID()}"
        val db = open(name)
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var url = "https://example.org/old-page"
        try {
            val repo = DirectRssRepository(db, DirectRssClient(readFeed = { requested ->
                if (requested.endsWith("/feed")) {
                    """<rss><channel><title>Owned</title><item><guid>stable-guid</guid><title>Owned</title>
                        <link>$url</link><description>RSS fallback</description></item></channel></rss>""".toByteArray()
                } else if (requested.endsWith("old-page")) {
                    started.countDown(); check(release.await(10, TimeUnit.SECONDS)); page("Obsolete URL body")
                } else page("Current URL body")
            }))
            repo.addSubscription("https://example.org/feed")
            val row = db.dao().articles().single()
            val old = scope.async { runCatching { repo.fullBody(row.id) } }
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS))
                url = "https://example.org/new-page"
                repo.refresh()
                assertNull(db.dao().fullBody(row.id))
                release.countDown()
                assertTrue("URL guard must reject the old extraction", old.await().exceptionOrNull() is IllegalArgumentException)
                assertNull(db.dao().fullBody(row.id))
                assertEquals(row.body, db.dao().article(row.id)!!.body)
                assertTrue(repo.fullBody(row.id).contains("Current URL body"))
            } finally { release.countDown(); old.cancelAndJoin() }
        } finally { release.countDown(); scope.cancel(); db.close(); context.deleteDatabase(name) }
    }

    @Test fun realV2MigrationPreservesEveryExistingFieldAndAddsSeparateTables() = runBlocking {
        val name = "owned-v2-${UUID.randomUUID()}"
        val path = "schemas/com.fluxa.app.data.directrss.DirectRssDatabase/2.json"
        val schema = JSONObject(sequenceOf(File(path), File("app/$path")).first { it.exists() }.readText()).getJSONObject("database")
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sql ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                    sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    entity.optJSONArray("indices")?.let { indices ->
                        for (j in 0 until indices.length()) sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
                sql.execSQL("INSERT INTO folders VALUES ('folder', 'Reading')")
                sql.execSQL("INSERT INTO subscriptions (id,title,url,dailyOnly,lastSuccess,status,alias,folderId,paused,archived) VALUES ('source','Title','https://example.org/feed',1,456,'status','Alias','folder',1,1)")
                sql.execSQL("INSERT INTO articles VALUES ('article','guid','source','Title',123,'<p>RSS</p>','https://example.org/a',1,1,2,3,'category','summary',4)")
                sql.version = 2
            }
            open(name).let { db -> try {
                assertEquals(DirectSubscription("source", "Title", "https://example.org/feed", true, 456, "status", "Alias", "folder", true, true), db.dao().subscription("source"))
                assertEquals(DirectArticle("article", "guid", "source", "Title", 123, "<p>RSS</p>", "https://example.org/a", true, true, 2, 3, "category", "summary", 4), db.dao().article("article"))
                assertEquals("Reading", db.dao().folder("folder")!!.name)
                assertNull(db.dao().fullBody("article"))
                db.dao().putFullBody(DirectFullBody("article", "separate"))
                db.dao().putMode(DirectReadingMode("source", "FULL_TEXT"))
            } finally { db.close() } }
            open(name).let { db -> try {
                assertEquals("<p>RSS</p>", db.dao().article("article")!!.body)
                assertEquals("separate", db.dao().fullBody("article")!!.body)
                assertEquals("FULL_TEXT", db.dao().observeMode("source").first()!!.mode)
            } finally { db.close() } }
        } finally { context.deleteDatabase(name) }
    }
}
