package com.fluxa.app.data.repository

import com.fluxa.app.data.reader.observeReaderArticles
import com.fluxa.app.data.reader.updateReaderMetadata
import androidx.room.withTransaction
import com.fluxa.app.data.api.*
import com.fluxa.app.data.local.*
import com.fluxa.app.domain.model.Article
import com.fluxa.app.domain.model.Subscription
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewsBlurArticleRepository @Inject constructor(
    private val api: NewsBlurApi,
    private val auth: AuthRepository,
    private val db: FluxaDatabase
) : ArticleRepository {
    private val network = Mutex()
    private var source: String? = null

    override fun getPagedArticles() = db.observeReaderArticles()
    override fun observeSubscriptions() = db.subscriptionDao().observeAll().map { items ->
        items.map { Subscription(it.id, it.title, it.url) }
    }
    override fun observePendingCount() = db.pendingActionDao().observeCount()
    override fun observeAwaitingCount() = db.pendingActionDao().observeAwaitingCount()
    override fun observeSyncErrors() = db.pendingActionDao().observeErrors().map { rows -> rows.map { it.payload }.distinct() }

    override suspend fun refresh() = network.withLock { refreshLocked() }
    override suspend fun refreshInBackground() = network.withLock { refreshLocked(allFeeds = true) }
    override suspend fun selectSource(sourceId: String?) = network.withLock {
        source = sourceId
        refreshLocked()
    }
    private suspend fun refreshLocked(allFeeds: Boolean = false) {
        requireSession()
        refreshFeeds()
        replayForRefresh(if (allFeeds) 50 else 200)
        val feeds = if (allFeeds) db.subscriptionDao().getAll() else selectedFeeds()
        for (feed in feeds) fetchPage(feed, 1)
    }
    override suspend fun loadMore() = network.withLock {
        requireSession()
        replayForRefresh()
        for (feed in selectedFeeds()) {
            val page = db.syncStateDao().getBySource(feed.id)?.cursor?.toIntOrNull() ?: continue
            fetchPage(feed, page)
        }
    }
    private suspend fun selectedFeeds(): List<SubscriptionEntity> {
        val feeds = db.subscriptionDao().getAll()
        return source?.let { id -> feeds.filter { it.id == id } } ?: feeds
    }

    // Free accounts have restricted River of News pagination. Fetch individual
    // subscribed feeds and merge locally rather than depending on that paid API.
    private suspend fun fetchPage(feed: SubscriptionEntity, page: Int) {
        // Only an intent already acknowledged before this request may be confirmed by its response.
        val acknowledgedAtStart = db.pendingActionDao().getAllOrdered()
            .filter { it.actionType in setOf("AckRead", "AckStar") }.map { it.id }.toSet()
        val response = api.stories(feed.id, page)
        if (response.code < 0) throw NewsBlurApiException("获取文章失败，请检查登录状态")
        db.withTransaction {
            val pending = db.pendingActionDao().getAllOrdered().groupBy { it.articleId }
            val articles = response.stories.map { story ->
                val previous = db.articleDao().getById(story.hash)
                var article = ArticleEntity(story.hash, story.title.ifBlank { "（无标题）" },
                    feed.title, story.timestamp.toDoubleOrNull()?.toLong() ?: 0L,
                    story.read == 1, story.starred, story.content, story.feedId.toString(), story.tags.orEmpty().joinToString(","))
                pending[story.hash].orEmpty().forEach { action ->
                    article = when (action.actionType) {
                        "MarkRead", "SetRead", "AckRead", "RejectedRead" -> {
                            val desired = action.actionType == "MarkRead" || action.payload.toBoolean()
                            if (action.id in acknowledgedAtStart && story.read == (if (desired) 1 else 0))
                                db.pendingActionDao().deleteById(action.id)
                            article.copy(isRead = desired)
                        }
                        "ToggleStar", "AckStar", "RejectedStar" -> {
                            val desired = action.payload.toBoolean()
                            if (action.id in acknowledgedAtStart && story.starred == desired)
                                db.pendingActionDao().deleteById(action.id)
                            article.copy(isStarred = desired)
                        }
                        else -> article
                    }
                }
                if (previous != null && (previous.isRead != article.isRead || previous.isStarred != article.isStarred)) newRevision(story.hash)
                article
            }
            db.articleDao().upsertAll(articles)
            response.stories.forEach { db.updateReaderMetadata(it.hash, it.content, it.permalink.orEmpty(), it.author.orEmpty()) }
            db.syncStateDao().upsert(SyncStateEntity(feed.id,
                if (response.stories.isEmpty()) null else (page + 1).toString(),
                System.currentTimeMillis()))
        }
    }

    override suspend fun markRead(id: String) = setReadState(id, true)
    override suspend fun setReadState(id: String, read: Boolean) {
        db.withTransaction { applyRead(id, read) }
        if (db.pendingActionDao().getAllOrdered().count { it.actionType in setOf("MarkRead", "SetRead") } >= 5) trySync()
    }
    private suspend fun newRevision(id: String): String {
        val revision = java.util.UUID.randomUUID().toString()
        db.pendingActionDao().deleteField(id, listOf("LocalRevision"))
        db.pendingActionDao().insert(PendingActionEntity(articleId = id, actionType = "LocalRevision", payload = revision))
        return revision
    }
    private suspend fun applyRead(id: String, read: Boolean): ReadChange? {
        val article = db.articleDao().getById(id) ?: return null
        if (article.isRead == read) return null
        db.articleDao().setReadState(id, read)
        db.pendingActionDao().deleteField(id, listOf("MarkRead", "SetRead", "AckRead", "RejectedRead", "SyncErrorRead"))
        db.pendingActionDao().insert(PendingActionEntity(articleId = id, actionType = if (read) "MarkRead" else "SetRead", payload = read.toString()))
        return ReadChange(id, article.isRead, newRevision(id))
    }
    override suspend fun bulkRead(ids: List<String>): ReadReceipt = db.withTransaction {
        ReadReceipt(ids.distinct().mapNotNull { applyRead(it, true) })
    }
    override suspend fun undoRead(receipt: ReadReceipt): ReadReceipt = db.withTransaction {
        ReadReceipt(receipt.changes.mapNotNull { change ->
            if (db.pendingActionDao().revision(change.id) == change.revision) applyRead(change.id, change.previous) else null
        })
    }
    override suspend fun toggleStar(id: String) {
        db.withTransaction {
            val article = db.articleDao().getById(id) ?: return@withTransaction
            db.articleDao().setStarred(id, !article.isStarred)
            newRevision(id)
            db.pendingActionDao().deleteField(id, listOf("ToggleStar", "AckStar", "RejectedStar", "SyncErrorStar"))
            db.pendingActionDao().insert(PendingActionEntity(articleId = id,
                actionType = "ToggleStar", payload = (!article.isStarred).toString()))
        }
        trySync()
    }
    override suspend fun changeFlag(id: String, flag: ArticleFlag, value: Boolean): FlagReceipt? = db.withTransaction {
        require(flag != ArticleFlag.ReadLater) { "稍后读仅用于本地 RSS" }
        val row = db.articleDao().getById(id) ?: return@withTransaction null
        val previous = if (flag == ArticleFlag.Read) row.isRead else row.isStarred
        if (previous == value) return@withTransaction null
        val revision = if (flag == ArticleFlag.Read) requireNotNull(applyRead(id, value)).revision else {
            db.articleDao().setStarred(id, value)
            db.pendingActionDao().deleteField(id, listOf("ToggleStar", "AckStar", "RejectedStar", "SyncErrorStar"))
            db.pendingActionDao().insert(PendingActionEntity(articleId = id, actionType = "ToggleStar", payload = value.toString()))
            newRevision(id)
        }
        // Return the receipt after durable intent commit; normal sync sends the absolute value.
        FlagReceipt(id, flag, previous, value, revision)
    }
    override suspend fun undoFlag(receipt: FlagReceipt): Boolean = db.withTransaction {
        if (db.pendingActionDao().revision(receipt.id) != receipt.revision) return@withTransaction false
        changeFlag(receipt.id, receipt.flag, receipt.previous) != null
    }
    private suspend fun trySync() {
        try { network.withLock { requireSession(); replayPending() } }
        catch (e: NewsBlurApiException) { throw e } // Business rejection must be visible; the queue remains intact.
        catch (_: IOException) { /* Queue remains durable. */ }
        catch (_: HttpException) { /* Retry after login/network/rate limits recover. */ }
    }
    override suspend fun syncPendingActions() = network.withLock {
        requireSession()
        db.withTransaction {
            for (action in db.pendingActionDao().getAllOrdered()) when (action.actionType) {
                "RejectedRead" -> db.pendingActionDao().acknowledge(action.id, "SetRead")
                "RejectedStar" -> db.pendingActionDao().acknowledge(action.id, "ToggleStar")
            }
        }
        replayPending()
    }
    private suspend fun replayForRefresh(limit: Int = 200) {
        try { replayPending(limit) }
        catch (e: HttpException) { if (e.code() != 429 && e.code() < 500) throw e }
    }
    private suspend fun replayPending(limit: Int = 200) {
        val actions = db.withTransaction {
            val known = db.pendingActionDao().getAllOrdered().filter {
                it.actionType in setOf("MarkRead", "SetRead", "AckRead", "RejectedRead", "ToggleStar", "AckStar", "RejectedStar")
            }
            val latest = known.groupBy { it.articleId to if (it.actionType.endsWith("Star")) "star" else "read" }
                .values.map { group ->
                    group.dropLast(1).forEach { db.pendingActionDao().deleteById(it.id) }
                    group.last()
                }
            latest.sortedBy { it.id }.filter { it.actionType in setOf("MarkRead", "SetRead", "ToggleStar") }.take(limit)
        }
        val reads = actions.filter { it.actionType == "MarkRead" || (it.actionType == "SetRead" && it.payload.toBoolean()) }
        for (batch in reads.chunked(5)) {
            submit(batch, "Read") { api.markRead(batch.map { it.articleId }).requireSuccess() }
            db.withTransaction { batch.forEach {
                // Legacy MarkRead payload was empty. Preserve the absolute desired value on acknowledgement.
                if (it.actionType == "MarkRead") {
                    db.pendingActionDao().acknowledgeRead(it.id)
                } else db.pendingActionDao().acknowledge(it.id, "AckRead")
            } }
        }
        for (action in actions.filter { it.actionType == "SetRead" && !it.payload.toBoolean() }) {
            submit(listOf(action), "Read") { api.markUnread(action.articleId).requireSuccess() }
            db.pendingActionDao().acknowledge(action.id, "AckRead")
        }
        for (action in actions.filter { it.actionType == "ToggleStar" }) {
            submit(listOf(action), "Star") {
                if (action.payload.toBoolean()) api.star(action.articleId).requireSuccess()
                else api.unstar(action.articleId).requireSuccess()
            }
            db.pendingActionDao().acknowledge(action.id, "AckStar")
        }
    }
    /** Business rejection is not a transport retry. Hold the desired state for explicit manual retry. */
    private suspend fun submit(actions: List<PendingActionEntity>, field: String, block: suspend () -> Unit) {
        try {
            block()
            db.withTransaction {
                val current = db.pendingActionDao().getAllOrdered().map { it.id }.toSet()
                actions.filter { it.id in current }.forEach { db.pendingActionDao().deleteField(it.articleId, listOf("SyncError$field")) }
            }
        } catch (failure: NewsBlurApiException) {
            db.withTransaction {
                val current = db.pendingActionDao().getAllOrdered().map { it.id }.toSet()
                for (action in actions.filter { it.id in current }) {
                    if (action.actionType == "MarkRead") db.pendingActionDao().acknowledgeRead(action.id)
                    db.pendingActionDao().acknowledge(action.id, "Rejected$field")
                    db.pendingActionDao().deleteField(action.articleId, listOf("SyncError$field"))
                    db.pendingActionDao().insert(PendingActionEntity(articleId = action.articleId, actionType = "SyncError$field",
                        payload = failure.message ?: "服务端拒绝操作；请手动重试"))
                }
            }
            throw failure
        }
    }
    override suspend fun refreshSubscriptions() = network.withLock {
        requireSession()
        refreshFeeds()
    }
    private suspend fun refreshFeeds() {
        val result = api.feeds()
        if (result.code < 0) throw NewsBlurApiException("获取订阅失败，请重新登录")
        val items = result.feeds.filterValues { it.active }.map { (id, feed) ->
            SubscriptionEntity(id, feed.title, feed.url)
        }
        check(items.size <= 64) { "当前第一版支持最多 64 个启用订阅，请先选择较少订阅" }
        db.withTransaction {
            db.subscriptionDao().clearAll()
            db.subscriptionDao().upsertAll(items)
        }
    }
    override suspend fun addSubscription(url: String) {
        val parsed = url.trim().toHttpUrlOrNull()
        require(parsed != null) { "请输入有效的 HTTP 或 HTTPS RSS 地址" }
        network.withLock {
            requireSession()
            api.addFeed(parsed.toString()).requireSuccess()
            refreshFeeds()
        }
    }
    private suspend fun requireSession() {
        if (!auth.hasSession()) throw IOException("请先登录 NewsBlur，再同步文章")
    }
}
