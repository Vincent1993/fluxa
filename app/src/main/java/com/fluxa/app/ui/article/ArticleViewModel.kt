package com.fluxa.app.ui.article

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxa.app.data.repository.ArticleRepository
import com.fluxa.app.data.reader.LocalReadingRepository
import com.fluxa.app.domain.model.Article
import com.fluxa.app.domain.usecase.*
import com.fluxa.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArticleViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val repository: ArticleRepository,
    private val local: LocalReadingRepository,
    private val addNote: AddNoteUseCase,
    private val addHighlight: AddHighlightUseCase,
    private val archiveArticle: ArchiveArticleUseCase,
    private val saveArticle: SaveForLaterUseCase
) : ViewModel() {
    private val articleId: String = checkNotNull(savedState["id"])
    val uiState: StateFlow<UiState<Article>> = repository.getPagedArticles().map { articles ->
        articles.firstOrNull { it.id == articleId }?.let { UiState.Success(it) }
            ?: UiState.Error("文章尚未缓存，请返回列表刷新")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)
    val annotations = local.annotations(articleId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val fontSize = savedState.getStateFlow("fontSize", 18)
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    init { operate { repository.markRead(articleId); local.open(articleId) } }
    fun setFontSize(value: Int) { savedState["fontSize"] = value.coerceIn(14, 32) }
    fun toggleStar() = operate { repository.toggleStar(articleId) }
    fun note(value: String) = operate { addNote(articleId, value) }
    fun highlight(quote: String, note: String) = operate { addHighlight(articleId, quote, note) }
    fun archive() = operate { archiveArticle(articleId) }
    fun save() = operate { saveArticle(articleId) }
    fun tags(names: List<String>) = operate { local.tags(articleId, names) }
    fun editAnnotation(id: String, quote: String, note: String) = operate { local.editAnnotation(id, quote, note) }
    fun deleteAnnotation(id: String) = operate { local.deleteAnnotation(id) }
    fun saveProgress(value: Float) = operate { local.progress(articleId, value) }
    private fun operate(block: suspend () -> Unit) = viewModelScope.launch {
        try { block(); _error.value = null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { _error.value = e.message ?: "操作失败，请重试" }
    }
}
