package com.fluxa.app.ui.feedlist

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fluxa.app.data.directrss.LocalFeedNotifications
import com.fluxa.app.domain.model.Subscription
import com.fluxa.app.ui.components.IconAction
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalNotificationSheet(service: LocalFeedNotifications, sources: List<Subscription>, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) revision++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun enable(id: String, value: Boolean) {
        scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { service.enable(id, value) }; message = if (value) "来源已选择通知；仍受系统权限、频道及安静时段控制" else "来源通知已关闭"; revision++ }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "通知设置未保存" }
            finally { busy = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val source = pending; pending = null
        if (granted && source != null && sources.any { it.id == source && it.isLocal && !it.archived }) enable(source, true)
        else message = "通知权限未授予，来源未启用；可主动打开系统设置"
        revision++
    }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        @Suppress("UNUSED_VARIABLE") val currentRevision = revision
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("来源通知", style = MaterialTheme.typography.titleLarge)
                Text("默认关闭。仅新到的公开订阅文章汇总，不发送首次导入或逐篇通知。22:00–08:00 使用当前本地时区安静积累；之后在下一次前台或后台刷新机会尝试发送，不保证准点。")
                Text("系统权限：${if (service.permissionGranted()) "已允许" else "未允许"} · 频道：${if (service.channelEnabled()) "可用" else "被系统关闭"}")
                message?.let { Text(it) }
                IconAction("打开系统通知设置", Icons.Outlined.Settings, {
                    runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                        .onFailure { message = "此设备没有通知设置入口" }
                })
            }
            items(sources.filter { it.isLocal && !it.archived }, key = { it.id }) { source ->
                val enabled = service.enabled(source.id)
                Row {
                    Column(Modifier.weight(1f)) { Text(source.title); Text(service.detail(source.id), style = MaterialTheme.typography.bodySmall) }
                    IconAction("${if (enabled) "关闭" else "启用"}来源通知 ${source.title}", Icons.Outlined.Notifications, {
                        if (!enabled && Build.VERSION.SDK_INT >= 33 && !service.permissionGranted()) {
                            pending = source.id; permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else enable(source.id, !enabled)
                    }, selected = enabled, enabled = !busy && pending == null)
                }
            }
            item { IconAction("关闭来源通知设置", Icons.Outlined.Close, close); Spacer(Modifier.height(24.dp)) }
        }
    }
}
