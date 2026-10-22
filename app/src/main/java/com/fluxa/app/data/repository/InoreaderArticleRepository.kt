package com.fluxa.app.data.repository

import androidx.room.withTransaction
import com.fluxa.app.data.api.InoreaderApi
import com.fluxa.app.data.api.model.StreamItem
import com.fluxa.app.data.local.*
import com.fluxa.app.domain.model.Article
import com.fluxa.app.domain.model.Subscription
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.HttpException

@Singleton
class InoreaderArticleRepository @Inject constructor(
    private val api: InoreaderApi,
    private val authRepository: AuthRepository,
    private val db: FluxaDatabase
) : ArticleRepository {
    private val network = Mutex()
    private var stream = READING_LIST

    override fun getPagedArticles() = db.articleDao().observeAll().map { items ->
        items.map { Article(it.id, it.title, it.feedName,
            Instant.ofEpochSecond(it.publishedAtEpochSeconds), it.isRead, it.isStarred,
            it.contentHtml, it.source) }
    }
    override fun observeSubscriptions() = db.subscriptionDao().observeAll().map { items ->
        items.map { Subscription(it.id, it.title, it.url) }
    }
    override fun observePendingCount() = db.pendingActionDao().observeCount()

    override suspend fun refresh() = network.withLock {
        requireSession()
        replayForRefresh()
        fetchPage(null)
    }

    override suspend fun loadMore() = network.withLock {
        val cursor = db.syncStateDao().getBySource(stream)?.cursor ?: return@withLock
        requireSession()
        replayForRefresh()
        fetchPage(cursor)
    }

    override suspend fun selectSource(sourceId: String?) = network.withLock {
        stream = sourceId ?: READING_LIST
        requireSession()
        replayForRefresh()
        fetchPage(null)
    }

    private suspend fun fetchPage(cursor: String?) {
        val response = api.getReadingStream(stream, 50, cursor)
        db.withTransaction {
            val pending = db.pendingActionDao().getAllOrdered().groupBy { it.articleId }
            val items = response.items.map { item ->
                var entity = item.toEntity()
                pending[item.id].orEmpty().forEach { action ->
                    entity = when (action.actionType) {
                        "MarkRead" -> entity.copy(isRead = true)
                        "ToggleStar" -> entity.copy(isStarred = action.payload.toBoolean())
                        else -> entity
                    }
                }
                entity
            }
            db.articleDao().upsertAll(items)
            db.syncStateDao().upsert(SyncStateEntity(stream, response.continuation,
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
        trySync()
    }

    override suspend fun toggleStar(id: String) {
        db.withTransaction {
            val article = db.articleDao().getById(id) ?: return@withTransaction
            val next = !article.isStarred
            db.articleDao().setStarred(id, next)
            db.pendingActionDao().insert(PendingActionEntity(articleId = id,
                actionType = "ToggleStar", payload = next.toString()))
        }
        trySync()
    }

    override suspend fun syncPendingActions() = network.withLock {
        requireSession()
        replayPending()
    }

    private suspend fun trySync() {
        try { syncPendingActions() }
        catch (_: IOException) { /* Durable queue remains for the next refresh. */ }
        catch (_: HttpException) { /* Includes expired sessions and rate limits. */ }
    }

    private suspend fun replayForRefresh() {
        // A tag write can be rate-limited separately from article reads. Pending
        // actions still overlay any downloaded article until replay succeeds.
        try { replayPending() }
        catch (e: HttpException) { if (e.code() != 429 && e.code() < 500) throw e }
    }

    private suspend fun replayPending() {
        for (action in db.pendingActionDao().getAllOrdered()) {
            when (action.actionType) {
                "MarkRead" -> api.editTag(action.articleId, addTag = READ_TAG)
                "ToggleStar" -> if (action.payload.toBoolean())
                    api.editTag(action.articleId, addTag = STARRED_TAG)
                    else api.editTag(action.articleId, removeTag = STARRED_TAG)
                else -> continue // Preserve unsupported actions from experimental PR #5.
            }
            db.pendingActionDao().deleteById(action.id)
        }
    }

    override suspend fun refreshSubscriptions() = network.withLock {
        requireSession()
        val items = api.getSubscriptions().subscriptions.map { SubscriptionEntity(it.id, it.title, it.url) }
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
            check(api.addSubscription("feed/$parsed").numResults > 0) { "未找到可订阅的 RSS" }
        }
        refreshSubscriptions()
    }

    private suspend fun requireSession() {
        if (authRepository.refreshIfNeeded().isNullOrBlank()) throw SessionRequiredException()
    }

    private fun StreamItem.toEntity() = ArticleEntity(id,
        title.orEmpty().ifBlank { "（无标题）" }, origin?.title.orEmpty().ifBlank { "Inoreader" },
        published ?: 0L, categories.orEmpty().hasStateTag(READ_TAG),
        categories.orEmpty().hasStateTag(STARRED_TAG), summary?.content.orEmpty(),
        origin?.streamId.orEmpty(), categories.orEmpty().joinToString(","))

    private fun List<String>.hasStateTag(tag: String) = any { category ->
        category.startsWith("user/") &&
            category.substringAfter('/').substringAfter('/') == tag.removePrefix("user/-/")
    }

    companion object {
        const val READING_LIST = "user/-/state/com.google/reading-list"
        const val READ_TAG = "user/-/state/com.google/read"
        const val STARRED_TAG = "user/-/state/com.google/starred"
    }
}

class SessionRequiredException : IOException("请先登录 Inoreader，再同步文章")
