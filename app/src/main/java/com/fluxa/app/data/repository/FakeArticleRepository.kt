package com.fluxa.app.data.repository

import com.fluxa.app.domain.model.Article
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeArticleRepository @Inject constructor() : ArticleRepository {
    private val articles = MutableStateFlow(
        List(20) { index ->
            Article(
                id = "article_$index",
                title = "Welcome to Fluxa #$index",
                feedName = "Fluxa Daily",
                publishedAt = Instant.now().minusSeconds((index * 300).toLong()),
                isRead = index > 5,
                isStarred = index % 3 == 0,
                contentHtml = "<h1>Fluxa</h1><p>This is sample article $index.</p>"
            )
        }
    )

    override fun getPagedArticles(): Flow<List<Article>> = articles

    override suspend fun refresh() {
        // Placeholder for network + room sync
    }

    override suspend fun loadMore() {
        val current = articles.value
        val start = current.size
        val more = List(10) { offset ->
            val index = start + offset
            Article(
                id = "article_$index",
                title = "More from Fluxa #$index",
                feedName = "Reader",
                publishedAt = Instant.now().minusSeconds((index * 400).toLong()),
                isRead = false,
                isStarred = false,
                contentHtml = "<h2>Article $index</h2><p>More content placeholder.</p>"
            )
        }
        articles.update { it + more }
    }

    override suspend fun markRead(id: String) {
        articles.update { list -> list.map { if (it.id == id) it.copy(isRead = true) else it } }
    }

    override suspend fun toggleStar(id: String) {
        articles.update { list -> list.map { if (it.id == id) it.copy(isStarred = !it.isStarred) else it } }
    }
}
