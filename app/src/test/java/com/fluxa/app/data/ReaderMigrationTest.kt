package com.fluxa.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.local.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReaderMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun upgradeFromExportedV3PreservesCoreRowsAndCreatesLocalReaderState(): Unit = runBlocking {
        val name = "reader-upgrade-v3.db"; context.deleteDatabase(name)
        val path = "schemas/com.fluxa.app.data.local.FluxaDatabase/3.json"
        val file = File(path).takeIf { it.isFile } ?: File("app/$path")
        val entities = JSONObject(file.readText()).getJSONObject("database").getJSONArray("entities")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO articles VALUES ('one','Title','Feed',123,1,1,'<p>Saved body</p>','feed/one','Tech')")
            old.execSQL("INSERT INTO subscriptions VALUES ('feed/one','Feed','https://example.test/rss')")
            old.execSQL("INSERT INTO sync_state VALUES ('feed/one','2',1234,'cursor-token')")
            old.execSQL("INSERT INTO pending_actions VALUES (1,'one','ToggleStar','false',1234)")
            old.execSQL("INSERT INTO pending_actions VALUES (2,'one','AddNote','Old typed note',1235)")
            old.version = 3
        }
        val db = Room.databaseBuilder(context, FluxaDatabase::class.java, name)
            .addMigrations(DatabaseMigrations.FROM_3).allowMainThreadQueries().build()
        try {
            assertEquals(4, db.openHelper.readableDatabase.version)
            assertEquals("<p>Saved body</p>", db.articleDao().getById("one")!!.contentHtml)
            assertTrue(db.articleDao().getById("one")!!.isStarred)
            assertEquals("2", db.syncStateDao().getBySource("feed/one")!!.cursor)
            assertEquals(2, db.pendingActionDao().getAllOrdered().size)
            assertEquals("Old typed note", db.readerDao().observeAnnotations("one").first().single().note)
            assertEquals("Saved body", db.readerDao().metadata("one")!!.excerpt)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun richV2ColumnsHighlightsAndRelationsAreCapturedBeforeNormalization(): Unit = runBlocking {
        val name = "reader-upgrade-rich-v2.db"; context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("""CREATE TABLE articles (id TEXT PRIMARY KEY NOT NULL,title TEXT NOT NULL,url TEXT NOT NULL,author TEXT NOT NULL,
                excerpt TEXT NOT NULL,feedName TEXT NOT NULL,publishedAtEpochSeconds INTEGER NOT NULL,isRead INTEGER NOT NULL,isStarred INTEGER NOT NULL,
                contentHtml TEXT NOT NULL,readingProgress REAL NOT NULL,archivedAtEpochSeconds INTEGER,savedAtEpochSeconds INTEGER,
                lastOpenedAtEpochSeconds INTEGER,wordCount INTEGER NOT NULL)""")
            old.execSQL("INSERT INTO articles VALUES ('one','Title','https://example.test/story','Ada','Excerpt','Feed',123,1,1,'<p>Saved</p>',.75,10,11,12,42)")
            old.execSQL("CREATE TABLE highlights (id INTEGER PRIMARY KEY,articleId TEXT,quote TEXT,note TEXT,color TEXT,createdAtEpochSeconds INTEGER,updatedAtEpochSeconds INTEGER)")
            old.execSQL("INSERT INTO highlights VALUES (1,'one','Saved','Keep me','#f1d28a',20,21)")
            old.execSQL("CREATE TABLE tags (id TEXT PRIMARY KEY,name TEXT,createdAtEpochSeconds INTEGER,updatedAtEpochSeconds INTEGER)")
            old.execSQL("INSERT INTO tags VALUES ('tag','Tech',30,31)")
            old.execSQL("CREATE TABLE article_tag_cross_ref (articleId TEXT,tagId TEXT,PRIMARY KEY(articleId,tagId))")
            old.execSQL("INSERT INTO article_tag_cross_ref VALUES ('one','tag')")
            old.version = 2
        }
        val db = Room.databaseBuilder(context, FluxaDatabase::class.java, name)
            .addMigrations(DatabaseMigrations.FROM_2, DatabaseMigrations.FROM_3).allowMainThreadQueries().build()
        try {
            val core = db.articleDao().getById("one")!!
            assertEquals("<p>Saved</p>", core.contentHtml); assertTrue(core.isRead); assertTrue(core.isStarred)
            val metadata = db.readerDao().metadata("one")!!
            assertEquals("Ada", metadata.author); assertEquals("https://example.test/story", metadata.url)
            assertEquals(.75f, metadata.readingProgress, .001f); assertEquals(42, metadata.wordCount)
            assertEquals(10L, metadata.archivedAtEpochSeconds); assertEquals(11L, metadata.savedAtEpochSeconds)
            assertEquals("Keep me", db.readerDao().observeAnnotations("one").first().single().note)
            assertEquals("Tech", db.readerDao().observeTags().first().single().name)
            assertEquals(30L, db.readerDao().observeTags().first().single().createdAtEpochSeconds)
            assertEquals("tag", db.readerDao().observeLinks().first().single().tagId)
            // Original extra tables are retained as well, rather than discarded.
            db.openHelper.readableDatabase.query("SELECT note FROM highlights WHERE id=1").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("Keep me", c.getString(0))
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
