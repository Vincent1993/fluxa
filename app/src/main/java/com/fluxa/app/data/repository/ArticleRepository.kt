package com.fluxa.app.data.repository

import com.fluxa.app.domain.model.Article
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.fluxa.app.domain.model.Subscription

interface ArticleRepository {
    fun getPagedArticles(): Flow<List<Article>>
    suspend fun refresh()
    suspend fun refreshInBackground() = refresh()
    suspend fun refreshOnResume() = Unit
    suspend fun retryLocalSource(id: String): Unit = throw UnsupportedOperationException("仅本地 RSS 支持")
    suspend fun initialize(sourceId: String?) = selectSource(sourceId)
    fun localOfflinePolicy(): com.fluxa.app.data.directrss.LocalOfflinePolicy = throw UnsupportedOperationException("仅本地 RSS 支持")
    suspend fun updateLocalOfflinePolicy(policy: com.fluxa.app.data.directrss.LocalOfflinePolicy): Unit = throw UnsupportedOperationException("仅本地 RSS 支持")
    suspend fun localOfflineUsage(): com.fluxa.app.data.directrss.OfflineUsage = throw UnsupportedOperationException("仅本地 RSS 支持")
    suspend fun pinLocalFolder(id: String, pinned: Boolean): Unit = throw UnsupportedOperationException("仅本地 RSS 支持")
    suspend fun pinLocalArticle(id: String, pinned: Boolean): Unit = throw UnsupportedOperationException("仅本地 RSS 支持")
    suspend fun loadMore()
    suspend fun markRead(id: String)
    suspend fun setReadState(id: String, read: Boolean) {
        if (read) markRead(id) else throw UnsupportedOperationException("此来源暂不支持标为未读")
    }
    suspend fun bulkRead(ids: List<String>): ReadReceipt = throw UnsupportedOperationException("此来源暂不支持批量已读")
    suspend fun undoRead(receipt: ReadReceipt): ReadReceipt = throw UnsupportedOperationException("此来源暂不支持撤销已读")
    suspend fun setReadLater(id: String, value: Boolean): Unit = throw UnsupportedOperationException("仅本地 RSS 支持稍后读标记")
    suspend fun changeFlag(id: String, flag: ArticleFlag, value: Boolean): FlagReceipt? = throw UnsupportedOperationException("此来源暂不支持此操作")
    suspend fun undoFlag(receipt: FlagReceipt): Boolean = false
    suspend fun toggleStar(id: String)
    fun observeSubscriptions(): Flow<List<Subscription>> = flowOf(emptyList())
    fun observePendingCount(): Flow<Int> = flowOf(0)
    fun observeSyncErrors(): Flow<List<String>> = flowOf(emptyList())
    fun observeAwaitingCount(): Flow<Int> = flowOf(0)
    suspend fun refreshSubscriptions() = Unit
    suspend fun addSubscription(url: String) = Unit
    suspend fun addLocalSubscription(url: String, dailyOnly: Boolean = false) {
        throw UnsupportedOperationException("本地 RSS 不可用")
    }
    fun observeLocalFolders(): Flow<List<com.fluxa.app.data.directrss.DirectFolder>> = flowOf(emptyList())
    suspend fun createLocalFolder(name: String): String = throw UnsupportedOperationException("本地 RSS 不可用")
    suspend fun organizeLocalSource(id: String, alias: String?, folderId: String?, paused: Boolean, archived: Boolean): Unit =
        throw UnsupportedOperationException("本地 RSS 不可用")
    suspend fun discoverLocalSource(url: String): com.fluxa.app.data.directrss.FeedDiscovery =
        throw UnsupportedOperationException("本地 RSS 不可用")
    suspend fun previewLocalSource(url: String): com.fluxa.app.data.directrss.RssFeed =
        throw UnsupportedOperationException("本地 RSS 不可用")
    suspend fun selectSource(sourceId: String?) = refresh()
    suspend fun syncPendingActions() = Unit
}
