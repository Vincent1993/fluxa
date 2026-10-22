package com.fluxa.app.ui.feedlist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluxa.app.data.directrss.*
import com.fluxa.app.ui.components.IconAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun mib(bytes: Long) = String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalOfflineSheet(
    initial: LocalOfflinePolicy, folders: List<DirectFolder>,
    save: suspend (LocalOfflinePolicy) -> Unit, usage: suspend () -> OfflineUsage,
    pin: suspend (String, Boolean) -> Unit, close: () -> Unit,
    isPinned: (String) -> Boolean = { false }
) {
    var policy by remember { mutableStateOf(initial) }
    var diagnostic by remember { mutableStateOf<OfflineUsage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block(); diagnostic = usage(); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "操作未完成" }
            finally { busy = false }
        }
    }
    fun change(value: LocalOfflinePolicy) = action { save(value); policy = value }
    LaunchedEffect(Unit) {
        try { diagnostic = usage() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "暂时无法读取容量" }
    }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row { Text("本地 RSS · 离线与后台", Modifier.weight(1f)); IconAction("关闭离线设置", Icons.Outlined.Close, close) }
            Text("正文随刷新自动保存。此策略不代表 NewsBlur 图片已离线。")
            Row {
                Text("容量软上限 ${policy.capacityMiB} MiB", Modifier.weight(1f))
                IconAction("切换本地缓存容量", Icons.Outlined.Storage, {
                    val values = listOf(64, 128, 256, 512)
                    change(policy.copy(capacityMiB = values[(values.indexOf(policy.capacityMiB) + 1) % values.size]))
                }, enabled = !busy)
            }
            Row {
                Text("已读保护 ${policy.retainedReadDays} 天", Modifier.weight(1f))
                IconAction("切换已读保留期限", Icons.Outlined.DateRange, {
                    val values = listOf(7, 30, 90)
                    change(policy.copy(retainedReadDays = values[(values.indexOf(policy.retainedReadDays) + 1) % values.size]))
                }, enabled = !busy)
            }
            Row {
                Text("自动保存图片", Modifier.weight(1f))
                IconAction("自动保存图片", Icons.Outlined.Image, { change(policy.copy(imagesEnabled = !policy.imagesEnabled)) }, selected = policy.imagesEnabled, enabled = !busy)
            }
            Row {
                Text(if (policy.imagesWifiOnly) "图片仅 Wi-Fi" else "图片允许蜂窝网络", Modifier.weight(1f))
                IconAction("切换图片网络规则", Icons.Outlined.Wifi, { change(policy.copy(imagesWifiOnly = !policy.imagesWifiOnly)) }, selected = policy.imagesWifiOnly, enabled = !busy)
            }
            Row {
                Text("本地 RSS 后台刷新", Modifier.weight(1f))
                IconAction("本地 RSS 后台刷新", Icons.Outlined.Sync, { change(policy.copy(backgroundEnabled = !policy.backgroundEnabled)) }, selected = policy.backgroundEnabled, enabled = !busy)
            }
            Text("约每小时尝试；需电量和存储不低，系统可延迟或跳过。仅 Wi-Fi 时后台要求不计费网络，图片另校验 Wi-Fi。")
            diagnostic?.let { Text("已用 ${mib(it.payloadBytes)} MiB / 软上限 ${mib(it.limitBytes)} MiB；受保护 ${mib(it.protectedBytes)} MiB；超出 ${mib(it.excessBytes)} MiB。始终保留 RSS 文字与用户状态；受保护内容可超过软上限。") }
            Text("文件夹离线保留：下载已有文章的图片；全文仅限已选全文模式的来源。取消保留不会立即删除。")
            folders.forEach { folder ->
                Row {
                    val pinned = isPinned(folder.id)
                    Text(folder.name + if (pinned) " · 已保留" else " · 未保留", Modifier.weight(1f))
                    IconAction(if (pinned) "取消文件夹离线保留 ${folder.name}" else "离线保留文件夹 ${folder.name}",
                        if (pinned) Icons.Outlined.RemoveCircleOutline else Icons.Outlined.Download,
                        { action { pin(folder.id, !pinned) } }, selected = pinned, enabled = !busy)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) CircularProgressIndicator()
        }
    }
}
