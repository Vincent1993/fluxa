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
import retrofit2.HttpException
import javax.inject.Inject
import com.fluxa.app.data.reader.LocalReadingRepository
import java.time.LocalDate
import java.time.ZoneId

enum class ArticleFilter(val label: String) { All("全部"), Unread("未读"), Starred("收藏"), Saved("稍后读"), Archived("归档"), Annotated("笔记与高亮") }

data class FeedListState(
    val articles: List<Article> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val query: String = "",
    val filter: ArticleFilter = ArticleFilter.All,
    val source: String? = null,
    val tag: String = "",
    val dateFrom: String = "",
    val dateTo: String = "",
    val busy: Boolean = false,
    val pendingCount: Int = 0,
    val message: String? = null
)

@HiltViewModel
class FeedListViewModel @Inject constructor(
    private val repository: ArticleRepository,
    private val savedState: SavedStateHandle,
    private val local: LocalReadingRepository? = null
) : ViewModel() {
    private val allArticles = MutableStateFlow(emptyList<Article>())
    private val _state = MutableStateFlow(FeedListState(
        query = savedState["query"] ?: "",
        source = savedState["source"],
        filter = ArticleFilter.valueOf(savedState["filter"] ?: "All"),
        tag = savedState["tag"] ?: "", dateFrom = savedState["dateFrom"] ?: "", dateTo = savedState["dateTo"] ?: ""
    ))
    val state = combine(allArticles, _state) { all, state ->
        state.copy(articles = all.filter { matchesReaderFilter(it, state) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), _state.value)

    init {
        viewModelScope.launch { repository.getPagedArticles().collect { allArticles.value = it } }
        viewModelScope.launch { repository.observeSubscriptions().collect { items ->
            _state.update { it.copy(subscriptions = items) }
        } }
        viewModelScope.launch { repository.observePendingCount().collect { count ->
            _state.update { it.copy(pendingCount = count) }
        } }
        refresh()
    }

    fun setQuery(value: String) {
        savedState["query"] = value
        _state.update { it.copy(query = value) }
    }
    fun setFilter(value: ArticleFilter) {
        savedState["filter"] = value.name
        _state.update { it.copy(filter = value) }
    }
    fun setAdvancedFilters(tag: String, from: String, to: String): Boolean {
        try {
            val start = from.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
            val end = to.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
            require(start == null || end == null || !end.isBefore(start)) { "结束日期不能早于起始日期" }
            savedState["tag"] = tag.trim(); savedState["dateFrom"] = from; savedState["dateTo"] = to
            _state.update { it.copy(tag = tag.trim(), dateFrom = from, dateTo = to, message = null) }
            return true
        } catch (_: Exception) {
            _state.update { it.copy(message = "请输入有效日期 YYYY-MM-DD，结束日期不能早于起始日期") }
            return false
        }
    }
    fun archive(id: String) = operate { checkNotNull(local).archive(id) }
    fun save(id: String) = operate { checkNotNull(local).save(id) }
    fun importLegacy() = perform {
        val count = checkNotNull(local).importLegacy()
        _state.update { it.copy(message = "已保全导入 $count 篇旧本地文章，仅在本机保存") }
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
    fun loadMore() = perform { repository.loadMore() }
    fun retryPending() = perform { repository.syncPendingActions() }
    fun markRead(id: String) = operate { repository.markRead(id) }
    fun toggleStar(id: String) = operate { repository.toggleStar(id) }
    fun addSubscription(url: String) = perform {
        repository.addSubscription(url)
        _state.update { it.copy(message = "订阅已添加，可刷新获取文章") }
    }

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
    if (state.filter == ArticleFilter.Saved && article.savedAtEpochSeconds == null) return false
    if (state.filter == ArticleFilter.Annotated && article.annotationCount == 0) return false
    if (state.tag.isNotBlank() && article.tags.none { it.equals(state.tag, true) }) return false
    val day = article.publishedAt.atZone(zone).toLocalDate()
    if (state.dateFrom.isNotBlank() && day.isBefore(LocalDate.parse(state.dateFrom))) return false
    if (state.dateTo.isNotBlank() && day.isAfter(LocalDate.parse(state.dateTo))) return false
    return state.query.isBlank() || listOf(article.title, article.feedName, article.contentHtml, article.author, article.excerpt)
        .any { it.contains(state.query, true) } || article.tags.any { it.contains(state.query, true) }
}
