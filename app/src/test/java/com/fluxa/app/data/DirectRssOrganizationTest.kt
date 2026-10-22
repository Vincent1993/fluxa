package com.fluxa.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.directrss.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** Synthetic owned file databases only; never the application's direct-rss.db. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DirectRssOrganizationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private inline fun <T> DirectRssDatabase.use(block: (DirectRssDatabase) -> T): T =
        try { block(this) } finally { close() }
    private fun open(name: String) = Room.databaseBuilder(context, DirectRssDatabase::class.java, name)
        .addMigrations(DirectRssDatabase.MIGRATION_1_2, DirectRssDatabase.MIGRATION_2_3, DirectRssDatabase.MIGRATION_3_4, DirectRssDatabase.MIGRATION_4_5, DirectRssDatabase.MIGRATION_5_6).allowMainThreadQueries().build()

    @Test fun realV1SchemaMigratesAndReopensWithAllRowsFlagsAndForeignKeyIntact() = runBlocking {
        val name = "rss-migration-fixture-${UUID.randomUUID()}.db"
        val path = "schemas/com.fluxa.app.data.directrss.DirectRssDatabase/1.json"
        val schema = JSONObject(sequenceOf(File(path), File("app/$path")).first { it.exists() }.readText()).getJSONObject("database")
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sql ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    entity.optJSONArray("indices")?.let { indices ->
                        for (j in 0 until indices.length()) sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
                sql.execSQL("INSERT INTO subscriptions VALUES ('source', 'Original', 'https://example.org/feed', 1, 123, 'old status')")
                sql.execSQL("INSERT INTO articles VALUES ('article', 'guid', 'source', 'Title', 123, '<p>body retained</p>', 'https://example.org/a', 1, 1, 2, 3, '早报', 'summary', 2)")
                sql.version = 1
            }
            open(name).use { db ->
                val repo = DirectRssRepository(db)
                val before = db.dao().subscription("source")!!
                assertEquals(DirectSubscription("source", "Original", "https://example.org/feed", true, 123, "old status"), before)
                val folder = repo.createFolder("  Reading  ")
                assertEquals(folder, repo.createFolder("Reading"))
                repo.organize("source", "  Alias  ", folder, paused = true, archived = true)
                try { repo.organize("source", null, "missing", false, false); fail("Missing FK accepted") } catch (_: IllegalArgumentException) { }
                try { repo.createFolder(" \n "); fail("Blank folder accepted") } catch (_: IllegalArgumentException) { }
                try {
                    db.dao().putSubscription(before.copy(folderId = "missing"))
                    fail("SQLite FK accepted")
                } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            }
            open(name).use { db ->
                val source = db.dao().subscription("source")!!
                assertEquals("Alias", source.alias); assertTrue(source.paused); assertTrue(source.archived)
                assertEquals("Reading", db.dao().folder(source.folderId!!)!!.name)
                assertEquals(123L, source.lastSuccess); assertEquals("old status", source.status)
                val article = db.dao().article("article")!!
                assertTrue(article.isRead && article.isStarred)
                assertEquals("<p>body retained</p>", article.body)
                assertEquals(2, article.cachedImages); assertEquals(3, article.totalImages)
                assertEquals("summary", article.summary); assertEquals("早报", article.categories)
                assertEquals("Alias", DirectRssRepository(db).articles().first().single().feedName)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun pauseArchiveAliasAndRepeatedReactivationDoNotChangeIdentityOrRequestExcludedFeeds() = runBlocking {
        val name = "rss-organization-fixture-${UUID.randomUUID()}.db"
        var requests = 0
        val xml = "<rss><channel><title>Synthetic source</title><item><guid>fixed</guid><title>Synthetic article</title><description>body</description></item></channel></rss>"
        val client = DirectRssClient(readFeed = { requests++; xml.toByteArray() })
        try {
            open(name).use { db ->
                val repo = DirectRssRepository(db, client)
                repo.addSubscription("https://example.org/feed")
                val source = db.dao().subscriptions().single()
                val article = db.dao().articles().single()
                repo.markRead(article.id); repo.toggleStar(article.id)
                repo.organize(source.id, "Personal", null, true, false)
                repo.refresh(); repo.refresh(source.id)
                assertEquals(1, requests)
                repo.organize(source.id, "Personal", null, false, true)
                repo.refresh(); assertEquals(1, requests)
                repeat(2) { repo.addSubscription("https://example.org/feed") }
                assertEquals(3, requests)
                assertEquals(listOf(source.id), db.dao().subscriptions().map { it.id })
                assertEquals("Personal", db.dao().subscriptions().single().alias)
                assertFalse(db.dao().subscriptions().single().archived)
                assertEquals(article.copy(isRead = true, isStarred = true), db.dao().articles().single())
            }
            open(name).use { db ->
                val repo = DirectRssRepository(db, client)
                repo.refresh(); assertEquals(4, requests)
                assertEquals("Personal", repo.articles().first().single().feedName)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun cancelledFetchCannotCommitSubscriptionOrArticles() = kotlinx.coroutines.runBlocking {
        val name = "rss-cancel-fixture-${UUID.randomUUID()}.db"
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            open(name).use { db ->
                val repo = DirectRssRepository(db, DirectRssClient(readFeed = {
                    started.countDown()
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    "<rss><channel><item><title>Cancelled fixture</title></item></channel></rss>".toByteArray()
                }))
                val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    repo.addSubscription("https://example.org/cancelled")
                }
                try {
                    assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    job.cancel()
                } finally { release.countDown() }
                job.join()
                assertTrue(db.dao().subscriptions().isEmpty())
                assertTrue(db.dao().articles().isEmpty())
            }
        } finally { release.countDown(); context.deleteDatabase(name) }
    }

    @Test fun failedFirstSubscriptionCreatesNothing() = runBlocking {
        val name = "rss-failed-fixture-${UUID.randomUUID()}.db"
        try {
            open(name).use { db ->
                val repo = DirectRssRepository(db, DirectRssClient(readFeed = { "<html>not a feed</html>".toByteArray() }))
                try { repo.addSubscription("https://example.org/feed"); fail() } catch (_: IllegalStateException) { }
                assertTrue(db.dao().subscriptions().isEmpty()); assertTrue(db.dao().articles().isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }
}
