package com.fluxa.app.ui.feedlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxa.app.data.directrss.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import com.fluxa.app.data.local.ReaderPreferences
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

enum class ImportStatus { New, Existing, Archived, Invalid, Running, Done, Failed }
data class ImportRow(val record: OpmlRecord, val status: ImportStatus, val selected: Boolean = status == ImportStatus.New, val error: String? = record.error)
data class TransferState(
    val open: Boolean = false, val shared: Boolean = false, val url: String = "", val choices: List<String> = emptyList(),
    val rows: List<ImportRow> = emptyList(), val page: SavedPage? = null, val busy: Boolean = false,
    val message: String? = null, val savedId: String? = null, val website: Boolean = false
)

@HiltViewModel
class LocalTransferViewModel @Inject constructor(
    private val repository: DirectRssRepository,
    private val reader: ReaderPreferences
) : ViewModel() {
    suspend fun hasLocalContent(): Boolean = repository.hasSubscriptions() || repository.articles().first().isNotEmpty()

    /** A share is outside the list query: freeze a one-article session, never reuse an old queue. */
    suspend fun prepareReader(id: String): Boolean {
        val token = generation
        if (mutable.value.savedId != id || repository.articles().first().none { it.id == id }) return false
        reader.ready.first { it }
        if (token != generation || mutable.value.savedId != id) return false
        if (!reader.awaitAndFreeze(listOf(id), id)) return false
        return token == generation && mutable.value.savedId == id
    }
    private val mutable = MutableStateFlow(TransferState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    private fun cancelWork() { generation++; job?.cancel(); job = null }
    fun close() { cancelWork(); mutable.value = TransferState() }
    fun openImport() { close(); mutable.value = TransferState(open = true) }
    fun receive(links: SharedLinks) {
        close()
        mutable.value = TransferState(open = true, shared = true, url = links.urls.singleOrNull().orEmpty(), choices = links.urls, message = links.message)
    }
    fun editUrl(url: String) {
        cancelWork()
        mutable.update { it.copy(url = url.take(8192), busy = false, page = null, savedId = null, message = null, website = false) }
    }
    fun documentTicket(): Int = generation
    fun acceptsDocument(ticket: Int?): Boolean = ticket == generation && mutable.value.open && !mutable.value.shared
    fun editWebsiteUrl(url: String) { mutable.update { it.copy(url = url.take(8192), page = null, savedId = null) } }
    fun website(show: Boolean) { cancelWork(); mutable.update { it.copy(website = show, busy = false, message = null) } }
    fun select(index: Int, selected: Boolean) {
        if (mutable.value.busy) return
        mutable.update { state -> state.copy(rows = state.rows.mapIndexed { i, row -> if (i == index && row.status in setOf(ImportStatus.New, ImportStatus.Failed)) row.copy(selected = selected) else row }) }
    }
    private fun work(block: suspend (Int) -> Unit) {
        if (mutable.value.busy) return
        val token = ++generation
        mutable.update { it.copy(busy = true, message = null) }
        job = viewModelScope.launch {
            try { block(token) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) mutable.update { it.copy(message = e.message ?: "操作失败，请重试") } }
            finally { if (token == generation) mutable.update { it.copy(busy = false) } }
        }
    }
    fun stopImport() {
        cancelWork()
        mutable.update { state -> state.copy(busy = false, message = "已停止后续导入；已完成的订阅保留", rows = state.rows.map {
            if (it.status == ImportStatus.Running) it.copy(status = ImportStatus.Failed, error = "已取消；重试会重新核对是否已存在") else it
        }) }
    }
    fun readOpml(open: () -> InputStream?) = work { token ->
        val records = withContext(Dispatchers.IO) { requireNotNull(open()) { "无法打开文件" }.use { DirectOpml.parse(DirectOpml.read(it)) } }
        val sources = repository.localSources().associateBy { it.id }
        currentCoroutineContext().ensureActive()
        if (token == generation) mutable.update { it.copy(rows = records.map { record ->
            val existing = sources[record.identity]
            val status = when { record.error != null -> ImportStatus.Invalid; existing?.archived == true -> ImportStatus.Archived; existing != null -> ImportStatus.Existing; else -> ImportStatus.New }
            ImportRow(record, status)
        }, message = if (records.isEmpty()) "文件中没有订阅" else null) }
    }
    fun exportOpml(open: () -> OutputStream?) = work { token ->
        val bytes = withContext(Dispatchers.IO) { repository.exportOpml() }
        withContext(Dispatchers.IO) { requireNotNull(open()) { "无法写入文件" }.use { it.write(bytes); it.flush() } }
        if (token == generation) mutable.update { it.copy(message = "已导出订阅（不含文章、账号或凭据）") }
    }
    fun importSelected(retry: Boolean = false) = work { token ->
        val indices = mutable.value.rows.indices.filter { index ->
            val row = mutable.value.rows[index]
            row.selected && row.status == if (retry) ImportStatus.Failed else ImportStatus.New
        }
        for (index in indices) {
            currentCoroutineContext().ensureActive()
            if (token != generation) break
            val record = mutable.value.rows[index].record
            fun update(status: ImportStatus, error: String? = null) {
                if (token == generation) mutable.update { state -> state.copy(rows = state.rows.mapIndexed { i, row ->
                    if (i == index) row.copy(status = status, error = error) else row
                }) }
            }
            update(ImportStatus.Running)
            try {
                val added = repository.importRecord(record)
                currentCoroutineContext().ensureActive()
                update(if (added) ImportStatus.Done else ImportStatus.Existing)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { update(ImportStatus.Failed, e.message ?: "导入失败") }
        }
    }
    fun extract() = work { token ->
        val url = requireNotNull(safeUrl(mutable.value.url)) { "请输入安全的 HTTP(S) 网址" }
        val page = repository.extractPage(url)
        currentCoroutineContext().ensureActive()
        if (token == generation) mutable.update { it.copy(page = page) }
    }
    fun save() = work { token ->
        val page = requireNotNull(mutable.value.page)
        val id = repository.savePage(page)
        currentCoroutineContext().ensureActive()
        if (token == generation) mutable.update { it.copy(savedId = id, message = "已保存到稍后读") }
    }
    fun subscribe(url: String, daily: Boolean) = work { token ->
        // A share must not reset an existing feed's pause/archive/alias/folder settings.
        val added = repository.importRecord(OpmlRecord(url, url, null, daily))
        if (token == generation) mutable.update { it.copy(website = false, message = if (added) "已订阅" else "订阅已存在；已取消的订阅请在管理中恢复") }
    }
    val actions = LocalSubscriptionActions(discover = { repository.discover(it) }, preview = { repository.preview(it) })
}
