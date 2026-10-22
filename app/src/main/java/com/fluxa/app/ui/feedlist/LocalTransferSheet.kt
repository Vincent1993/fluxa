package com.fluxa.app.ui.feedlist

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fluxa.app.data.directrss.*
import com.fluxa.app.ui.components.IconAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalTransferSheet(model: LocalTransferViewModel, onOpenArticle: (String) -> Unit) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val resolver = context.contentResolver
    var openTicket by rememberSaveable { mutableStateOf<Int?>(null) }
    var createTicket by rememberSaveable { mutableStateOf<Int?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && model.acceptsDocument(openTicket)) model.readOpml { resolver.openInputStream(uri) }
        openTicket = null
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        if (uri != null && model.acceptsDocument(createTicket)) model.exportOpml { resolver.openOutputStream(uri, "wt") }
        createTicket = null
    }
    if (!state.open) return
    if (state.website) {
        AddSubscriptionDialog(state.busy, state.message, { model.website(false) }, {}, model::subscribe,
            model.actions, initialUrl = state.url, localOnly = true, onUrlChange = model::editWebsiteUrl)
        return
    }
    ModalBottomSheet(onDismissRequest = model::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row {
                    Text(if (state.shared) "浏览器分享" else "OPML 订阅", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconAction("关闭", Icons.Outlined.Close, model::close)
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.message?.let { Text(it) }
            }
            if (state.shared) {
                item {
                    OutlinedTextField(state.url, model::editUrl, label = { Text("HTTP / HTTPS 网址") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("选择后才会访问网站；保存文章需要预览后确认。", style = MaterialTheme.typography.bodySmall)
                }
                itemsIndexed(state.choices) { _, url ->
                    Row { Text(url, Modifier.weight(1f)); IconAction("选择 $url", Icons.Outlined.Check, { model.editUrl(url) }) }
                }
                item {
                    Row {
                        IconAction("订阅网站", Icons.Outlined.RssFeed, { model.website(true) }, enabled = !state.busy && safeUrl(state.url) != null)
                        IconAction("保存文章", Icons.Outlined.BookmarkAdd, model::extract, enabled = !state.busy && safeUrl(state.url) != null)
                        IconAction("打开原网页", Icons.Outlined.OpenInNew, {
                            safeUrl(state.url)?.let { url ->
                                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                catch (_: android.content.ActivityNotFoundException) { android.widget.Toast.makeText(context, "没有可打开网页的应用", android.widget.Toast.LENGTH_SHORT).show() }
                            }
                        }, enabled = safeUrl(state.url) != null)
                    }
                }
                state.page?.let { page ->
                    item {
                        Row {
                            Text(page.title, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconAction("确认保存文章", Icons.Outlined.Check, model::save, enabled = !state.busy && state.savedId == null)
                        }
                        Text(page.url, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("正文节选预览 · 确认后保存完整提取正文", style = MaterialTheme.typography.labelMedium)
                        Text(remember(page.body) { DirectHtml.text(page.body).take(240) }, maxLines = 4,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                state.savedId?.let { id -> item { IconAction("阅读已保存文章", Icons.Outlined.MenuBook, { onOpenArticle(id) }) } }
            } else {
                item {
                    Text("仅导入和导出订阅，不是账号备份。嵌套文件夹展开为路径（最多 80 字符）。已存在或已取消的订阅保持不变。", style = MaterialTheme.typography.bodySmall)
                    Row {
                        IconAction("选择 OPML 文件", Icons.Outlined.FileOpen, { openTicket = model.documentTicket(); open.launch(arrayOf("text/*", "application/xml", "application/octet-stream")) }, enabled = !state.busy)
                        IconAction("导出 OPML", Icons.Outlined.FileUpload, { createTicket = model.documentTicket(); create.launch("Fluxa-subscriptions.opml") }, enabled = !state.busy)
                    }
                    Text("新增 ${state.rows.count { it.status == ImportStatus.New }} · 已存在 ${state.rows.count { it.status in setOf(ImportStatus.Existing, ImportStatus.Archived) }} · 无效 ${state.rows.count { it.status == ImportStatus.Invalid }}")
                    Row {
                        IconAction("确认导入所选订阅", Icons.Outlined.Check, { model.importSelected() }, enabled = !state.busy && state.rows.any { it.selected && it.status == ImportStatus.New })
                        IconAction("仅重试失败订阅", Icons.Outlined.Refresh, { model.importSelected(true) }, enabled = !state.busy && state.rows.any { it.selected && it.status == ImportStatus.Failed })
                        if (state.busy) IconAction("停止后续导入", Icons.Outlined.Stop, model::stopImport)
                    }
                }
                itemsIndexed(state.rows) { index, row -> ImportPreviewRow(row) { model.select(index, it) } }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
internal fun ImportPreviewRow(row: ImportRow, onSelect: (Boolean) -> Unit) {
    Row {
        Column(Modifier.weight(1f)) {
            Text(row.record.name, style = MaterialTheme.typography.titleSmall)
            Text(row.record.url, style = MaterialTheme.typography.bodySmall)
            row.record.folder?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(when (row.status) {
                ImportStatus.New -> "待导入"; ImportStatus.Existing -> "已存在，未修改"; ImportStatus.Archived -> "已取消订阅，跳过；可在管理中恢复"
                ImportStatus.Invalid -> "无效"; ImportStatus.Running -> "正在导入…"; ImportStatus.Done -> "已导入"; ImportStatus.Failed -> "失败，可重试"
            }, style = MaterialTheme.typography.bodySmall)
            row.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (row.status in setOf(ImportStatus.New, ImportStatus.Failed))
            IconAction("选择 ${row.record.name}", if (row.selected) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                { onSelect(!row.selected) }, selected = row.selected)
    }
}
