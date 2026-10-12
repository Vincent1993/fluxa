package com.fluxa.app.data.reader

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase

/** Local reading tables never participate in a backend's network replay queue. */
object ReaderSchema {
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS reader_metadata (articleId TEXT NOT NULL PRIMARY KEY,
            url TEXT NOT NULL, author TEXT NOT NULL, excerpt TEXT NOT NULL, wordCount INTEGER NOT NULL,
            readingProgress REAL NOT NULL, archivedAtEpochSeconds INTEGER, savedAtEpochSeconds INTEGER,
            lastOpenedAtEpochSeconds INTEGER)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS reader_annotations (id TEXT NOT NULL PRIMARY KEY,
            articleId TEXT NOT NULL, kind TEXT NOT NULL, quote TEXT NOT NULL, note TEXT NOT NULL,
            color TEXT NOT NULL, createdAtEpochSeconds INTEGER NOT NULL, updatedAtEpochSeconds INTEGER NOT NULL)""")
        db.execSQL("CREATE TABLE IF NOT EXISTS reader_tags (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAtEpochSeconds INTEGER NOT NULL, updatedAtEpochSeconds INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS reader_article_tags (articleId TEXT NOT NULL, tagId TEXT NOT NULL, PRIMARY KEY(articleId,tagId))")
    }
    fun copyLegacy(query: (String) -> Cursor, db: SupportSQLiteDatabase, prefix: String = "") {
        val imported = mutableSetOf<String>()
        fun eligible(id: String) = prefix.isEmpty() || id in imported
        fun exists(table: String) = query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { it.moveToFirst() }
        if (exists("articles")) query("SELECT * FROM articles").use { c ->
            while (c.moveToNext()) {
                val articleId = prefix + c.string("id")
                if (prefix.isNotEmpty()) {
                    val present = db.query("SELECT articleId FROM reader_metadata WHERE articleId=?", arrayOf(articleId)).use { it.moveToFirst() }
                    if (present) continue
                    imported += articleId
                }
                val body = c.string("contentHtml")
                val plain = ReaderHtml.text(body)
                db.execSQL("INSERT OR IGNORE INTO reader_metadata VALUES (?,?,?,?,?,?,?,?,?)", arrayOf(
                    prefix + c.string("id"), c.string("url"), c.string("author"), c.string("excerpt", plain.take(240)),
                    c.long("wordCount", ReaderHtml.wordCount(plain).toLong()), c.number("readingProgress"),
                    c.nullableLong("archivedAtEpochSeconds"), c.nullableLong("savedAtEpochSeconds"), c.nullableLong("lastOpenedAtEpochSeconds")))
            }
        }
        if (exists("highlights")) query("SELECT * FROM highlights").use { c ->
            while (c.moveToNext()) if (eligible(prefix + c.string("articleId"))) db.execSQL("INSERT OR IGNORE INTO reader_annotations VALUES (?,?,?,?,?,?,?,?)", arrayOf(
                prefix + "legacy/highlight/" + c.string("id"), prefix + c.string("articleId"), "highlight",
                c.string("quote"), c.string("note"), c.string("color", "#f1d28a"), c.long("createdAtEpochSeconds"), c.long("updatedAtEpochSeconds")))
        }
        if (exists("tags")) query("SELECT * FROM tags").use { c ->
            while (c.moveToNext()) db.execSQL("INSERT OR IGNORE INTO reader_tags VALUES (?,?,?,?)", arrayOf(prefix + c.string("id"), c.string("name"), c.long("createdAtEpochSeconds"), c.long("updatedAtEpochSeconds")))
        }
        if (exists("article_tag_cross_ref")) query("SELECT * FROM article_tag_cross_ref").use { c ->
            while (c.moveToNext()) if (eligible(prefix + c.string("articleId"))) db.execSQL("INSERT OR IGNORE INTO reader_article_tags VALUES (?,?)", arrayOf(prefix + c.string("articleId"), prefix + c.string("tagId")))
        }
        if (exists("pending_actions")) query("SELECT * FROM pending_actions").use { c ->
            while (c.moveToNext()) {
                val id = prefix + c.string("articleId")
                if (!eligible(id)) continue
                val kind = c.string("actionType")
                val time = c.long("createdAtEpochMillis") / 1000
                when (kind) {
                    "AddNote", "AddHighlight" -> db.execSQL("INSERT OR IGNORE INTO reader_annotations VALUES (?,?,?,?,?,?,?,?)", arrayOf(
                        prefix + "legacy/pending/" + c.string("id"), id, if (kind == "AddNote") "note" else "highlight",
                        if (kind == "AddHighlight") c.string("payload") else "", if (kind == "AddNote") c.string("payload") else "", "#f1d28a", time, time))
                    "Archive" -> db.execSQL("UPDATE reader_metadata SET archivedAtEpochSeconds=? WHERE articleId=?", arrayOf(time, id))
                    "SaveForLater" -> db.execSQL("UPDATE reader_metadata SET savedAtEpochSeconds=? WHERE articleId=?", arrayOf(time, id))
                }
            }
        }
    }
    fun Cursor.string(name: String, default: String = ""): String {
        val i = getColumnIndex(name); return if (i < 0 || isNull(i)) default else getString(i)
    }
    private fun Cursor.long(name: String, default: Long = 0): Long {
        val i = getColumnIndex(name); return if (i < 0 || isNull(i)) default else getLong(i)
    }
    private fun Cursor.nullableLong(name: String): Long? {
        val i = getColumnIndex(name); return if (i < 0 || isNull(i)) null else getLong(i)
    }
    private fun Cursor.number(name: String): Double {
        val i = getColumnIndex(name); return if (i < 0 || isNull(i)) 0.0 else getDouble(i)
    }
}
