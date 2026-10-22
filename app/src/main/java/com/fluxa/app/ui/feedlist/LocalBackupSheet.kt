package com.fluxa.app.ui.feedlist

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxa.app.data.directrss.*
import com.fluxa.app.ui.components.IconAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

data class BackupUiState(val busy: Boolean = false, val preview: BackupPreview? = null,
    val pendingPreferences: BackupPayload? = null, val message: String = "选择文件后先预览，不会访问网络或修改资料库。")
@HiltViewModel
class LocalBackupViewModel @Inject constructor(private val backup: LocalBackup) : ViewModel() {
    private val mutable = MutableStateFlow(BackupUiState())
    val state = mutable.asStateFlow()
    // Opaque instance ticket survives rotation, never process death; no URI/payload in SavedState.
    private val instance = java.util.UUID.randomUUID().toString()
    private var generation = 0
    private var job: Job? = null
    fun ticket(): String = "$instance:$generation"
    fun accepts(ticket: String?): Boolean = ticket == ticket() && !mutable.value.busy
    fun reselect() { mutable.value = mutable.value.copy(message = "文件选择已失效，请重新选择；原有数据保持不变。") }
    fun close() { if (!mutable.value.busy) { generation++; mutable.value = BackupUiState() } }
    private fun work(failureMessage: String = "恢复未完成，请重新预览后重试。", block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        job = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val detail = if (e is BackupValidationException) e.message else safeBackupDetail(e)
                mutable.value = mutable.value.copy(message = if (detail == null) failureMessage else "$detail；$failureMessage")
            }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun read(open: () -> InputStream?) = work("未能读取或预览备份，原有数据未改变。请检查文件及读取权限，重新选择备份文件。") {
        mutable.value = mutable.value.copy(preview = null, pendingPreferences = null)
        val bytes = BackupJson.read(requireNotNull(open()) { "无法打开文件" })
        val preview = backup.preview(bytes)
        mutable.value = mutable.value.copy(preview = preview, message = "预览完成，尚未恢复。已有文章标记与设置优先；不删除任何内容。")
    }
    fun export(bodies: Boolean, images: Boolean, open: () -> OutputStream?) = work("未能完成导出，目标文件可能不完整。请检查存储空间及写入权限，重新选择保存位置。") {
        val bytes = backup.export(bodies, images)
        requireNotNull(open()) { "无法创建文件" }.use { it.write(bytes); it.flush() }
        mutable.value = mutable.value.copy(message = "已写入备份（${bytes.size} 字节）。")
    }
    fun confirm() = work {
        val preview = requireNotNull(mutable.value.preview)
        backup.restore(preview)
        mutable.value = mutable.value.copy(preview = null, pendingPreferences = preview.payload,
            message = "文章已恢复，正在合并设置。")
        preferences(preview.payload)
    }
    private suspend fun preferences(payload: BackupPayload) {
        try {
            backup.restorePreferences(payload)
            mutable.value = mutable.value.copy(pendingPreferences = null, message = "本地恢复完成；已有标记与设置保留。未包含的正文可刷新订阅获取。")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = mutable.value.copy(message = "文章已恢复，但设置尚未全部保存。请重试设置；不会再次导入文章。") }
    }
    fun retryPreferences() = work { mutable.value.pendingPreferences?.let { preferences(it) } }
}

