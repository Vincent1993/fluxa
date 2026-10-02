package com.fluxa.app.data.repository

import com.fluxa.app.domain.model.Article
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.fluxa.app.domain.model.Subscription

interface ArticleRepository {
    fun getPagedArticles(): Flow<List<Article>>
    suspend fun refresh()
    suspend fun refreshInBackground() = refresh()
    suspend fun loadMore()
    suspend fun markRead(id: String)
    suspend fun toggleStar(id: String)
    fun observeSubscriptions(): Flow<List<Subscription>> = flowOf(emptyList())
    fun observePendingCount(): Flow<Int> = flowOf(0)
    suspend fun refreshSubscriptions() = Unit
    suspend fun addSubscription(url: String) = Unit
    suspend fun selectSource(sourceId: String?) = refresh()
    suspend fun syncPendingActions() = Unit
}
