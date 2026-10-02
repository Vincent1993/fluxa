package com.fluxa.app.ui.article

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxa.app.data.repository.ArticleRepository
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArticleViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val repository: ArticleRepository
) : ViewModel() {
    private val articleId: String = checkNotNull(savedState["id"])
    val uiState: StateFlow<UiState<Article>> = repository.getPagedArticles().map { articles ->
        articles.firstOrNull { it.id == articleId }?.let { UiState.Success(it) }
            ?: UiState.Error("文章尚未缓存，请返回列表刷新")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)
    val fontSize = savedState.getStateFlow("fontSize", 18)
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init { operate { repository.markRead(articleId) } }
    fun setFontSize(value: Int) { savedState["fontSize"] = value.coerceIn(16, 24) }
    fun toggleStar() = operate { repository.toggleStar(articleId) }
    private fun operate(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { _error.value = "操作失败，请返回列表重试" }
    }
}
