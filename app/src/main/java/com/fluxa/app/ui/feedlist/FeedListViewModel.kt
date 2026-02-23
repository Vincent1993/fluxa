package com.fluxa.app.ui.feedlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxa.app.data.repository.ArticleRepository
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FeedListViewModel @Inject constructor(
    private val repository: ArticleRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<UiState<List<Article>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<Article>>> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var isLoadingMore = false

    init {
        observeArticles()
        refresh()
    }

    private fun observeArticles() {
        viewModelScope.launch {
            repository.getPagedArticles()
                .catch { _uiState.value = UiState.Error("加载文章失败，请稍后重试") }
                .collectLatest { articles ->
                    _uiState.value = if (articles.isEmpty()) UiState.Empty else UiState.Success(articles)
                }
        }
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
}
