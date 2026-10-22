package com.fluxa.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.directrss.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class Stage4LocalReaderTest {
    @Test fun exportedV5UpgradeAndReopenPreservePayloadAndNewIndependentState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "owned-stage4-${UUID.randomUUID()}.db"
        val path = "schemas/com.fluxa.app.data.directrss.DirectRssDatabase/5.json"
        val schema = JSONObject(sequenceOf(File(path), File("app/$path")).first { it.exists() }.readText()).getJSONObject("database")
        val id = "direct-rss:stage4-owned"
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sql ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                    sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    entity.optJSONArray("indices")?.let { indices -> for (j in 0 until indices.length())
                        sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
                sql.execSQL("INSERT INTO subscriptions (id,title,url,dailyOnly,lastSuccess,status,paused,archived) VALUES ('source','Publisher','https://example.org/feed',0,123,'ok',0,0)")
                sql.execSQL("INSERT INTO articles (id,guid,source,title,published,body,originalUrl,isRead,isStarred,cachedImages,totalImages,categories,summary,readMinutes,author) VALUES (?, 'guid','source','Title',123,'<p>RSS 汉字</p>','https://example.org/a',1,0,0,0,'category','summary',4,'Author')", arrayOf(id))
                sql.execSQL("INSERT INTO full_bodies VALUES (?, '<p>Full text</p>')", arrayOf(id))
                sql.execSQL("INSERT INTO reading_modes VALUES ('source','FULL_TEXT')")
                sql.execSQL("INSERT INTO local_revisions VALUES (?, 'revision')", arrayOf(id))
                sql.version = 5
            }
            fun open() = Room.databaseBuilder(context, DirectRssDatabase::class.java, name)
                .addMigrations(DirectRssDatabase.MIGRATION_1_2, DirectRssDatabase.MIGRATION_2_3,
                    DirectRssDatabase.MIGRATION_3_4, DirectRssDatabase.MIGRATION_4_5, DirectRssDatabase.MIGRATION_5_6)
                .allowMainThreadQueries().build()
            val note = DirectReaderNote("owned-note", id, "RSS", "RSS 汉字", stableHash("RSS 汉字"), 0, 1, "note <script> & text")
            val readLaterRevision = open().let { db -> try {
                val repo = DirectRssRepository(db)
                // Migration preserves the old marker before any local flag mutation.
                val migrated = requireNotNull(db.dao().article(id))
                assertFalse(migrated.readLater)
                assertTrue(migrated.isRead)
                assertFalse(migrated.isStarred)
                assertEquals("<p>RSS 汉字</p>", migrated.body)
                assertEquals("<p>Full text</p>", db.dao().fullBody(id)!!.body)
                assertEquals("FULL_TEXT", db.dao().mode("source")!!.mode)
                assertEquals("revision", db.dao().revision(id)!!.revision)
                assertEquals("Author", migrated.author)
                repo.setReadLater(id, true)
                val changedRevision = requireNotNull(db.dao().revision(id)).revision
                assertTrue(changedRevision.isNotBlank())
                assertNotEquals("revision", changedRevision)
                assertEquals(migrated.copy(readLater = true), db.dao().article(id))
                // Notes are independent; they do not advance the article flag marker.
                repo.saveNote(note)
                assertEquals(changedRevision, db.dao().revision(id)!!.revision)
                changedRevision
            } finally { db.close() } }
            open().let { db -> try {
                val repo = DirectRssRepository(db)
                assertTrue(repo.articles().first().single().readLater)
                val reopened = requireNotNull(db.dao().article(id))
                assertTrue(reopened.isRead)
                assertFalse(reopened.isStarred)
                assertEquals("Author", reopened.author)
                assertEquals("<p>RSS 汉字</p>", reopened.body)
                assertEquals("<p>Full text</p>", db.dao().fullBody(id)!!.body)
                assertEquals("FULL_TEXT", db.dao().mode("source")!!.mode)
                assertEquals(readLaterRevision, db.dao().revision(id)!!.revision)
                assertEquals(note, repo.notes(id).first().single())
                repo.deleteNote(note.id, id); assertTrue(repo.notes(id).first().isEmpty())
                assertEquals(readLaterRevision, db.dao().revision(id)!!.revision)
                repo.saveNote(note); assertEquals(note, repo.notes(id).first().single())
                assertEquals(readLaterRevision, db.dao().revision(id)!!.revision)
                repo.setReadLater(id, false)
                val clearedRevision = requireNotNull(db.dao().revision(id)).revision
                assertTrue(clearedRevision.isNotBlank())
                assertNotEquals(readLaterRevision, clearedRevision)
                assertNotEquals("revision", clearedRevision)
                assertFalse(repo.articles().first().single().readLater)
                assertEquals(reopened.copy(readLater = false), db.dao().article(id))
                assertEquals("<p>Full text</p>", db.dao().fullBody(id)!!.body)
                assertEquals("FULL_TEXT", db.dao().mode("source")!!.mode)
                assertEquals(note, repo.notes(id).first().single())
            } finally { db.close() } }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun readLaterProtectsOldReadUnstarredPayloadAndSavedPagesRemainProtected() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = LocalOfflineSettings(context)
        val db = Room.inMemoryDatabaseBuilder(context, DirectRssDatabase::class.java).allowMainThreadQueries().build()
        try {
            val row = DirectArticle("direct-rss:stage4-protect", "guid", "source", "Title", 1, "<p>Keep prose</p>", "https://example.org/a", isRead = true)
            db.dao().putArticle(row)
            settings.recordRead(row.id, true, 1)
            val repo = DirectRssRepository(db, clock = { 100L * 86400 * 1000 }, offline = settings)
            assertEquals(0L, repo.trimPayloads(evict = false).protectedBytes)
            repo.setReadLater(row.id, true)
            assertEquals(payloadBytes(row.body), repo.trimPayloads(evict = false).protectedBytes)
            repo.setReadLater(row.id, false)
            assertEquals(0L, repo.trimPayloads(evict = false).protectedBytes)
            db.dao().putArticle(row.copy(id = "direct-rss:saved-stage4", source = SAVED_PAGE_SOURCE))
            assertEquals(payloadBytes(row.body), repo.trimPayloads(evict = false).protectedBytes)
            assertTrue(repo.articles().first().first { it.source == SAVED_PAGE_SOURCE }.readLater)
            try { repo.setReadLater("direct-rss:saved-stage4", false); fail("Saved pages stay protected") } catch (_: IllegalArgumentException) { }
        } finally { db.close() }
    }
}
