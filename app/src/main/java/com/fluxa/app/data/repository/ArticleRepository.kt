package com.fluxa.app.data.repository

import com.fluxa.app.domain.model.Article
import kotlinx.coroutines.flow.Flow

interface ArticleRepository {
    fun getPagedArticles(): Flow<List<Article>>
    suspend fun refresh()
    suspend fun loadMore()
    suspend fun markRead(id: String)
    suspend fun toggleStar(id: String)
    suspend fun addHighlight(articleId: String, text: String)
    suspend fun addNote(articleId: String, note: String)
    suspend fun archive(articleId: String)
    suspend fun saveForLater(articleId: String)
    suspend fun syncPendingActions()
}
