package com.fluxa.app.data.reader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.fluxa.app.data.local.*
import com.fluxa.app.domain.model.Article
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalReadingRepository @Inject constructor(private val db: FluxaDatabase, @ApplicationContext private val context: Context) {
    fun annotations(id: String) = db.readerDao().observeAnnotations(id)
    suspend fun remoteMetadata(id: String, html: String, url: String, author: String) = db.updateReaderMetadata(id, html, url, author)
    suspend fun open(id: String) = update(id) { it.copy(lastOpenedAtEpochSeconds = now()) }
    suspend fun progress(id: String, value: Float) {
        require(value.isFinite()); update(id) { it.copy(readingProgress = value.coerceIn(0f, 1f)) }
    }
    suspend fun archive(id: String) = db.withTransaction {
        val article = checkNotNull(db.articleDao().getById(id))
        val old = db.readerDao().metadata(id) ?: ReaderMetadata(id)
        val archiving = old.archivedAtEpochSeconds == null
        if (archiving && !article.isRead) {
            db.articleDao().markRead(id)
            if (!id.startsWith("legacy/")) db.pendingActionDao().insert(PendingActionEntity(articleId = id, actionType = "MarkRead"))
        }
        db.readerDao().putMetadata(old.copy(archivedAtEpochSeconds = if (archiving) now() else null))
    }
    suspend fun save(id: String) = update(id) { it.copy(savedAtEpochSeconds = if (it.savedAtEpochSeconds == null) now() else null) }
    suspend fun note(id: String, text: String) = annotation(id, "note", "", text)
    suspend fun highlight(id: String, quote: String, note: String = "") = annotation(id, "highlight", quote, note)
    private suspend fun annotation(id: String, kind: String, quote: String, note: String) {
        require((if (kind == "note") note else quote).isNotBlank())
        require(quote.length <= 10000 && note.length <= 10000)
        db.withTransaction {
            val article = checkNotNull(db.articleDao().getById(id)) { "文章尚未缓存" }
            if (kind == "highlight") require(ReaderHtml.text(article.contentHtml).contains(quote.trim())) { "高亮文字需要来自缓存正文" }
            db.readerDao().putAnnotation(ReaderAnnotation(UUID.randomUUID().toString(), id, kind, quote.trim(), note.trim(), "#f1d28a", now(), now()))
        }
    }
    suspend fun editAnnotation(id: String, quote: String, note: String) = db.withTransaction {
        val old = checkNotNull(db.readerDao().annotation(id))
        require((if (old.kind == "note") note else quote).isNotBlank())
        require(quote.length <= 10000 && note.length <= 10000)
        if (old.kind == "highlight") {
            val article = checkNotNull(db.articleDao().getById(old.articleId))
            require(ReaderHtml.text(article.contentHtml).contains(quote.trim())) { "高亮文字需要来自缓存正文" }
        }
        db.readerDao().putAnnotation(old.copy(quote = quote.trim(), note = note.trim(), updatedAtEpochSeconds = now()))
    }
    suspend fun deleteAnnotation(id: String) = db.readerDao().deleteAnnotation(id)
    suspend fun tags(id: String, names: List<String>) = db.withTransaction {
        check(db.articleDao().getById(id) != null)
        val values = names.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }
        require(values.size <= 32 && values.all { it.length <= 80 })
        db.readerDao().clearLinks(id)
        for (name in values) {
            val tag = ReaderTag("local/" + name.lowercase(Locale.ROOT), name, now(), now())
            db.readerDao().putTag(tag); db.readerDao().putLink(ReaderArticleTag(id, tag.id))
        }
    }
    private suspend fun update(id: String, block: (ReaderMetadata) -> ReaderMetadata) = db.withTransaction {
        check(db.articleDao().getById(id) != null) { "文章尚未缓存" }
        db.readerDao().putMetadata(block(db.readerDao().metadata(id) ?: ReaderMetadata(id)))
    }
    /** Copies only owned article data. Tokens/cookies and the source database are never modified. */
    suspend fun importLegacy(): Int = withContext(Dispatchers.IO) {
        val file = context.getDatabasePath("fluxa.db")
        check(file.isFile) { "当前安装渠道没有旧本地数据库" }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { old ->
            check(old.version in 1..3) { "旧数据库版本尚不支持，原文件未修改" }
            var count = 0
            db.withTransaction {
                val target = db.openHelper.writableDatabase
                ReaderSchema.copyLegacy({ old.rawQuery(it, null) }, target, "legacy/")
                old.rawQuery("SELECT * FROM articles", null).use { c ->
                    with(ReaderSchema) {
                        while (c.moveToNext()) {
                            val id = "legacy/" + c.string("id")
                            if (db.articleDao().getById(id) == null) {
                                fun num(name: String): Long = c.getColumnIndex(name).let { if (it < 0 || c.isNull(it)) 0 else c.getLong(it) }
                                db.articleDao().upsertAll(listOf(ArticleEntity(id, c.string("title"), c.string("feedName"),
                                    num("publishedAtEpochSeconds"), num("isRead") == 1L, num("isStarred") == 1L,
                                    c.string("contentHtml"), "legacy/" + c.string("source"), c.string("tags"))))
                                count++
                            }
                        }
                    }
                }
            }
            count
        }
    }
    private fun now() = System.currentTimeMillis() / 1000
}

fun FluxaDatabase.observeReaderArticles() = combine(articleDao().observeAll(), readerDao().observeMetadata(),
    readerDao().observeTags(), readerDao().observeLinks(), readerDao().observeAllAnnotations()) { rows, metadata, tags, links, annotations ->
    val states = metadata.associateBy { it.articleId }
    val tagNames = tags.associate { it.id to it.name }
    val byArticle = links.groupBy { it.articleId }
    val counts = annotations.groupingBy { it.articleId }.eachCount()
    rows.map { item ->
        val m = states[item.id] ?: ReaderMetadata(item.id)
        val names = (byArticle[item.id].orEmpty().mapNotNull { tagNames[it.tagId] } +
            item.tags.split(',').filter { it.isNotBlank() && !it.contains("/state/") }.map { it.substringAfter("/label/", it) }).distinct()
        Article(item.id, item.title, item.feedName, Instant.ofEpochSecond(item.publishedAtEpochSeconds), item.isRead,
            item.isStarred, item.contentHtml, item.source, m.url, m.author, m.excerpt, m.wordCount, m.readingProgress,
            m.archivedAtEpochSeconds, m.savedAtEpochSeconds, m.lastOpenedAtEpochSeconds, names, counts[item.id] ?: 0)
    }
}


suspend fun FluxaDatabase.updateReaderMetadata(id: String, html: String, url: String, author: String) = withTransaction {
    val old = readerDao().metadata(id) ?: ReaderMetadata(id)
    val text = ReaderHtml.text(html)
    readerDao().putMetadata(old.copy(url = url.ifBlank { old.url }, author = author.ifBlank { old.author },
        excerpt = text.take(240), wordCount = ReaderHtml.wordCount(text)))
}
