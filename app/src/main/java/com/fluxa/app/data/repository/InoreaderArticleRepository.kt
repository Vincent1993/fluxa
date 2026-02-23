package com.fluxa.app.data.repository

import com.fluxa.app.data.api.InoreaderApi
import com.fluxa.app.data.local.ArticleDao
import com.fluxa.app.data.local.ArticleEntity
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
    private val articleDao: ArticleDao
) : ArticleRepository {

    private var continuation: String? = null

    override fun getPagedArticles(): Flow<List<Article>> {
        return articleDao.observeAll().map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun refresh() {
        authRepository.refreshIfNeeded()
        continuation = null
        val response = api.getReadingStream(count = PAGE_SIZE, continuation = null)
        continuation = response.continuation
        articleDao.clearAll()
        articleDao.upsertAll(response.items.map { it.toEntity() })
    }

    override suspend fun loadMore() {
        authRepository.refreshIfNeeded()
        val token = continuation ?: return
        val response = api.getReadingStream(count = PAGE_SIZE, continuation = token)
        continuation = response.continuation
        articleDao.upsertAll(response.items.map { it.toEntity() })
    }

    override suspend fun markRead(id: String) {
        authRepository.refreshIfNeeded()
        articleDao.markRead(id)
        api.editTag(itemId = id, addTag = READ_TAG)
    }

    override suspend fun toggleStar(id: String) {
        authRepository.refreshIfNeeded()
        val current = articleDao.getStarred(id) ?: false
        val next = !current
        articleDao.setStarred(id, next)
        if (next) {
            api.editTag(itemId = id, addTag = STARRED_TAG)
        } else {
            api.editTag(itemId = id, removeTag = STARRED_TAG)
        }
    }

    private fun com.fluxa.app.data.api.model.StreamItem.toEntity(): ArticleEntity {
        return ArticleEntity(
            id = id,
            title = title.orEmpty().ifBlank { "(无标题)" },
            feedName = origin?.title.orEmpty().ifBlank { "Inoreader" },
            publishedAtEpochSeconds = published ?: (System.currentTimeMillis() / 1000),
            isRead = categories?.contains(READ_TAG) == true,
            isStarred = categories?.contains(STARRED_TAG) == true,
            contentHtml = summary?.content.orEmpty()
        )
    }

    private fun ArticleEntity.toDomain(): Article {
        return Article(
            id = id,
            title = title,
            feedName = feedName,
            publishedAt = Instant.ofEpochSecond(publishedAtEpochSeconds),
            isRead = isRead,
            isStarred = isStarred,
            contentHtml = contentHtml
        )
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val READ_TAG = "user/-/state/com.google/read"
        const val STARRED_TAG = "user/-/state/com.google/starred"
    }
}
