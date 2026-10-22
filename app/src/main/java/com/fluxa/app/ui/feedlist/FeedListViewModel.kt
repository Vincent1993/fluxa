package com.fluxa.app.ui.feedlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxa.app.data.repository.ArticleRepository
import com.fluxa.app.domain.model.Subscription
import com.fluxa.app.domain.model.Article
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import retrofit2.HttpException
import javax.inject.Inject
import java.time.LocalDate
import java.time.ZoneId

enum class ArticleFilter(val label: String) {
    All("全部"), Unread("未读"), Starred("收藏"), ReadLater("稍后读"), Last24Hours("24 小时"), Archived("归档"), Annotated("笔记与高亮");

    companion object {
        // The published reader called this collection Saved. Keep old callers and
        // persisted searches compatible without exposing two identical choices.
        val Saved: ArticleFilter get() = ReadLater
        fun fromPersisted(value: String): ArticleFilter = if (value == "Saved") ReadLater else valueOf(value)
    }
}

data class FeedListState(
    val articles: List<Article> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val query: String = "",
    val filter: ArticleFilter = ArticleFilter.All,
    val source: String? = null,
    val busy: Boolean = false,
    val pendingCount: Int = 0,
    val syncErrors: List<String> = emptyList(),
    val awaitingConfirmation: Int = 0,
    val message: String? = null,
    val subscriptionSuccess: Int = 0,
    val localFolders: List<com.fluxa.app.data.directrss.DirectFolder> = emptyList(),
    val readReceipt: com.fluxa.app.data.repository.ReadReceipt? = null,
    val scope: SearchScope = SearchScope(),
    val triage: TriageConfig = TriageConfig(),
    val groups: Map<String, List<Article>> = emptyMap(),
    val unreadCount: Int = 0, val starredCount: Int = 0,
    val sourceCounts: Map<String, Int> = emptyMap(),
    val searchPending: Boolean = false,
    val readLaterUndo: Pair<String, Boolean>? = null,
    val swipeConfig: SwipeConfig = SwipeConfig(),
    val flagReceipt: com.fluxa.app.data.repository.FlagReceipt? = null,
    val tag: String = "", val dateFrom: String = "", val dateTo: String = ""
)

