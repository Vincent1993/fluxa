package com.fluxa.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.local.*
import com.fluxa.app.data.reader.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReaderFeaturesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: FluxaDatabase
    private lateinit var local: LocalReadingRepository
    @Before fun setup() = runBlocking {
        context.deleteDatabase("fluxa.db")
        db = Room.inMemoryDatabaseBuilder(context, FluxaDatabase::class.java).allowMainThreadQueries().build()
        local = LocalReadingRepository(db, context)
        db.articleDao().upsertAll(listOf(ArticleEntity("one", "Title", "Feed", 100, false, false, "<p>Hello <b>reader</b> 世界</p>")))
    }
    @After fun cleanup() { db.close(); context.deleteDatabase("fluxa.db") }

    @Test fun notesHighlightsTagsProgressAndMetadataPersistAndSurviveRefresh(): Unit = runBlocking {
        local.remoteMetadata("one", "<p>Hello <b>reader</b> 世界</p>", "https://example.test/story", "Author")
        local.note("one", "A real typed note")
        local.highlight("one", "reader", "Context")
        local.tags("one", listOf("Tech", "tech", " 阅读 "))
        local.progress("one", .67f); local.open("one"); local.save("one")
        val before = db.observeReaderArticles().first().single()
        assertEquals("Author", before.author); assertEquals("https://example.test/story", before.url)
        assertEquals(4, before.wordCount); assertEquals(listOf("Tech", "阅读"), before.tags.sorted())
        assertEquals(2, before.annotationCount); assertEquals(.67f, before.readingProgress, .001f)
        assertNotNull(before.savedAtEpochSeconds); assertNotNull(before.lastOpenedAtEpochSeconds)
        local.remoteMetadata("one", "<p>Updated story</p>", "", "")
        val after = db.observeReaderArticles().first().single()
        assertEquals(.67f, after.readingProgress, .001f); assertEquals(2, after.annotationCount)
        assertEquals(before.tags, after.tags); assertEquals(before.url, after.url)
        assertTrue(db.pendingActionDao().getAllOrdered().isEmpty())
        val note = local.annotations("one").first().first { it.kind == "note" }
        local.editAnnotation(note.id, "", "Edited note")
        assertEquals("Edited note", local.annotations("one").first().first { it.id == note.id }.note)
        local.deleteAnnotation(note.id)
        assertEquals(1, local.annotations("one").first().size)
    }
    @Test fun archiveKeepsContentAndQueuesOnlySupportedReadThenRestore(): Unit = runBlocking {
        local.archive("one")
        assertNotNull(db.readerDao().metadata("one")!!.archivedAtEpochSeconds)
        assertTrue(db.articleDao().getById("one")!!.isRead)
        assertEquals(listOf("MarkRead"), db.pendingActionDao().getAllOrdered().map { it.actionType })
        assertEquals("<p>Hello <b>reader</b> 世界</p>", db.articleDao().getById("one")!!.contentHtml)
        local.archive("one"); assertNull(db.readerDao().metadata("one")!!.archivedAtEpochSeconds)
        local.save("one"); local.save("one"); assertNull(db.readerDao().metadata("one")!!.savedAtEpochSeconds)
    }
    @Test fun invalidAnnotationsAndNonfiniteProgressCannotMutateStorage(): Unit = runBlocking {
        try { local.note("one", " "); fail() } catch (_: IllegalArgumentException) { }
        try { local.highlight("one", "not in cached body"); fail() } catch (_: IllegalArgumentException) { }
        try { local.progress("one", Float.NaN); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(local.annotations("one").first().isEmpty())
        assertNull(db.readerDao().metadata("one"))
    }
    @Test fun importsRichLegacyDataReadOnlyIdempotentlyWithoutBackendQueue(): Unit = runBlocking {
        context.openOrCreateDatabase("fluxa.db", Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("""CREATE TABLE articles (id TEXT PRIMARY KEY NOT NULL,title TEXT NOT NULL,url TEXT NOT NULL,author TEXT NOT NULL,
                excerpt TEXT NOT NULL,feedName TEXT NOT NULL,publishedAtEpochSeconds INTEGER NOT NULL,isRead INTEGER NOT NULL,isStarred INTEGER NOT NULL,
                contentHtml TEXT NOT NULL,readingProgress REAL NOT NULL,archivedAtEpochSeconds INTEGER,savedAtEpochSeconds INTEGER,
                lastOpenedAtEpochSeconds INTEGER,wordCount INTEGER NOT NULL)""")
            old.execSQL("INSERT INTO articles VALUES ('old','Old','https://example.test/old','Old author','Excerpt','Old feed',99,0,1,'<p>Legacy body</p>',.45,20,21,22,42)")
            old.execSQL("CREATE TABLE highlights (id INTEGER PRIMARY KEY,articleId TEXT,quote TEXT,note TEXT,color TEXT,createdAtEpochSeconds INTEGER,updatedAtEpochSeconds INTEGER)")
            old.execSQL("INSERT INTO highlights VALUES (1,'old','Legacy','Keep','#f1d28a',10,11)")
            old.execSQL("CREATE TABLE tags (id TEXT PRIMARY KEY,name TEXT,createdAtEpochSeconds INTEGER,updatedAtEpochSeconds INTEGER)")
            old.execSQL("INSERT INTO tags VALUES ('t','Old tag',10,11)")
            old.execSQL("CREATE TABLE article_tag_cross_ref (articleId TEXT,tagId TEXT,PRIMARY KEY(articleId,tagId))")
            old.execSQL("INSERT INTO article_tag_cross_ref VALUES ('old','t')")
            old.version = 2
        }
        assertEquals(1, local.importLegacy()); assertEquals(0, local.importLegacy())
        val article = db.observeReaderArticles().first().first { it.id == "legacy/old" }
        assertEquals("Old author", article.author); assertEquals(42, article.wordCount)
        assertEquals(.45f, article.readingProgress, .001f); assertEquals(20L, article.archivedAtEpochSeconds)
        assertEquals(listOf("Old tag"), article.tags); assertEquals(1, article.annotationCount)
        local.archive(article.id)
        local.tags(article.id, listOf("Edited tag"))
        local.deleteAnnotation(local.annotations(article.id).first().single().id)
        assertEquals(0, local.importLegacy())
        val reimported = db.observeReaderArticles().first().first { it.id == article.id }
        assertNull(reimported.archivedAtEpochSeconds)
        assertEquals(listOf("Edited tag"), reimported.tags)
        assertEquals(0, reimported.annotationCount)
        assertTrue(db.pendingActionDao().getAllOrdered().isEmpty())
        context.openOrCreateDatabase("fluxa.db", Context.MODE_PRIVATE, null).use { old ->
            assertEquals(2, old.version)
            old.rawQuery("SELECT author,readingProgress FROM articles WHERE id='old'", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals("Old author", c.getString(0)); assertEquals(.45, c.getDouble(1), .001)
            }
        }
    }
}
