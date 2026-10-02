package com.fluxa.app.data.repository

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

    override fun getPagedArticles() = db.articleDao().observeAll().map { items ->
        items.map { Article(it.id, it.title, it.feedName,
            Instant.ofEpochSecond(it.publishedAtEpochSeconds), it.isRead, it.isStarred,
            it.contentHtml, it.source) }
    }
    override fun observeSubscriptions() = db.subscriptionDao().observeAll().map { items ->
        items.map { Subscription(it.id, it.title, it.url) }
    }
    override fun observePendingCount() = db.pendingActionDao().observeCount()

    override suspend fun refresh() = network.withLock { refreshLocked() }
    override suspend fun selectSource(sourceId: String?) = network.withLock {
        source = sourceId
        refreshLocked()
    }
    private suspend fun refreshLocked() {
        requireSession()
        refreshFeeds()
        replayForRefresh()
        for (feed in selectedFeeds()) fetchPage(feed, 1)
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
        val response = api.stories(feed.id, page)
        if (response.code < 0) throw NewsBlurApiException("获取文章失败，请检查登录状态")
        db.withTransaction {
            val pending = db.pendingActionDao().getAllOrdered().groupBy { it.articleId }
            val articles = response.stories.map { story ->
                var article = ArticleEntity(story.hash, story.title.ifBlank { "（无标题）" },
                    feed.title, story.timestamp.toDoubleOrNull()?.toLong() ?: 0L,
                    story.read == 1, story.starred, story.content, story.feedId.toString())
                pending[story.hash].orEmpty().forEach { action ->
                    article = when (action.actionType) {
                        "MarkRead" -> article.copy(isRead = true)
                        "ToggleStar" -> article.copy(isStarred = action.payload.toBoolean())
                        else -> article
                    }
                }
                article
            }
            db.articleDao().upsertAll(articles)
            db.syncStateDao().upsert(SyncStateEntity(feed.id,
                if (response.stories.isEmpty()) null else (page + 1).toString(),
                System.currentTimeMillis()))
        }
    }

    override suspend fun markRead(id: String) {
        db.withTransaction {
            val article = db.articleDao().getById(id) ?: return@withTransaction
            if (!article.isRead) {
                db.articleDao().markRead(id)
                db.pendingActionDao().insert(PendingActionEntity(articleId = id, actionType = "MarkRead"))
            }
        }
        // Follow NewsBlur's guidance: batch reads on refresh/manual sync rather
        // than firing a network request for every article opened.
        if (db.pendingActionDao().getAllOrdered().count { it.actionType == "MarkRead" } >= 5) trySync()
    }
    override suspend fun toggleStar(id: String) {
        db.withTransaction {
            val article = db.articleDao().getById(id) ?: return@withTransaction
            db.articleDao().setStarred(id, !article.isStarred)
            db.pendingActionDao().insert(PendingActionEntity(articleId = id,
                actionType = "ToggleStar", payload = (!article.isStarred).toString()))
        }
        trySync()
    }
    private suspend fun trySync() {
        try { syncPendingActions() }
        catch (e: NewsBlurApiException) { throw e } // Business rejection must be visible; the queue remains intact.
        catch (_: IOException) { /* Queue remains durable. */ }
        catch (_: HttpException) { /* Retry after login/network/rate limits recover. */ }
    }
    override suspend fun syncPendingActions() = network.withLock {
        requireSession()
        replayPending()
    }
    private suspend fun replayForRefresh() {
        try { replayPending() }
        catch (e: HttpException) { if (e.code() != 429 && e.code() < 500) throw e }
    }
    private suspend fun replayPending() {
        val actions = db.pendingActionDao().getAllOrdered()
        val reads = actions.filter { it.actionType == "MarkRead" }
        for (batch in reads.chunked(5)) {
            api.markRead(batch.map { it.articleId }).requireSuccess()
            db.withTransaction { batch.forEach { db.pendingActionDao().deleteById(it.id) } }
        }
        for (action in actions.filter { it.actionType == "ToggleStar" }) {
            if (action.payload.toBoolean()) api.star(action.articleId).requireSuccess()
            else api.unstar(action.articleId).requireSuccess()
            db.pendingActionDao().deleteById(action.id)
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
