package com.fluxa.app.ui.feedlist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluxa.app.data.directrss.*
import com.fluxa.app.domain.model.Subscription
import com.fluxa.app.ui.components.IconAction
import kotlinx.coroutines.*

/** Production routes supply repository operations. No synthetic fallback results. */
data class LocalSubscriptionActions(
    val discover: suspend (String) -> FeedDiscovery = { error("本地 RSS 不可用") },
    val preview: suspend (String) -> RssFeed = { error("本地 RSS 不可用") },
    val createFolder: suspend (String) -> String = { error("本地 RSS 不可用") },
    val organize: suspend (Subscription) -> Unit = { error("本地 RSS 不可用") }
)

@Composable
internal fun AddSubscriptionDialog(busy: Boolean, message: String?, onClose: () -> Unit,
    onAdd: (String) -> Unit, onLocal: (String, Boolean) -> Unit,
    actions: LocalSubscriptionActions = LocalSubscriptionActions(), initialUrl: String = "", localOnly: Boolean = false,
    onUrlChange: (String) -> Unit = {}) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var local by rememberSaveable { mutableStateOf(true) }
    var daily by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf(emptyList<FeedCandidate>()) }
    var selected by remember { mutableStateOf<FeedCandidate?>(null) }
    var preview by remember { mutableStateOf<RssFeed?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    fun invalidate() {
        generation++; job?.cancel(); loading = false; candidates = emptyList(); selected = null; preview = null; error = null; attempted = false
    }
    fun discover(value: String, dailyOnly: Boolean) {
        invalidate(); daily = dailyOnly
        val token = generation
        loading = true
        job = scope.launch {
            try {
                val result = actions.discover(value)
                ensureActive()
                if (generation == token) {
                    candidates = result.candidates
                    selected = if (result.directFeed != null) result.candidates.single() else null
                    preview = result.directFeed
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == token) error = e.message ?: "发现失败" }
            finally { if (generation == token) loading = false }
        }
    }
    fun choose(candidate: FeedCandidate) {
        job?.cancel(); generation++; val token = generation
        selected = candidate; preview = null; error = null; loading = true
        job = scope.launch {
            try {
                val result = actions.preview(candidate.url)
                ensureActive()
                if (generation == token) preview = result
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == token) error = e.message ?: "预览失败" }
            finally { if (generation == token) loading = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) { invalidate(); onClose() } }, title = { Text("添加 RSS 订阅") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!localOnly) Row {
                IconAction("本地 RSS", Icons.Outlined.RssFeed, { invalidate(); local = true }, enabled = !busy, selected = local)
                IconAction("NewsBlur", Icons.Outlined.Cloud, { invalidate(); local = false }, enabled = !busy, selected = !local)
            }
            Text(if (local) "无需账号 · 仅保存在此设备" else "NewsBlur 账号订阅；需登录。")
            if (local) {
                Text("爱范儿早报 · 官方全文源 · 筛选早报", style = MaterialTheme.typography.bodySmall)
                IconAction("订阅爱范儿早报", Icons.Outlined.RssFeed, {
                    url = IFANR_FEED; onUrlChange(url); discover(url, true)
                }, enabled = !busy)
            }
            OutlinedTextField(url, { invalidate(); url = it.take(8192); onUrlChange(url); daily = false }, label = { Text("HTTP / HTTPS RSS 地址") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, autoCorrectEnabled = false),
                enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (local) Text("建议使用 HTTPS；HTTP 公开订阅以明文传输，不用于账号登录。", style = MaterialTheme.typography.bodySmall)
            if (local) {
                Text(if (daily) "早报专选" else "全站模式 · 先发现，再预览确认", style = MaterialTheme.typography.bodySmall)
                IconAction("发现订阅源", Icons.Outlined.Search, { discover(url, false) }, enabled = url.isNotBlank() && !busy)
                candidates.forEach { candidate ->
                    Row {
                        Column(Modifier.weight(1f)) { Text(candidate.title); Text(candidate.url, style = MaterialTheme.typography.bodySmall) }
                        IconAction("预览 ${candidate.title}", Icons.Outlined.Visibility, { choose(candidate) }, enabled = !busy,
                            selected = selected == candidate)
                    }
                }
                preview?.let { feed ->
                    Text(feed.title, style = MaterialTheme.typography.titleMedium)
                    val items = feed.items.filter { !daily || isDaily(it) }
                    Text("${items.size} 篇 · 实际来源预览", style = MaterialTheme.typography.bodySmall)
                    items.take(5).forEach { Text(it.title, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            if (loading || busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (busy) "正在订阅…" else "正在预览…")
            }
            (error ?: message?.takeIf { attempted })?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        IconAction(if (local) "确认订阅" else "订阅", Icons.Outlined.Check, {
            attempted = true
            if (local) selected?.let { onLocal(it.url, daily) } else onAdd(url)
        }, enabled = !busy && !loading && if (local) preview != null && selected != null else url.isNotBlank())
    }, dismissButton = { IconAction("取消", Icons.Outlined.Close, { invalidate(); onClose() }, enabled = !busy) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalSubscriptionManager(sources: List<Subscription>, folders: List<DirectFolder>,
    actions: LocalSubscriptionActions, onClose: () -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    var alias by rememberSaveable { mutableStateOf("") }
    var folder by remember { mutableStateOf<String?>(null) }
    var folderName by rememberSaveable { mutableStateOf("") }
    var undo by remember { mutableStateOf<Subscription?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onClose() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("管理本地订阅", style = MaterialTheme.typography.headlineSmall)
            Text("暂停不刷新；取消订阅保留已有文章、已读与收藏。NewsBlur 请在服务端管理。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(folderName, { folderName = it }, label = { Text("新文件夹名称") }, enabled = !busy)
            IconAction("创建文件夹", Icons.Outlined.CreateNewFolder, { run { actions.createFolder(folderName); folderName = "" } }, enabled = !busy && folderName.isNotBlank())
            sources.filter { it.isLocal }.forEach { source ->
                HorizontalDivider()
                Text(source.title, style = MaterialTheme.typography.titleMedium)
                Text(source.url, style = MaterialTheme.typography.bodySmall)
                if (source.status.isNotBlank()) Text(source.status, style = MaterialTheme.typography.bodySmall)
                Text((folders.firstOrNull { it.id == source.folderId }?.name ?: "未分组") +
                    if (source.archived) " · 已取消订阅" else if (source.paused) " · 已暂停" else "", style = MaterialTheme.typography.bodySmall)
                Row {
                    IconAction("编辑 ${source.title}", Icons.Outlined.Edit, {
                        editing = source.id; alias = source.alias.orEmpty(); folder = source.folderId
                    }, enabled = !busy)
                    if (!source.archived) IconAction(if (source.paused) "恢复 ${source.title}" else "暂停 ${source.title}",
                        if (source.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        { run { actions.organize(source.copy(paused = !source.paused)) } }, enabled = !busy)
                    IconAction(if (source.archived) "重新订阅 ${source.title}" else "取消订阅 ${source.title}",
                        if (source.archived) Icons.Outlined.Restore else Icons.Outlined.Unsubscribe,
                        { run {
                            actions.organize(source.copy(archived = !source.archived))
                            undo = if (!source.archived) source else null
                        } }, enabled = !busy)
                }
                if (editing == source.id) {
                    OutlinedTextField(alias, { alias = it }, label = { Text("显示别名（留空恢复原标题）") }, enabled = !busy)
                    Row { Text("未分组", Modifier.weight(1f)); IconAction("移至未分组", Icons.Outlined.FolderOff, { folder = null }, enabled = !busy, selected = folder == null) }
                    folders.forEach { target ->
                        Row { Text(target.name, Modifier.weight(1f)); IconAction("移至 ${target.name}", Icons.Outlined.Folder, { folder = target.id }, enabled = !busy, selected = folder == target.id) }
                    }
                    IconAction("保存订阅设置", Icons.Outlined.Check, { run {
                        actions.organize(source.copy(alias = alias, folderId = folder)); editing = null
                    } }, enabled = !busy)
                    IconAction("取消编辑", Icons.Outlined.Close, { editing = null }, enabled = !busy)
                }
            }
            undo?.let { previous ->
                Text("已取消订阅 ${previous.title}", style = MaterialTheme.typography.bodySmall)
                IconAction("撤销取消订阅", Icons.AutoMirrored.Outlined.Undo, { run {
                    // Restore only archive state; later edits are not overwritten.
                    val current = sources.firstOrNull { it.id == previous.id } ?: previous
                    actions.organize(current.copy(archived = false)); undo = null
                } }, enabled = !busy)
            }
            error?.let { Text(it) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            IconAction("关闭管理", Icons.Outlined.Close, onClose, enabled = !busy)
        }
    }
}
