package com.fluxa.app.ui.github

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxa.app.data.github.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GitHubViewModel @Inject constructor(private val repository: GitHubRepository) : ViewModel() {
    val settings = repository.dao.observeSettings().map { it ?: GitHubSettings() }.stateIn(viewModelScope, SharingStarted.Eagerly, GitHubSettings())
    // Wait for the persisted selection, rather than generating a default-user batch during startup.
    private val profile = repository.dao.observeSettings().map { it?.active ?: "vincent1993" }.distinctUntilChanged()
    val repos = profile.flatMapLatest { repository.dao.observeRepos(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val progress = profile.flatMapLatest { repository.dao.observeProfile(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val batches = profile.flatMapLatest { repository.dao.observeDaily(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val clock = MutableStateFlow(System.currentTimeMillis())
    init {
        viewModelScope.launch { while (true) { clock.value = System.currentTimeMillis(); repository.daily(repository.dao.settings()?.active ?: "vincent1993"); delay(30_000) } }
        viewModelScope.launch { profile.collect { repository.daily(it) } }
    }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch { try { block(); message.value = null } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; message.value = e.message ?: "本地操作失败" } } }
    fun select(raw: String) = action { repository.select(raw) }
    fun importStars() {
        if (busy.value) return
        val user = settings.value.active
        busy.value = true
        action { try { repository.importStars(user) } finally { busy.value = false } }
    }
    fun restart() { val user = settings.value.active; action { repository.restart(user) } }
    fun change() { val user = settings.value.active; action { repository.daily(user, true) } }
    fun seen(user: String, id: Long) = action { repository.markSeen(user, id) }
    fun tags(user: String, id: Long, tags: List<String>?) = action { repository.editTags(user, id, tags) }
}
