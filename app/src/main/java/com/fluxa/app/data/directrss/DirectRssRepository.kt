package com.fluxa.app.data.directrss

import androidx.room.withTransaction
import com.fluxa.app.domain.model.Article
import com.fluxa.app.domain.model.Subscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.time.Instant

class DirectRssRepository(
    private val db: DirectRssDatabase,
    private val client: DirectRssClient = DirectRssClient(),
    private val images: DirectImageCache = DirectImageCache(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val offline: LocalOfflineSettings? = null,
    private val notifications: LocalFeedNotifications? = null
) {
    private val network = Mutex()
    fun articles() = combine(db.dao().observeArticles(), db.dao().observeSubscriptions()) { rows, feeds -> rows.map { row ->
        Article(row.id, row.title, feeds.firstOrNull { it.id == row.source }?.let { it.alias ?: it.title } ?: if (row.source == SAVED_PAGE_SOURCE) "稍后读" else "本地 RSS",
            Instant.ofEpochSecond(row.published), row.isRead, row.isStarred, row.body, row.source,
            row.originalUrl, row.summary, row.readMinutes, sourceNote = "", readLater = row.readLater || row.source == SAVED_PAGE_SOURCE,
            categories = row.categories.lines().filter { it.isNotBlank() }, author = row.author)
    } }
    fun subscriptions() = db.dao().observeSubscriptions().map { rows -> rows.map {
        Subscription(it.id, it.alias ?: it.title, it.url, true, it.dailyOnly, it.lastSuccess, it.status,
            it.alias, it.folderId, it.paused, it.archived)
    } }
    fun notes(id: String) = db.dao().observeNotes(id)
    suspend fun saveNote(note: DirectReaderNote) = db.withTransaction {
        ReaderQuotes.validate(note)
        require(db.dao().article(note.articleId) != null) { "仅支持已保存的本地文章" }
        val existing = db.dao().note(note.id)
        require(existing == null || existing.articleId == note.articleId) { "笔记归属冲突" }
        require(existing != null || (db.dao().noteCount() < 5000 && db.dao().articleNoteCount(note.articleId) < 100)) { "笔记数量已达上限" }
        db.dao().putNote(note)
    }
    suspend fun deleteNote(id: String, articleId: String) = db.withTransaction {
        require(db.dao().note(id)?.articleId == articleId) { "笔记归属冲突" }
        db.dao().deleteNote(id)
    }
    fun readingMode(source: String) = db.dao().observeMode(source).map {
        runCatching { LocalReadingMode.valueOf(it?.mode.orEmpty()) }.getOrDefault(LocalReadingMode.RSS)
    }
    suspend fun setReadingMode(source: String, mode: LocalReadingMode) {
        require(db.dao().subscription(source) != null) { "本地订阅不存在" }
        db.dao().putMode(DirectReadingMode(source, mode.name))
    }
    /** A cancelled caller cannot commit. INSERT IGNORE makes concurrent successful extraction idempotent. */
    suspend fun fullBody(id: String, isCurrent: () -> Boolean = { true }): String {
        db.dao().fullBody(id)?.let { return it.body }
        val article = requireNotNull(db.dao().article(id))
        require(article.source != SAVED_PAGE_SOURCE)
        val page = extractPage(article.originalUrl)
        val cached = withContext(Dispatchers.IO) { images.cache(page.body, "", DirectImageCache.Budget()) }
        coroutineContext.ensureActive()
        val result = db.withTransaction {
            coroutineContext.ensureActive()
            if (!isCurrent()) throw CancellationException("Obsolete extraction")
            val latest = db.dao().article(id)
            require(latest != null && latest.originalUrl == article.originalUrl && latest.source == article.source) { "文章已变更" }
            db.dao().putFullBody(DirectFullBody(id, cached.html))
            val result = requireNotNull(db.dao().fullBody(id)).body
            coroutineContext.ensureActive()
            if (!isCurrent()) throw CancellationException("Obsolete extraction")
            result
        }
        trimPayloads()
        return result
    }
    fun folders() = db.dao().observeFolders()
    suspend fun createFolder(raw: String): String = db.withTransaction {
        val name = raw.trim()
        require(name.isNotEmpty() && name.length <= 80 && name.none { it.isISOControl() }) { "文件夹名称需为 1–80 个有效字符" }
        val folder = DirectFolder(stableHash(name), name)
        db.dao().putFolder(folder)
        requireNotNull(db.dao().folderNamed(name)).id
    }
    suspend fun organize(id: String, alias: String?, folderId: String?, paused: Boolean, archived: Boolean) = network.withLock {
        db.withTransaction {
            val feed = requireNotNull(db.dao().subscription(id)) { "本地订阅不存在" }
            require(folderId == null || db.dao().folder(folderId) != null) { "文件夹不存在" }
            val name = alias?.trim()?.takeIf { it.isNotEmpty() }
            require(name == null || (name.length <= 200 && name.none { it.isISOControl() })) { "别名过长或包含控制字符" }
            db.dao().putSubscription(feed.copy(alias = name, folderId = folderId, paused = paused, archived = archived))
        }
    }
    suspend fun discover(url: String) = withContext(Dispatchers.IO) { client.discover(url) }
    suspend fun preview(url: String) = withContext(Dispatchers.IO) { client.fetch(url) }
    suspend fun localSources() = db.dao().subscriptions()
    suspend fun exportOpml(): ByteArray = db.withTransaction {
        DirectOpml.export(db.dao().subscriptions(), db.dao().folders())
    }
    /** Re-check duplicates under the same lock as manual add/refresh. Never refresh existing rows. */
    suspend fun importRecord(record: OpmlRecord): Boolean = network.withLock {
        require(record.error == null) { "不能导入无效记录" }
        val id = subscriptionIdentity(record.url, record.daily)
        if (db.dao().subscription(id) != null) return@withLock false
        val url = if (isIfanrFeed(record.url)) IFANR_FEED else requireNotNull(safeUrl(record.url))
        val feed = DirectSubscription(id, record.name, url, record.daily, alias = record.name.takeIf { it.length <= 200 })
        refreshOne(feed, DirectImageCache.Budget(), record.folder)
        true
    }
    suspend fun extractPage(url: String) = withContext(Dispatchers.IO) { client.extractPage(url) }
    suspend fun savePage(page: SavedPage): String = network.withLock {
        val url = requireNotNull(safeUrl(page.url))
        val id = LOCAL_PREFIX + "page:" + stableHash(url)
        val previous = db.dao().article(id)
        val cached = withContext(Dispatchers.IO) { images.cache(page.body, previous?.body.orEmpty(), DirectImageCache.Budget()) }
        coroutineContext.ensureActive()
        val text = DirectHtml.text(page.body)
        db.withTransaction {
            coroutineContext.ensureActive()
            val current = db.dao().article(id)
            db.dao().putArticle(DirectArticle(id, url, SAVED_PAGE_SOURCE, page.title, current?.published ?: clock() / 1000,
                cached.html, url, current?.isRead ?: false, current?.isStarred ?: false,
                cached.cached, cached.total, summary = text.take(140), readMinutes = maxOf(1, (text.length + 399) / 400)))
        }
        trimPayloads()
        id
    }
    suspend fun hasSubscriptions() = db.dao().subscriptions().isNotEmpty()
    suspend fun addSubscription(raw: String, dailyOnly: Boolean = false) = network.withLock {
        val url = requireNotNull(safeUrl(raw)) { "请输入无凭据、无片段的 HTTP(S) RSS 地址" }
        require(!dailyOnly || isIfanrFeed(url)) { "早报专选仅支持爱范儿官方 /feed" }
        val canonical = if (isIfanrFeed(url)) IFANR_FEED else url
        val id = subscriptionIdentity(canonical, dailyOnly)
        val existing = db.dao().subscription(id)
        val feed = existing ?: DirectSubscription(id, if (dailyOnly) "爱范儿早报" else canonical, canonical, dailyOnly)
        // No row is created/reactivated until a valid feed has been fetched and prepared.
        refreshOne(feed.copy(archived = false, paused = false), DirectImageCache.Budget())
    }
    private var lastForegroundAttempt = Long.MIN_VALUE
    suspend fun refreshStale() = refreshInternal(null, staleOnly = true)
    suspend fun refreshOnResume() {
        releaseNotifications()
        val now = clock()
        synchronized(this) {
            if (lastForegroundAttempt != Long.MIN_VALUE && now - lastForegroundAttempt < 60_000) return
            lastForegroundAttempt = now
        }
        for (attempt in 0..2) {
            try { refreshStale(); return }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (attempt == 2 || !isRetryableDirectFailure(failure)) throw failure
                kotlinx.coroutines.delay(2_000L shl attempt)
            }
        }
    }
    suspend fun refresh(source: String? = null) = refreshInternal(source, staleOnly = false)
    private suspend fun refreshInternal(source: String?, staleOnly: Boolean) = network.withLock {
        releaseNotifications()
        val feeds = db.dao().subscriptions().filter { !it.paused && !it.archived && (source == null || it.id == source) &&
            (!staleOnly || (offline?.automaticRetryAllowed(it.id) != false &&
                (it.lastSuccess == 0L || clock() - it.lastSuccess >= 3_600_000))) }
        val budget = DirectImageCache.Budget()
        val errors = mutableListOf<String>()
        var retryableRefreshFailure = false
        for (feed in feeds) {
            try { refreshOne(feed, budget) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                errors += "${feed.title}: ${e.message}"
                retryableRefreshFailure = retryableRefreshFailure || isRetryableDirectFailure(e)
            }
        }
        trimPayloads()
        if (errors.isNotEmpty()) throw DirectRefreshException(errors.joinToString("；"), retryableRefreshFailure)
    }
    private suspend fun refreshOne(feed: DirectSubscription, budget: DirectImageCache.Budget, importedFolder: String? = null) {
        val attemptedAt = Instant.ofEpochMilli(clock())
        try {
            db.dao().status(feed.id, "尝试 $attemptedAt · 刷新中…")
            val response = withContext(Dispatchers.IO) { client.fetch(feed.url) }
            val selected = response.items.filter { !feed.dailyOnly || isDaily(it) }
            val prepared = selected.map { item ->
                val id = LOCAL_PREFIX + stableHash(feed.id + "|" + item.key)
                val previous = db.dao().article(id)
                val cached = withContext(Dispatchers.IO) { images.cache(item.body, previous?.body.orEmpty(), budget) }
                val text = withContext(Dispatchers.Default) { DirectHtml.text(item.body).removePrefix(item.title).trim() }
                DirectArticle(id, item.key, feed.id, item.title, item.published, cached.html, item.url,
                    cachedImages = cached.cached, totalImages = cached.total,
                    categories = item.categories.joinToString("\n"), author = item.author, summary = text.take(140),
                    readMinutes = maxOf(1, (text.length + 399) / 400))
            }
            coroutineContext.ensureActive()
            val newRows = mutableListOf<DirectArticle>()
            db.withTransaction {
                coroutineContext.ensureActive()
                val folderId = importedFolder?.let { name ->
                    require(name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() })
                    db.dao().putFolder(DirectFolder(stableHash(name), name))
                    requireNotNull(db.dao().folderNamed(name)).id
                } ?: feed.folderId
                prepared.forEach { row ->
                    // Read state inside the transaction, not the pre-network snapshot.
                    val current = db.dao().article(row.id)
                    if (current == null) newRows += row
                    // The optional body belongs to this URL, not merely the publisher's stable GUID.
                    // Invalidate under the same transaction as the URL change; extraction rechecks it here too.
                    if (current != null && current.originalUrl != row.originalUrl) db.dao().deleteFullBody(row.id)
                    db.dao().putArticle(row.copy(isRead = current?.isRead ?: false, isStarred = current?.isStarred ?: false, readLater = current?.readLater ?: false))
                }
                db.dao().putSubscription(feed.copy(folderId = folderId, title = if (feed.dailyOnly) "爱范儿早报" else response.title,
                    lastSuccess = clock(), status = "尝试 $attemptedAt · 已刷新 ${prepared.size} 篇"))
            }
            offline?.recordFailure(feed.id, permanent = false)
            trimPayloads()
            try {
                val eligible = db.dao().subscriptions()
                withContext(Dispatchers.IO) { notifications?.successful(feed, newRows, eligible) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Notification delivery must not turn a committed refresh into a retry/flood. */ }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val retryable = isRetryableDirectFailure(e)
            val message = "尝试 $attemptedAt · ${if (retryable) "暂时失败" else "源或响应无效"}：${e.message ?: "网络或 Feed 错误"}"
            db.dao().status(feed.id, message)
            offline?.recordFailure(feed.id, permanent = !retryable)
            throw DirectRefreshException(message, retryable)
        }
    }
    suspend fun notificationSource(id: String): Boolean = id.length <= 200 && db.dao().subscription(id)?.let {
        it.id.startsWith(LOCAL_PREFIX) && it.id != SAVED_PAGE_SOURCE && !it.archived
    } == true
    private suspend fun releaseNotifications() {
        try {
            val eligible = db.dao().subscriptions()
            withContext(Dispatchers.IO) { notifications?.release(eligible) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Best effort, no change to refresh/account state. */ }
    }
    suspend fun markRead(id: String) = setReadState(id, true)
    suspend fun setReadState(id: String, read: Boolean) = db.withTransaction {
        val current = db.dao().article(id) ?: return@withTransaction
        if (current.isRead != read) {
            // Unknown/pre-upgrade ages are deliberately protected, never guessed from publish date.
            offline?.recordRead(id, read, clock())
            db.dao().setReadState(id, read)
            db.dao().putRevision(DirectRevision(id, java.util.UUID.randomUUID().toString()))
        }
    }
    suspend fun setReadLater(id: String, value: Boolean) = db.withTransaction {
        val row = requireNotNull(db.dao().article(id)) { "文章不存在" }
        require(row.source != SAVED_PAGE_SOURCE) { "保存网页始终保留在稍后读" }
        if (row.readLater != value) {
            db.dao().setReadLater(id, value)
            db.dao().putRevision(DirectRevision(id, java.util.UUID.randomUUID().toString()))
        }
    }
    suspend fun changeFlag(id: String, flag: com.fluxa.app.data.repository.ArticleFlag, value: Boolean): com.fluxa.app.data.repository.FlagReceipt? = db.withTransaction {
        val row = db.dao().article(id) ?: return@withTransaction null
        val previous = when (flag) {
            com.fluxa.app.data.repository.ArticleFlag.Read -> row.isRead
            com.fluxa.app.data.repository.ArticleFlag.Star -> row.isStarred
            com.fluxa.app.data.repository.ArticleFlag.ReadLater -> row.readLater
        }
        if (previous == value) return@withTransaction null
        when (flag) {
            com.fluxa.app.data.repository.ArticleFlag.Read -> setReadState(id, value)
            com.fluxa.app.data.repository.ArticleFlag.Star -> toggleStar(id) // transaction captured the absolute state
            com.fluxa.app.data.repository.ArticleFlag.ReadLater -> setReadLater(id, value)
        }
        com.fluxa.app.data.repository.FlagReceipt(id, flag, previous, value, requireNotNull(db.dao().revision(id)).revision)
    }
    suspend fun undoFlag(receipt: com.fluxa.app.data.repository.FlagReceipt): Boolean = db.withTransaction {
        if (db.dao().revision(receipt.id)?.revision != receipt.revision) return@withTransaction false
        changeFlag(receipt.id, receipt.flag, receipt.previous) != null
    }
    suspend fun toggleStar(id: String) = db.withTransaction {
        db.dao().toggleStar(id)
        db.dao().putRevision(DirectRevision(id, java.util.UUID.randomUUID().toString()))
    }
    suspend fun bulkRead(ids: List<String>): com.fluxa.app.data.repository.ReadReceipt = db.withTransaction {
        val changes = mutableListOf<com.fluxa.app.data.repository.ReadChange>()
        for (id in ids.distinct()) {
            val current = db.dao().article(id) ?: continue
            if (!current.isRead) {
                setReadState(id, true)
                changes += com.fluxa.app.data.repository.ReadChange(id, false, requireNotNull(db.dao().revision(id)).revision)
            }
        }
        com.fluxa.app.data.repository.ReadReceipt(changes)
    }
    suspend fun undoRead(receipt: com.fluxa.app.data.repository.ReadReceipt): com.fluxa.app.data.repository.ReadReceipt = db.withTransaction {
        val restored = mutableListOf<com.fluxa.app.data.repository.ReadChange>()
        for (change in receipt.changes) {
            if (db.dao().revision(change.id)?.revision == change.revision) {
                setReadState(change.id, change.previous)
                restored += change
            }
        }
        com.fluxa.app.data.repository.ReadReceipt(restored)
    }

    fun offlinePolicy() = offline?.policy() ?: LocalOfflinePolicy()
    fun updateOfflinePolicy(policy: LocalOfflinePolicy) { checkNotNull(offline).update(policy) }
    suspend fun pinArticle(id: String, pinned: Boolean) {
        db.withTransaction {
            require(db.dao().article(id) != null)
            checkNotNull(offline).pinArticle(id, pinned)
        }
        if (pinned) downloadArticles(listOf(id))
    }
    suspend fun pinFolder(id: String, pinned: Boolean) {
        db.withTransaction {
            require(db.dao().folder(id) != null)
            checkNotNull(offline).pinFolder(id, pinned)
        }
        if (pinned) {
            val sources = db.dao().subscriptions().filter { it.folderId == id }.map { it.id }.toSet()
            downloadArticles(db.dao().cacheEntries().filter { it.source in sources }.map { it.id })
        }
    }
    /** One shared budget; each article commits independently. Cancellation never discards prior successes. */
    private suspend fun downloadArticles(ids: List<String>) = network.withLock {
        val budget = DirectImageCache.Budget()
        val failures = mutableListOf<String>()
        for (id in ids) {
            coroutineContext.ensureActive()
            try {
                val row = db.dao().article(id) ?: continue
                val cached = withContext(Dispatchers.IO) { images.cache(row.body, row.body, budget) }
                db.withTransaction {
                    coroutineContext.ensureActive()
                    val current = db.dao().article(id)
                    if (current != null && current.originalUrl == row.originalUrl) {
                        db.dao().putArticle(current.copy(body = cached.html, cachedImages = cached.cached, totalImages = cached.total))
                    }
                }
                if (cached.cached < cached.total) failures += "部分图片尚未下载（网络策略或预算）"
                // Commit RSS success before optional extraction; a full-text failure must not discard images.
                if (db.dao().mode(row.source)?.mode == LocalReadingMode.FULL_TEXT.name) {
                    val existing = db.dao().fullBody(id)?.body
                    val body = existing ?: extractPage(row.originalUrl).body
                    val extracted = withContext(Dispatchers.IO) { images.cache(body, body, budget) }
                    db.withTransaction {
                        coroutineContext.ensureActive()
                        if (db.dao().article(id)?.originalUrl == row.originalUrl) {
                            db.dao().putFullBody(DirectFullBody(id, extracted.html))
                            db.dao().updateFullBody(id, extracted.html)
                        }
                    }
                    if (extracted.cached < extracted.total) failures += "全文部分图片尚未下载"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failures += failure.message ?: "下载失败" }
        }
        trimPayloads()
        if (failures.isNotEmpty()) throw IllegalStateException("已保留成功内容；${failures.size} 项未完成：${failures.distinct().take(3).joinToString("；")}")
    }

    /** Soft payload budget, not physical SQLite file size. RSS prose is never removed. */
    suspend fun trimPayloads(evict: Boolean = true): OfflineUsage = db.withTransaction {
        val policy = offlinePolicy()
        val folders = db.dao().subscriptions().associate { it.id to it.folderId }
        val rows = db.dao().cacheEntries()
        val notedArticles = db.dao().allNotes().mapTo(hashSetOf()) { it.articleId }
        val cutoff = clock() - policy.retainedReadDays * 86_400_000L
        fun isProtected(row: DirectCacheEntry): Boolean {
            val readAt = offline?.readAt(row.id) ?: 0
            return !row.isRead || row.isStarred || row.readLater || row.source == SAVED_PAGE_SOURCE || row.id in notedArticles ||
                offline?.articlePinned(row.id) == true || offline?.folderPinned(folders[row.source]) == true ||
                readAt == 0L || readAt >= cutoff
        }
        val orphanBytes = db.dao().orphanPayloadBytes()
        var bytes = rows.sumOf { it.bytes } + orphanBytes
        val protectedBytes = orphanBytes + rows.filter(::isProtected).sumOf { it.bytes }
        val eligible = rows.filter { evict && !isProtected(it) }.sortedWith(compareBy<DirectCacheEntry> { offline?.readAt(it.id) ?: 0 }.thenBy { it.id })
        // Transaction current flags, notes, pins and ages are used, never the pre-network snapshot.
        for (row in eligible) {
            if (bytes <= policy.capacityBytes) break
            if (isProtected(row)) continue
            val article = db.dao().article(row.id) ?: continue
            val full = db.dao().fullBody(row.id)?.body
            val body = withoutEmbeddedImages(article.body)
            val extracted = full?.let(::withoutEmbeddedImages)
            bytes -= payloadBytes(article.body, full.orEmpty()) - payloadBytes(body, extracted.orEmpty())
            if (body != article.body) db.dao().putArticle(article.copy(body = body, cachedImages = 0))
            if (extracted != null && extracted != full) db.dao().updateFullBody(row.id, extracted)
        }
        for (row in eligible) {
            if (bytes <= policy.capacityBytes) break
            if (isProtected(row)) continue
            db.dao().fullBody(row.id)?.let { bytes -= payloadBytes(it.body); db.dao().deleteFullBody(row.id) }
        }
        OfflineUsage(bytes, policy.capacityBytes, protectedBytes)
    }
}