@HiltViewModel
class FeedListViewModel @Inject constructor(
    private val repository: ArticleRepository,
    private val savedState: SavedStateHandle,
    private val triageSettings: TriageSettings? = null,
    private val triageRuntime: TriageRuntime = TriageRuntime(),
    val notifications: com.fluxa.app.data.directrss.LocalFeedNotifications? = null,
    private val swipeSettings: SwipeSettings? = null,
    private val retained: com.fluxa.app.data.reader.LocalReadingRepository? = null
) : ViewModel() {
    private val allArticles = MutableStateFlow(emptyList<Article>())
    private val _state = MutableStateFlow(FeedListState(
        query = savedState.get<String>("query").orEmpty().take(256),
        source = savedState["source"],
        filter = runCatching { ArticleFilter.fromPersisted(savedState["filter"] ?: "All") }.getOrDefault(ArticleFilter.All),
        scope = SearchScope(after = savedState["after"], before = savedState["before"], showHidden = savedState["hidden"] ?: false, tag = savedState["tag"] ?: ""),
        triage = triageSettings?.load() ?: TriageConfig(),
        swipeConfig = swipeSettings?.load() ?: SwipeConfig()
    ))
    // Editor echo must never wait for debounce / body parsing / result projection.
    private val _editorQuery = MutableStateFlow(_state.value.query)
    val editorQuery = _editorQuery.asStateFlow()
    // Back restoration reads the cached value before collecting: keep it current during reader visits.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val debouncedQuery = _state.map { it.query }.distinctUntilChanged().mapLatest {
        if (it.isNotBlank()) kotlinx.coroutines.delay(250)
        it
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val clockTick = _state.map { it.filter == ArticleFilter.Last24Hours }.distinctUntilChanged().flatMapLatest { live ->
        if (!live) flowOf(Unit) else flow {
            while (true) { emit(Unit); kotlinx.coroutines.delay(60_000) }
        }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state = combine(allArticles, _state, debouncedQuery, clockTick) { all, state, query, _ -> Triple(all, state, query) }.mapLatest { (all, state, query) ->
        val scope = state.scope.copy(query = state.query, source = state.source, filter = state.filter)
        if (state.query != query) return@mapLatest state.copy(scope = scope, searchPending = true)
        val result = kotlinx.coroutines.withContext(triageRuntime.dispatcher) {
            TriageEngine(triageRuntime.clock).apply(all, scope.copy(query = query), state.triage) { coroutineContext.ensureActive() }
        }
        state.copy(articles = result.articles, scope = scope, groups = result.groups, unreadCount = result.unread, starredCount = result.starred,
            sourceCounts = all.groupingBy { it.source }.eachCount())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _state.value)

    init {
        viewModelScope.launch { repository.getPagedArticles().collect { allArticles.value = it } }
        viewModelScope.launch { repository.observeSubscriptions().collect { items ->
            _state.update { it.copy(subscriptions = items) }
        } }
        viewModelScope.launch { repository.observePendingCount().collect { count ->
            _state.update { it.copy(pendingCount = count) }
        } }
        viewModelScope.launch { repository.observeAwaitingCount().collect { count ->
            _state.update { it.copy(awaitingConfirmation = count) }
        } }
        viewModelScope.launch { repository.observeSyncErrors().collect { errors ->
            _state.update { it.copy(syncErrors = errors) }
        } }
        viewModelScope.launch { repository.observeLocalFolders().collect { folders ->
            _state.update { it.copy(localFolders = folders) }
        } }
        perform { repository.initialize(_state.value.source) }
    }

    fun setQuery(value: String) {
        _editorQuery.value = value.take(256)
        savedState["query"] = value.take(256)
        _state.update { it.copy(query = value.take(256)) }
    }
    fun applyScope(scope: SearchScope) {
        _editorQuery.value = scope.query
        savedState["query"] = scope.query; savedState["source"] = scope.source; savedState["filter"] = scope.filter.name
        savedState["after"] = scope.after; savedState["before"] = scope.before; savedState["hidden"] = scope.showHidden
        savedState["tag"] = scope.tag
        _state.update { it.copy(query = scope.query, source = scope.source, filter = scope.filter, scope = scope) }
    }
    fun reloadPreferences() { _state.update { it.copy(triage = triageSettings?.load() ?: it.triage,
        swipeConfig = swipeSettings?.load() ?: it.swipeConfig) } }
    fun configure(config: TriageConfig) = operate {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { triageSettings?.save(config) }
        _state.update { it.copy(triage = config) }
    }
    suspend fun previewRule(rule: TriageRule): List<Article> {
        val snapshot = allArticles.value.toList()
        return kotlinx.coroutines.withContext(triageRuntime.dispatcher) { snapshot.filter {
            coroutineContext.ensureActive()
            (rule.source == null || rule.source == it.source) && rule.matches(it, plainBody(it.contentHtml))
        } }
    }
    fun setFilter(value: ArticleFilter) {
        savedState["filter"] = value.name
        _state.update { it.copy(filter = value) }
    }
    fun selectSource(id: String?) {
        if (_state.value.busy) return
        savedState["source"] = id
        _state.update { it.copy(source = id) }
        perform { repository.selectSource(id) }
    }
    fun refresh() = perform {
        repository.selectSource(_state.value.source)
    }
    fun retrySource(id: String) = perform { repository.retryLocalSource(id) }
    fun importLegacy() = operate {
        val count = checkNotNull(retained).importLegacy()
        _state.update { it.copy(message = "已导入 $count 篇旧本地文章") }
    }
    fun onResume() = operate { repository.refreshOnResume() }
    fun offlinePolicy() = repository.localOfflinePolicy()
    suspend fun updateOfflinePolicy(policy: com.fluxa.app.data.directrss.LocalOfflinePolicy) = repository.updateLocalOfflinePolicy(policy)
    suspend fun offlineUsage() = repository.localOfflineUsage()
    suspend fun pinFolder(id: String, pinned: Boolean) = repository.pinLocalFolder(id, pinned)
    fun pinArticle(id: String, pinned: Boolean) = operate { repository.pinLocalArticle(id, pinned) }
    fun configureSwipes(config: SwipeConfig) = operate {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { swipeSettings?.save(config) }
        _state.update { it.copy(swipeConfig = config) }
    }
    private suspend fun flag(id: String, field: com.fluxa.app.data.repository.ArticleFlag, value: Boolean) {
        val receipt = repository.changeFlag(id, field, value)
        _state.update { it.copy(flagReceipt = receipt, message = if (receipt == null) "状态未改变" else "已更改标记") }
    }
    fun swipe(id: String, action: SwipeAction) = operate {
        val row = allArticles.value.firstOrNull { it.id == id } ?: return@operate
        when (action) {
            SwipeAction.Read -> flag(id, com.fluxa.app.data.repository.ArticleFlag.Read, true)
            SwipeAction.ToggleRead -> flag(id, com.fluxa.app.data.repository.ArticleFlag.Read, !row.isRead)
            SwipeAction.Star -> flag(id, com.fluxa.app.data.repository.ArticleFlag.Star, !row.isStarred)
            SwipeAction.ReadLater -> flag(id, com.fluxa.app.data.repository.ArticleFlag.ReadLater, !row.readLater)
        }
    }
    fun undoFlag() = operate {
        val receipt = _state.value.flagReceipt ?: return@operate
        val changed = repository.undoFlag(receipt)
        _state.update { if (it.flagReceipt == receipt) it.copy(flagReceipt = null, message = if (changed) "已撤销" else "已保留后续更改") else it }
    }
    fun dismissFlag() { _state.update { it.copy(flagReceipt = null) } }
    fun setReadState(id: String, read: Boolean) = operate { flag(id, com.fluxa.app.data.repository.ArticleFlag.Read, read) }
    fun bulkRead(ids: List<String>) = operate {
        val receipt = repository.bulkRead(ids.toList())
        _state.update { it.copy(readReceipt = receipt, message = "已更改 ${receipt.changes.size} 篇" + receipt.failures.joinToString("；")) }
    }
    fun undoRead() = operate {
        val receipt = _state.value.readReceipt ?: return@operate
        val restored = repository.undoRead(receipt)
        _state.update { it.copy(readReceipt = if (restored.failures.isEmpty()) null else receipt.copy(failures = restored.failures),
            message = "已撤销 ${restored.changes.size} 篇；后续更改保持不变" + restored.failures.joinToString("；")) }
    }
    fun dismissReadReceipt() { _state.update { it.copy(readReceipt = null) } }
    fun loadMore() = perform { repository.loadMore() }
    fun retryPending() = perform { repository.syncPendingActions() }
    fun markRead(id: String) = swipe(id, SwipeAction.Read)
    fun toggleStar(id: String) = swipe(id, SwipeAction.Star)
    fun setReadLater(id: String, value: Boolean) = operate {
        flag(id, com.fluxa.app.data.repository.ArticleFlag.ReadLater, value)
    }
    fun undoReadLater() = operate {
        val receipt = _state.value.readLaterUndo ?: return@operate
        if (allArticles.value.firstOrNull { it.id == receipt.first }?.readLater == receipt.second)
            repository.setReadLater(receipt.first, !receipt.second)
        _state.update { it.copy(readLaterUndo = null) }
    }
    fun dismissReadLaterUndo() { _state.update { it.copy(readLaterUndo = null) } }
    fun addSubscription(url: String) = perform {
        repository.addSubscription(url)
        _state.update { it.copy(message = "订阅已添加，可刷新获取文章", subscriptionSuccess = it.subscriptionSuccess + 1) }
    }
    fun addLocalSubscription(url: String, dailyOnly: Boolean) = perform {
        repository.addLocalSubscription(url, dailyOnly)
        _state.update { it.copy(message = "本地订阅已添加 · 不同步至 NewsBlur", subscriptionSuccess = it.subscriptionSuccess + 1) }
    }

    suspend fun discoverLocalSource(url: String) = repository.discoverLocalSource(url)
    suspend fun previewLocalSource(url: String) = repository.previewLocalSource(url)
    suspend fun createLocalFolder(name: String) = repository.createLocalFolder(name)
    suspend fun organizeLocalSource(source: Subscription) = repository.organizeLocalSource(
        source.id, source.alias, source.folderId, source.paused, source.archived)

    private fun perform(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(message = messageFor(e)) } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    private fun operate(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(message = messageFor(e)) } }
    }
}

