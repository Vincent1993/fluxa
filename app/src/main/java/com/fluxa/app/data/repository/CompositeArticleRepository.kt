package com.fluxa.app.data.repository

import com.fluxa.app.data.directrss.DirectRssRepository
import com.fluxa.app.data.directrss.LOCAL_PREFIX
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CompositeArticleRepository private constructor(
    private val remote: ArticleRepository,
    private val local: DirectRssRepository,
    private val hasSession: suspend () -> Boolean
) : ArticleRepository {
    @Inject constructor(remote: NewsBlurArticleRepository, local: DirectRssRepository, auth: AuthRepository) :
        this(remote, local, { auth.hasSession() })
    companion object {
        internal fun forTest(remote: ArticleRepository, local: DirectRssRepository, hasSession: suspend () -> Boolean) =
            CompositeArticleRepository(remote, local, hasSession)
    }
    private var source: String? = null
    override fun getPagedArticles() = combine(remote.getPagedArticles(), local.articles()) { a, b ->
        (a + b).sortedWith(compareByDescending<com.fluxa.app.domain.model.Article> { it.publishedAt }.thenBy { it.id })
    }
    override fun observeSubscriptions() = combine(remote.observeSubscriptions(), local.subscriptions()) { a, b -> a + b }
    override fun observeLocalFolders() = local.folders()
    override suspend fun createLocalFolder(name: String) = local.createFolder(name)
    override suspend fun organizeLocalSource(id: String, alias: String?, folderId: String?, paused: Boolean, archived: Boolean) =
        local.organize(id, alias, folderId, paused, archived)
    override suspend fun discoverLocalSource(url: String) = local.discover(url)
    override suspend fun previewLocalSource(url: String) = local.preview(url)
    override fun observePendingCount() = remote.observePendingCount()
    override fun observeSyncErrors() = remote.observeSyncErrors()
    override fun observeAwaitingCount() = remote.observeAwaitingCount()
    override suspend fun addSubscription(url: String) = remote.addSubscription(url)
    override suspend fun addLocalSubscription(url: String, dailyOnly: Boolean) = local.addSubscription(url, dailyOnly)
    override suspend fun markRead(id: String) = if (id.startsWith(LOCAL_PREFIX)) local.markRead(id) else remote.markRead(id)
    override suspend fun setReadState(id: String, read: Boolean) = if (id.startsWith(LOCAL_PREFIX)) local.setReadState(id, read) else remote.setReadState(id, read)
    override suspend fun bulkRead(ids: List<String>): ReadReceipt {
        val changes = mutableListOf<ReadChange>()
        val failures = mutableListOf<String>()
        val (localIds, remoteIds) = ids.distinct().partition { it.startsWith(LOCAL_PREFIX) }
        for ((name, operation) in listOf<Pair<String, suspend () -> ReadReceipt>>(
            "本地 RSS" to { if (localIds.isEmpty()) ReadReceipt(emptyList()) else local.bulkRead(localIds) },
            "NewsBlur" to { if (remoteIds.isEmpty()) ReadReceipt(emptyList()) else remote.bulkRead(remoteIds) })) {
            try { changes += operation().changes }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failures += "$name: ${failure.message}" }
        }
        return ReadReceipt(changes, failures)
    }
    override suspend fun undoRead(receipt: ReadReceipt): ReadReceipt {
        val (localChanges, remoteChanges) = receipt.changes.partition { it.id.startsWith(LOCAL_PREFIX) }
        val restored = mutableListOf<ReadChange>()
        val failures = mutableListOf<String>()
        for (operation in listOf<suspend () -> ReadReceipt>(
            { if (localChanges.isEmpty()) ReadReceipt(emptyList()) else local.undoRead(ReadReceipt(localChanges)) },
            { if (remoteChanges.isEmpty()) ReadReceipt(emptyList()) else remote.undoRead(ReadReceipt(remoteChanges)) })) {
            try { restored += operation().changes }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failures += failure.message ?: "撤销失败" }
        }
        return ReadReceipt(restored, failures)
    }
    override suspend fun initialize(sourceId: String?) {
        source = sourceId
        val errors = mutableListOf<String>()
        try { local.refreshOnResume() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { errors += e.message ?: "本地刷新失败" }
        if (sourceId?.startsWith(LOCAL_PREFIX) != true && hasSession()) {
            try { remote.selectSource(sourceId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors += "NewsBlur 刷新失败：${e.message}" }
        }
        if (errors.isNotEmpty()) throw IllegalStateException(errors.joinToString("；"))
    }
    override suspend fun refreshOnResume() = local.refreshOnResume()
    override suspend fun retryLocalSource(id: String) = local.refresh(id)
    override fun localOfflinePolicy() = local.offlinePolicy()
    override suspend fun updateLocalOfflinePolicy(policy: com.fluxa.app.data.directrss.LocalOfflinePolicy) = local.updateOfflinePolicy(policy)
    override suspend fun localOfflineUsage() = local.trimPayloads(evict = false)
    override suspend fun pinLocalFolder(id: String, pinned: Boolean) = local.pinFolder(id, pinned)
    override suspend fun pinLocalArticle(id: String, pinned: Boolean) = local.pinArticle(id, pinned)
    override suspend fun setReadLater(id: String, value: Boolean) {
        require(id.startsWith(LOCAL_PREFIX)) { "远程文章暂不支持本地稍后读" }
        local.setReadLater(id, value)
    }
    override suspend fun changeFlag(id: String, flag: ArticleFlag, value: Boolean) =
        if (id.startsWith(LOCAL_PREFIX)) local.changeFlag(id, flag, value) else remote.changeFlag(id, flag, value)
    override suspend fun undoFlag(receipt: FlagReceipt) =
        if (receipt.id.startsWith(LOCAL_PREFIX)) local.undoFlag(receipt) else remote.undoFlag(receipt)
    override suspend fun toggleStar(id: String) = if (id.startsWith(LOCAL_PREFIX)) local.toggleStar(id) else remote.toggleStar(id)
    override suspend fun syncPendingActions() = remote.syncPendingActions()
    override suspend fun refreshSubscriptions() = remote.refreshSubscriptions()
    // Worker authorization remains strictly NewsBlur-only.
    override suspend fun refreshInBackground() = remote.refreshInBackground()
    override suspend fun selectSource(sourceId: String?) { source = sourceId; refresh() }
    override suspend fun refresh() {
        val selected = source
        if (selected?.startsWith(LOCAL_PREFIX) == true) { local.refresh(selected); return }
        if (selected != null || (!local.hasSubscriptions() && hasSession())) { remote.selectSource(selected); return }
        // A signed-out library may contain only saved pages (or no feeds at all).
        // Absence of RSS subscriptions is not authorization to contact NewsBlur.
        val errors = mutableListOf<String>()
        try { local.refresh() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { errors += e.message ?: "本地刷新失败" }
        if (hasSession()) {
            try { remote.selectSource(null) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors += "NewsBlur 刷新失败；本地结果已保留：${e.message}" }
        }
        if (errors.isNotEmpty()) throw IllegalStateException(errors.joinToString("；"))
    }
    override suspend fun loadMore() {
        if (source?.startsWith(LOCAL_PREFIX) == true || (source == null && !hasSession())) {
            throw IllegalStateException("本地 RSS 仅提供近期窗口；已有历史缓存保留，请刷新获取最新文章")
        }
        remote.loadMore()
    }
}
