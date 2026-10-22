package com.fluxa.app.ui.article

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxa.app.data.directrss.*
import com.fluxa.app.data.local.ReaderPreferences
import com.fluxa.app.data.repository.ArticleRepository
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/** One atomic presentation: a new article can never inherit the former body's mode/notes/errors. */
data class ReaderArticleState(
    val article: UiState<Article> = UiState.Loading,
    val mode: LocalReadingMode = LocalReadingMode.RSS,
    val fullText: String? = null,
    val notes: List<DirectReaderNote> = emptyList(),
    val extractionError: String? = null,
    val error: String? = null,
    val readLaterUndo: Boolean? = null,
    val retainedAnnotations: List<com.fluxa.app.data.reader.ReaderAnnotation> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArticleViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val repository: ArticleRepository,
    val reader: ReaderPreferences,
    private val local: DirectRssRepository,
    private val triage: com.fluxa.app.ui.feedlist.TriageSettings? = null,
    private val retained: com.fluxa.app.data.reader.LocalReadingRepository? = null
) : ViewModel() {
    // The navigation argument is only the initial selection. This key belongs to the
    // same Nav entry's SavedStateHandle and survives Activity/process restoration.
    @Volatile private var articleId: String = savedState[SELECTED_ID] ?: checkNotNull(savedState["id"])
    private val _state = MutableStateFlow(ReaderArticleState())
    val state = _state.asStateFlow()
    private val cachedState = MutableStateFlow<Map<String, Article>>(emptyMap())
    private val cached get() = cachedState.value
    private val _availableIds = MutableStateFlow<Set<String>>(emptySet())
    val availableIds = _availableIds.asStateFlow()
    val adjacentTitles = combine(state, reader.session, cachedState) { value, session, articles ->
        val id = (value.article as? UiState.Success<Article>)?.data?.id
        val neighbors = id?.let { setOfNotNull(session.adjacent(it, -1), session.adjacent(it, 1)) }.orEmpty()
        articles.filterKeys { it in neighbors }.mapValues { it.value.title }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    @Volatile private var selectionGeneration = 0L
    private val extractionGeneration = java.util.concurrent.atomic.AtomicLong()
    private var selectionJob: Job? = null
    private val active = MutableStateFlow(false)
    // Recreation can overlap disposal of the outgoing composition with attachment
    // of the incoming one on this retained VM. Older callbacks cannot revoke it.
    private val activeOwners = linkedMapOf<Any, Boolean>()
    internal fun attachActiveOwner(owner: Any, resumed: Boolean) {
        activeOwners[owner] = resumed
        setActive(resumed)
    }
    internal fun updateActiveOwner(owner: Any, resumed: Boolean) {
        if (owner !in activeOwners) return
        activeOwners[owner] = resumed
        if (activeOwners.keys.lastOrNull() === owner) setActive(resumed)
    }
    internal fun releaseActiveOwner(owner: Any) {
        val wasCurrent = activeOwners.keys.lastOrNull() === owner
        activeOwners.remove(owner)
        if (wasCurrent) setActive(activeOwners.values.lastOrNull() ?: false)
    }
    private var unreadDiagnostic = "idle"
    internal fun diagnosticState(): String = "selected=$articleId generation=$selectionGeneration " +
        "presented=${article()?.id} cached=${cached.keys.sorted()} queue=${reader.session.value.ids} " +
        "mode=${_state.value.mode} fullHash=${_state.value.fullText?.let(::stableHash)} " +
        "active=${active.value} owners=${activeOwners.size} extraction=${extractionGeneration.get()} " +
        "selectionJobActive=${selectionJob?.isActive} extractionError=${_state.value.extractionError} unread=$unreadDiagnostic"
    private val retry = MutableStateFlow(0L)
    private fun article() = (_state.value.article as? UiState.Success<Article>)?.data
    private fun identity(article: Article) = Triple(article.id, article.source, article.originalUrl)
    private fun current(id: String, generation: Long) = articleId == id && selectionGeneration == generation

    init {
        viewModelScope.launch {
            repository.getPagedArticles().collect { rows ->
                cachedState.value = rows.associateBy { it.id }
                _availableIds.value = cached.keys.toSet()
                val selected = cached[articleId]
                val old = article()
                if (selected == null) {
                    selectionGeneration++; extractionGeneration.incrementAndGet(); selectionJob?.cancel()
                    _state.value = ReaderArticleState(article = UiState.Error("文章尚未缓存，请返回列表刷新"))
                } else if (old == null || identity(old) != identity(selected)) {
                    bind(selected, markRead = old?.id != selected.id)
                } else {
                    _state.value = _state.value.copy(article = UiState.Success(selected))
                }
            }
        }
    }

    /** Main-thread, synchronous cache validation + retirement + publication; never emits Loading. */
    fun selectAdjacent(target: String, beforeSelect: () -> Boolean = { true }): Boolean {
        if (target !in setOfNotNull(reader.session.value.adjacent(articleId, -1), reader.session.value.adjacent(articleId, 1))) return false
        return selectCached(target, beforeSelect)
    }
    private fun selectCached(target: String, beforeSelect: () -> Boolean): Boolean {
        val selected = cached[target] ?: return false
        if (target == articleId || target !in reader.session.value.ids || !reader.ready.value) return false
        if (!beforeSelect()) return false
        articleId = target
        savedState[SELECTED_ID] = target
        bind(selected, markRead = true)
        return true
    }
    fun persistRetainedProgress() {
        val selected = article() ?: return
        if (!selected.id.startsWith(LOCAL_PREFIX) && retained != null && reader.ready.value) {
            val value = reader.position(selected.id).fraction
            operate(selected.id, selectionGeneration) { retained.progress(selected.id, value) }
        }
    }
    private fun bind(selected: Article, markRead: Boolean) {
        persistRetainedProgress()
        val generation = ++selectionGeneration
        extractionGeneration.incrementAndGet()
        selectionJob?.cancel()
        // Publish all selection-owned fields together before launching any suspended work.
        _state.value = ReaderArticleState(article = UiState.Success(selected))
        savedState[SELECTED_ID] = selected.id
        selectionJob = viewModelScope.launch {
            if (!selected.id.startsWith(LOCAL_PREFIX) && retained != null) launch {
                retained.open(selected.id)
                reader.ready.first { it }
                if (reader.position(selected.id) == com.fluxa.app.data.local.ReadingPosition() && selected.readingProgress > 0f)
                    reader.savePosition(selected.id, com.fluxa.app.data.local.ReadingPosition(fraction = selected.readingProgress))
                retained.annotations(selected.id).collect { records ->
                    if (current(selected.id, generation)) _state.value = _state.value.copy(retainedAnnotations = records)
                }
            }
            launch {
                local.notes(selected.id).collect { notes ->
                    if (current(selected.id, generation)) _state.value = _state.value.copy(notes = notes)
                }
            }
            val modes = if (selected.source.startsWith(LOCAL_PREFIX) && selected.source != SAVED_PAGE_SOURCE)
                local.readingMode(selected.source) else flowOf(LocalReadingMode.RSS)
            var extractionJob: Job? = null
            combine(modes, active, retry) { mode, enabled, _ -> mode to enabled }
                .collect { (mode, enabled) ->
                    if (!current(selected.id, generation)) return@collect
                    val extraction = extractionGeneration.incrementAndGet()
                    extractionJob?.cancel()
                    // Do not join a blocked IO extractor before publishing a different mode.
                    val changed = _state.value.mode != mode
                    _state.value = _state.value.copy(mode = mode,
                        fullText = if (changed) null else _state.value.fullText, extractionError = null)
                    fun ownsExtraction() = current(selected.id, generation) && active.value &&
                        extractionGeneration.get() == extraction && article()?.let(::identity) == identity(selected) &&
                        _state.value.mode == LocalReadingMode.FULL_TEXT
                    if (enabled && mode == LocalReadingMode.FULL_TEXT) extractionJob = launch {
                        try {
                            val body = local.fullBody(selected.id, ::ownsExtraction)
                            if (ownsExtraction()) _state.value = _state.value.copy(fullText = body)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            if (ownsExtraction()) _state.value = _state.value.copy(extractionError = "全文提取失败，仍可阅读 RSS 或打开原网页")
                        }
                    }
                }
        }
        // Only a validated cached selection is marked, and dispatch captures its ID.
        if (markRead) operate(selected.id, generation) { repository.markRead(selected.id) }
    }
    fun setActive(value: Boolean) {
        if (active.value == value) return
        if (!value) extractionGeneration.incrementAndGet()
        active.value = value
        // A pause/resume pair can be conflated by StateFlow. Still restart a job
        // whose ownership was invalidated synchronously during that pause.
        if (value) retry.value++
    }
    fun selectMode(source: String, value: LocalReadingMode): Job {
        val selected = article()
        val generation = selectionGeneration
        if (selected == null || selected.source != source) return viewModelScope.launch { }
        extractionGeneration.incrementAndGet()
        val sameMode = _state.value.mode == value
        return operate(selected.id, generation) {
            local.setReadingMode(source, value)
            if (sameMode && current(selected.id, generation)) retry.value++
        }
    }
    suspend fun saveNote(note: DirectReaderNote) {
        val id = articleId
        require(note.articleId == id)
        local.saveNote(note) // Immutable note carries ownership across suspension.
    }
    suspend fun deleteNote(id: String, expectedArticleId: String) {
        require(expectedArticleId == articleId)
        local.deleteNote(id, expectedArticleId)
    }
    /** A separate explicit frozen queue, never inferred from the changing unread list. */
    suspend fun nextUnread(beforeSelect: () -> Boolean = { true }): String? {
        val id = articleId
        val generation = selectionGeneration
        val all = cached.values.toList()
        unreadDiagnostic = "subscriptions"
        val archived = repository.observeSubscriptions().first().filter { it.archived }.map { it.id }.toSet()
        val config = triage?.load() ?: com.fluxa.app.ui.feedlist.TriageConfig()
        unreadDiagnostic = "capture"
        val snapshot = withContext(Dispatchers.Default) { NextUnreadSelection.capture(all, id, archived, config) }
        unreadDiagnostic = "reader-ready"
        reader.ready.first { it }
        // No suspension between validation, freezing, retirement and selection (ready is true).
        if (!current(id, generation)) { unreadDiagnostic = "obsolete"; return null }
        if (snapshot.firstId !in cached) { unreadDiagnostic = "no-cached-unread"; return null }
        if (!beforeSelect()) { unreadDiagnostic = "retirement-rejected"; return null }
        val target = snapshot.freeze(reader) ?: run { unreadDiagnostic = "freeze-rejected"; return null }
        val accepted = selectCached(target) { true }
        unreadDiagnostic = if (accepted) "selected" else "selection-rejected"
        return target.takeIf { accepted }
    }
    fun toggleReadLater(): Job {
        val selected = article() ?: return viewModelScope.launch { }
        val generation = selectionGeneration
        return operate(selected.id, generation) {
            repository.setReadLater(selected.id, !selected.readLater)
            if (current(selected.id, generation)) _state.value = _state.value.copy(readLaterUndo = !selected.readLater)
        }
    }
    fun undoReadLater(): Job {
        val selected = article() ?: return viewModelScope.launch { }
        val generation = selectionGeneration
        val applied = _state.value.readLaterUndo ?: return viewModelScope.launch { }
        _state.value = _state.value.copy(readLaterUndo = null)
        return operate(selected.id, generation) {
            if (selected.readLater == applied) repository.setReadLater(selected.id, !applied)
        }
    }
    fun toggleStar(): Job {
        val selected = article() ?: return viewModelScope.launch { }
        return operate(selected.id, selectionGeneration) { repository.toggleStar(selected.id) }
    }
    private fun operate(id: String, generation: Long, block: suspend () -> Unit) = viewModelScope.launch {
        if (current(id, generation)) _state.value = _state.value.copy(error = null)
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (current(id, generation)) _state.value = _state.value.copy(error = "操作失败，请返回列表重试")
        }
    }
    companion object { internal const val SELECTED_ID = "reader.selectedArticleId" }
}
