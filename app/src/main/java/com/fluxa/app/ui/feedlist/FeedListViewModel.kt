package com.fluxa.app.ui.feedlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.fluxa.app.data.repository.ArticleRepository
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FeedListViewModel @Inject constructor(
    private val repository: ArticleRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _uiState = MutableStateFlow<UiState<List<Article>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<Article>>> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    data class FilterState(
        val isRead: Boolean? = null,
        val isStarred: Boolean? = null,
        val source: String? = null,
        val tag: String? = null,
        val startDateEpochSeconds: Long? = null,
        val endDateEpochSeconds: Long? = null
    )

    private val _filterState = MutableStateFlow(
        FilterState(
            isRead = savedStateHandle[KEY_FILTER_IS_READ],
            isStarred = savedStateHandle[KEY_FILTER_IS_STARRED],
            source = savedStateHandle[KEY_FILTER_SOURCE],
            tag = savedStateHandle[KEY_FILTER_TAG],
            startDateEpochSeconds = savedStateHandle[KEY_FILTER_START],
            endDateEpochSeconds = savedStateHandle[KEY_FILTER_END]
        )
    )
    val filterState: StateFlow<FilterState> = _filterState.asStateFlow()

    private val _searchQuery = MutableStateFlow(savedStateHandle[KEY_SEARCH_QUERY] ?: "")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var isLoadingMore = false

    init {
        observeArticles()
        refresh()
    }

    private fun observeArticles() {
        viewModelScope.launch {
            combine(_filterState, _searchQuery) { filter, query -> filter to query }
                .collectLatest { (filter, query) ->
                    repository.getFilteredArticles(
                        isRead = filter.isRead,
                        isStarred = filter.isStarred,
                        source = filter.source,
                        tag = filter.tag,
                        startDateEpochSeconds = filter.startDateEpochSeconds,
                        endDateEpochSeconds = filter.endDateEpochSeconds,
                        searchQuery = query
                    )
                .catch { _uiState.value = UiState.Error("加载文章失败，请稍后重试") }
                .collectLatest { articles ->
                    _uiState.value = if (articles.isEmpty()) UiState.Empty else UiState.Success(articles)
                }
                }
        }
    }

    fun updateFilterState(filterState: FilterState) {
        _filterState.value = filterState
        savedStateHandle[KEY_FILTER_IS_READ] = filterState.isRead
        savedStateHandle[KEY_FILTER_IS_STARRED] = filterState.isStarred
        savedStateHandle[KEY_FILTER_SOURCE] = filterState.source
        savedStateHandle[KEY_FILTER_TAG] = filterState.tag
        savedStateHandle[KEY_FILTER_START] = filterState.startDateEpochSeconds
        savedStateHandle[KEY_FILTER_END] = filterState.endDateEpochSeconds
    }

    fun updateSearchQuery(searchQuery: String) {
        _searchQuery.value = searchQuery
        savedStateHandle[KEY_SEARCH_QUERY] = searchQuery
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            runCatching { repository.refresh() }
                .onFailure { _uiState.value = UiState.Error("刷新失败，请检查网络") }
            _isRefreshing.value = false
        }
    }

    fun loadMore() {
        if (isLoadingMore) return
        viewModelScope.launch {
            isLoadingMore = true
            runCatching { repository.loadMore() }
                .onFailure { _uiState.value = UiState.Error("加载更多失败") }
            isLoadingMore = false
        }
    }

    fun markRead(id: String) = viewModelScope.launch { repository.markRead(id) }

    fun toggleStar(id: String) = viewModelScope.launch { repository.toggleStar(id) }

    private companion object {
        const val KEY_SEARCH_QUERY = "feed_search_query"
        const val KEY_FILTER_IS_READ = "feed_filter_is_read"
        const val KEY_FILTER_IS_STARRED = "feed_filter_is_starred"
        const val KEY_FILTER_SOURCE = "feed_filter_source"
        const val KEY_FILTER_TAG = "feed_filter_tag"
        const val KEY_FILTER_START = "feed_filter_start"
        const val KEY_FILTER_END = "feed_filter_end"
    }
}