@Composable
private fun BackupScopeRow(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    // Touch and accessibility use the same guarded transition, never a separate semantic state.
    val change: (Boolean) -> Boolean = { value ->
        if (enabled) {
            onCheckedChange(value)
            true
        } else false
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch,
            onValueChange = { change(it) })
        // A named leaf avoids Android's synthetic description child for merging parents.
        .clearAndSetSemantics {
            contentDescription = label
            role = Role.Switch
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            if (!enabled) disabled()
            onClick { change(!checked) }
        }) {
        Text(label, Modifier.weight(1f).clearAndSetSemantics {})
        Switch(checked = checked, onCheckedChange = null, enabled = enabled,
            modifier = Modifier.clearAndSetSemantics {})
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalBackupSheet(close: () -> Unit, onRestored: () -> Unit, model: LocalBackupViewModel = hiltViewModel()) {
    val state by model.state.collectAsState()
    val resolver = LocalContext.current.contentResolver
    var bodies by rememberSaveable { mutableStateOf(false) }
    var images by rememberSaveable { mutableStateOf(false) }
    var openTicket by rememberSaveable { mutableStateOf<String?>(null) }
    var createTicket by rememberSaveable { mutableStateOf<String?>(null) }
    var exportBodies by rememberSaveable { mutableStateOf(false) }
    var exportImages by rememberSaveable { mutableStateOf(false) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { if (model.accepts(openTicket)) model.read { resolver.openInputStream(uri) } else model.reselect() }
        openTicket = null
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) { if (model.accepts(createTicket)) model.export(exportBodies, exportImages) { resolver.openOutputStream(uri, "wt") } else model.reselect() }
        createTicket = null
    }
    ModalBottomSheet(onDismissRequest = { if (!state.busy) { model.close(); onRestored(); close() } },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row { Text("本地备份", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconAction("关闭备份", Icons.Outlined.Close, { model.close(); onRestored(); close() }, enabled = !state.busy) }
            Text("订阅、文件夹、别名、暂停与归档、正文模式、离线保留设置、显示与滑动偏好、搜索与规则、已读与收藏、稍后读、笔记和阅读位置。")
            Text("不含账号、凭据、通知订阅或后台开关。仅合并，不覆盖已有状态，不删除数据。文件可能包含私人笔记，请妥善保存。", style = MaterialTheme.typography.bodySmall)
            BackupScopeRow("包含 RSS 与已提取全文", bodies, !state.busy) {
                bodies = it
                if (!it) images = false
            }
            BackupScopeRow("另含已保存图片", images, bodies && !state.busy) { images = it }
            Text("默认仅文章信息；没有正文的文章不会显示为已离线保存。上限 32 MiB、5000 篇文章、500 个订阅；每篇正文 1.4 MB，每张图片 1 MiB。", style = MaterialTheme.typography.bodySmall)
            Row {
                IconAction("导出本地备份", Icons.Outlined.FileUpload, {
                    exportBodies = bodies; exportImages = images; createTicket = model.ticket(); create.launch("fluxa-local-v1.json")
                }, enabled = !state.busy && state.pendingPreferences == null)
                IconAction("选择备份预览", Icons.Outlined.FileDownload, { openTicket = model.ticket(); open.launch(arrayOf("application/json", "text/plain")) }, enabled = !state.busy && state.pendingPreferences == null)
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.message)
            state.preview?.let { p ->
                Text("版本 1 · ${p.bytes} 字节\n新增 ${p.newSources} 个订阅、${p.newFolders} 个文件夹、${p.newArticles} 篇文章、${p.newNotes} 条笔记\n${p.collisions} 篇已有文章保持原状态")
                Text("正文：${if (p.payload.bodies) "包含" else "不包含"} · 图片：${if (p.payload.images) "包含" else "不包含"}\n${p.payload.modes.size} 项正文模式、${p.payload.articlePins.size + p.payload.folderPins.size} 项保留、${p.payload.triage.saved.size} 个搜索、${p.payload.triage.rules.size} 条规则、${p.payload.reader.positions.size} 项阅读位置、${p.payload.reader.session.ids.size} 篇阅读队列（仅补充缺失项）")
                IconAction("确认合并备份", Icons.Outlined.Check, model::confirm, enabled = !state.busy)
            }
            if (state.pendingPreferences != null) IconAction("重试设置恢复", Icons.Outlined.Refresh, model::retryPreferences, enabled = !state.busy)
            Text("预览与文件选择仅在本次进程有效。退出或系统关闭后请重新选择；已提交的文章不会撤回。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
