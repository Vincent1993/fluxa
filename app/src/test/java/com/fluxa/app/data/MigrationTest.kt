package com.fluxa.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun migratesMainV1WithoutLosingArticleContentOrFlags() = verifyMigration(1, "main")
    @Test fun migratesSyncV2AndRetainsCursor() = verifyMigration(2, "sync")
    @Test fun migratesPendingV2AndRetainsActions() = verifyMigration(2, "pending")
    @Test fun migratesFilterV2AndRetainsSourceAndTags() = verifyMigration(2, "filter")
    @Test fun migratesCombinedExperimentalV2() = verifyMigration(2, "combined")

    private fun verifyMigration(version: Int, variant: String) = runBlocking {
        val name = "migration-$variant.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val extended = variant == "filter" || variant == "combined"
            old.execSQL("""CREATE TABLE articles (
                id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, feedName TEXT NOT NULL,
                publishedAtEpochSeconds INTEGER NOT NULL, isRead INTEGER NOT NULL,
                isStarred INTEGER NOT NULL, contentHtml TEXT NOT NULL
                ${if (extended) ", source TEXT NOT NULL, tags TEXT NOT NULL" else ""})""")
            old.execSQL("INSERT INTO articles VALUES ('one', 'Title', 'Feed', 1234, 1, 1, '<p>Saved</p>'" +
                if (extended) ", 'feed/one', 'tag-one')" else ")")
            if (variant == "sync" || variant == "combined") {
                old.execSQL("""CREATE TABLE sync_state (source TEXT NOT NULL PRIMARY KEY,
                    cursor TEXT, lastSyncAtEpochMillis INTEGER NOT NULL, syncToken TEXT)""")
                old.execSQL("INSERT INTO sync_state VALUES ('reading', 'next', 1234, 'sync')")
            }
            if (variant == "pending" || variant == "combined") {
                old.execSQL("""CREATE TABLE pending_actions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    articleId TEXT NOT NULL, actionType TEXT NOT NULL, payload TEXT NOT NULL,
                    createdAtEpochMillis INTEGER NOT NULL)""")
                old.execSQL("INSERT INTO pending_actions VALUES (1, 'one', 'ToggleStar', 'false', 1234)")
                old.execSQL("INSERT INTO pending_actions VALUES (2, 'one', 'AddNote', 'Retain this note', 1235)")
            }
            old.version = version
        }
        val db = Room.databaseBuilder(context, FluxaDatabase::class.java, name)
            .addMigrations(DatabaseMigrations.FROM_1, DatabaseMigrations.FROM_2)
            .allowMainThreadQueries().build()
        try {
            // Opening through Room also validates every resulting table against its schema.
            val article = db.articleDao().getById("one")!!
            assertEquals("<p>Saved</p>", article.contentHtml)
            assertTrue(article.isRead)
            assertTrue(article.isStarred)
            assertEquals(1234L, article.publishedAtEpochSeconds)
            assertEquals(if (variant == "filter" || variant == "combined") "feed/one" else "", article.source)
            if (variant == "sync" || variant == "combined")
                assertEquals("next", db.syncStateDao().getBySource("reading")!!.cursor)
            if (variant == "pending" || variant == "combined") {
                assertEquals(2, db.pendingActionDao().getAllOrdered().size)
                assertEquals("Retain this note", db.pendingActionDao().getAllOrdered().last().payload)
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