internal fun messageFor(e: Exception): String = when {
    e is HttpException && e.code() in listOf(401, 403) -> "登录已失效，请重新登录；本地文章仍可阅读"
    e is com.fluxa.app.data.api.NewsBlurApiException -> e.message ?: "NewsBlur 操作失败，请重试"
    e is HttpException && e.code() == 429 -> "服务端已限流或达到每日额度；缓存与待同步操作已保留"
    e is IllegalArgumentException || e is IllegalStateException -> e.message ?: "操作失败"
    else -> "暂时无法同步，请检查网络或登录状态；本地文章仍可阅读"
}

internal fun matchesReaderFilter(article: Article, state: FeedListState, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    val archived = article.archivedAtEpochSeconds != null
    if (state.filter == ArticleFilter.Archived && !archived || state.filter != ArticleFilter.Archived && archived) return false
    if (state.source != null && article.source != state.source) return false
    if (state.filter == ArticleFilter.Unread && article.isRead || state.filter == ArticleFilter.Starred && !article.isStarred) return false
    if (state.filter == ArticleFilter.ReadLater && !article.readLater && article.savedAtEpochSeconds == null) return false
    if (state.filter == ArticleFilter.Annotated && article.annotationCount == 0) return false
    if (state.tag.isNotBlank() && article.tags.none { it.equals(state.tag, true) }) return false
    val day = article.publishedAt.atZone(zone).toLocalDate()
    if (state.dateFrom.isNotBlank() && day.isBefore(LocalDate.parse(state.dateFrom))) return false
    if (state.dateTo.isNotBlank() && day.isAfter(LocalDate.parse(state.dateTo))) return false
    return state.query.isBlank() || listOf(article.title, article.feedName, article.contentHtml, article.author, article.excerpt)
        .any { it.contains(state.query, true) } || article.tags.any { it.contains(state.query, true) }
}
