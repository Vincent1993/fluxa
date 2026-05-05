package com.fluxa.app.data.repository

import com.fluxa.app.data.api.InoreaderApi
import com.fluxa.app.data.local.ArticleDao
import com.fluxa.app.data.local.ArticleEntity
import com.fluxa.app.data.local.PendingActionDao
import com.fluxa.app.data.local.PendingActionEntity
import com.fluxa.app.domain.model.Article
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InoreaderArticleRepository @Inject constructor(
    private val api: InoreaderApi,
    private val authRepository: AuthRepository,
    private val articleDao: ArticleDao,
    private val pendingActionDao: PendingActionDao
) : ArticleRepository {

    private var continuation: String? = null

    override fun getPagedArticles(): Flow<List<Article>> {
        return articleDao.observeAll().map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun refresh() {
        syncPendingActions()
        authRepository.refreshIfNeeded()
        continuation = null
        val response = api.getReadingStream(count = PAGE_SIZE, continuation = null)
        continuation = response.continuation
        articleDao.clearAll()
        articleDao.upsertAll(response.items.map { it.toEntity() })
    }

    override suspend fun loadMore() {
        val token = continuation ?: return
        authRepository.refreshIfNeeded()
        val response = api.getReadingStream(count = PAGE_SIZE, continuation = token)
        continuation = response.continuation
        articleDao.upsertAll(response.items.map { it.toEntity() })
    }

    override suspend fun markRead(id: String) {
        articleDao.markRead(id)
        executeOrQueue(id, PendingActionType.MarkRead) {
            api.editTag(itemId = id, addTag = READ_TAG)
        }
    }

    override suspend fun toggleStar(id: String) {
        val current = articleDao.getStarred(id) ?: false
        val next = !current
        articleDao.setStarred(id, next)
        executeOrQueue(id, PendingActionType.ToggleStar, next.toString()) {
            if (next) api.editTag(itemId = id, addTag = STARRED_TAG)
            else api.editTag(itemId = id, removeTag = STARRED_TAG)
        }
    }

    override suspend fun addHighlight(articleId: String, text: String) {
        executeOrQueue(articleId, PendingActionType.AddHighlight, text) {
            api.editTag(itemId = articleId, addTag = HIGHLIGHT_TAG, annotation = text)
        }
    }

    override suspend fun addNote(articleId: String, note: String) {
        executeOrQueue(articleId, PendingActionType.AddNote, note) {
            api.editTag(itemId = articleId, addTag = NOTE_TAG, annotation = note)
        }
    }

    override suspend fun archive(articleId: String) {
        articleDao.markRead(articleId)
        executeOrQueue(articleId, PendingActionType.Archive) {
            api.editTag(itemId = articleId, removeTag = READING_LIST_TAG)
        }
    }

    override suspend fun saveForLater(articleId: String) {
        executeOrQueue(articleId, PendingActionType.SaveForLater) {
            api.editTag(itemId = articleId, addTag = SAVED_TAG)
        }
    }

    override suspend fun syncPendingActions() {
        val actions = pendingActionDao.getAllOrdered()
        for (action in actions) {
            val ok = runCatching { replay(action) }.isSuccess
            if (ok) pendingActionDao.deleteById(action.id) else break
        }
    }

    private suspend fun executeOrQueue(articleId: String, type: PendingActionType, payload: String = "", block: suspend () -> Unit) {
        val success = runCatching {
            authRepository.refreshIfNeeded()
            block()
        }.isSuccess
        if (!success) {
            pendingActionDao.insert(PendingActionEntity(articleId = articleId, actionType = type.name, payload = payload))
        }
    }

    private suspend fun replay(action: PendingActionEntity) {
        authRepository.refreshIfNeeded()
        when (PendingActionType.valueOf(action.actionType)) {
            PendingActionType.MarkRead -> api.editTag(itemId = action.articleId, addTag = READ_TAG)
            PendingActionType.ToggleStar -> {
                if (action.payload.toBoolean()) api.editTag(itemId = action.articleId, addTag = STARRED_TAG)
                else api.editTag(itemId = action.articleId, removeTag = STARRED_TAG)
            }
            PendingActionType.AddHighlight -> api.editTag(itemId = action.articleId, addTag = HIGHLIGHT_TAG, annotation = action.payload)
            PendingActionType.AddNote -> api.editTag(itemId = action.articleId, addTag = NOTE_TAG, annotation = action.payload)
            PendingActionType.Archive -> api.editTag(itemId = action.articleId, removeTag = READING_LIST_TAG)
            PendingActionType.SaveForLater -> api.editTag(itemId = action.articleId, addTag = SAVED_TAG)
        }
    }

    private enum class PendingActionType { MarkRead, ToggleStar, AddHighlight, AddNote, Archive, SaveForLater }

    private fun com.fluxa.app.data.api.model.StreamItem.toEntity(): ArticleEntity = ArticleEntity(
        id = id,
        title = title.orEmpty().ifBlank { "(无标题)" },
        feedName = origin?.title.orEmpty().ifBlank { "Inoreader" },
        publishedAtEpochSeconds = published ?: (System.currentTimeMillis() / 1000),
        isRead = categories?.contains(READ_TAG) == true,
        isStarred = categories?.contains(STARRED_TAG) == true,
        contentHtml = summary?.content.orEmpty()
    )

    private fun ArticleEntity.toDomain(): Article = Article(
        id = id,
        title = title,
        feedName = feedName,
        publishedAt = Instant.ofEpochSecond(publishedAtEpochSeconds),
        isRead = isRead,
        isStarred = isStarred,
        contentHtml = contentHtml
    )

    private companion object {
        const val PAGE_SIZE = 20
        const val READ_TAG = "user/-/state/com.google/read"
        const val STARRED_TAG = "user/-/state/com.google/starred"
        const val HIGHLIGHT_TAG = "user/-/state/com.fluxa/highlight"
        const val NOTE_TAG = "user/-/state/com.fluxa/note"
        const val READING_LIST_TAG = "user/-/state/com.google/reading-list"
        const val SAVED_TAG = "user/-/state/com.fluxa/save-for-later"
    }
}
