package com.fluxa.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val FROM_1 = upgrade(1)
    val FROM_2 = upgrade(2)

    // The unmerged branches used three different v2 schemas. Inspect columns and
    // rebuild only the article table so all paths have identical Room defaults.
    private fun upgrade(from: Int) = object : Migration(from, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(articles)").use { cursor ->
                while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            db.execSQL("""CREATE TABLE articles_v3 (
                id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, feedName TEXT NOT NULL,
                publishedAtEpochSeconds INTEGER NOT NULL, isRead INTEGER NOT NULL,
                isStarred INTEGER NOT NULL, contentHtml TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT '', tags TEXT NOT NULL DEFAULT '')""")
            val source = if ("source" in columns) "source" else "''"
            val tags = if ("tags" in columns) "tags" else "''"
            db.execSQL("""INSERT INTO articles_v3 SELECT id, title, feedName,
                publishedAtEpochSeconds, isRead, isStarred, contentHtml, $source, $tags FROM articles""")
            db.execSQL("DROP TABLE articles")
            db.execSQL("ALTER TABLE articles_v3 RENAME TO articles")
            db.execSQL("""CREATE TABLE IF NOT EXISTS pending_actions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, articleId TEXT NOT NULL,
                actionType TEXT NOT NULL, payload TEXT NOT NULL, createdAtEpochMillis INTEGER NOT NULL)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS sync_state (
                source TEXT NOT NULL PRIMARY KEY, cursor TEXT, lastSyncAtEpochMillis INTEGER NOT NULL,
                syncToken TEXT)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS subscriptions (
                id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, url TEXT NOT NULL)""")
        }
    }
}
