package com.fluxa.app.ui.feedlist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.components.IconAction
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TriageSheet(state: FeedListState, apply: (SearchScope) -> Unit, configure: (TriageConfig) -> Unit,
    preview: suspend (TriageRule) -> List<Article>, close: () -> Unit) {
    val jobs = rememberCoroutineScope()
    var previewJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var after by rememberSaveable { mutableStateOf(state.scope.after?.let { java.time.Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }.orEmpty()) }
    var before by rememberSaveable { mutableStateOf(state.scope.before?.let { java.time.Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }.orEmpty()) }
    LaunchedEffect(state.scope.after, state.scope.before) {
        after = state.scope.after?.let { java.time.Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }.orEmpty()
        before = state.scope.before?.let { java.time.Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }.orEmpty()
    }
    var value by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(RuleKind.KeepCategory) }
    var global by rememberSaveable { mutableStateOf(false) }
    var candidate by remember { mutableStateOf<TriageRule?>(null) }
    var hits by remember { mutableStateOf<List<Article>>(emptyList()) }
    var removed by remember { mutableStateOf<TriageRule?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var previewBusy by remember { mutableStateOf(false) }
    val source = state.scope.source
    val config = state.triage
    LaunchedEffect(source, kind, global, value) { previewJob?.cancel(); candidate = null }
    fun showPreview(rule: TriageRule, confirm: Boolean) {
        previewJob?.cancel()
        candidate = null
        previewJob = jobs.launch {
            previewBusy = true
            try {
                hits = preview(rule)
                if (confirm) candidate = rule
                else error = "命中 ${hits.size} 篇：" + hits.take(5).joinToString("；") { it.title }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = "无法预览，请重试" }
            finally { previewBusy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row { Text("筛选与规则 · 仅本地内容", Modifier.weight(1f)); IconAction("关闭筛选", Icons.Outlined.Close, close) }
            Text("当前：${state.scope.query.ifBlank { "无关键词" }} · ${state.filter.label} · ${source?.let { id -> state.subscriptions.firstOrNull { it.id == id }?.title ?: "缺失来源（仍保留筛选）" } ?: "全部来源"}")
            Text("未读 ${state.unreadCount} · 收藏 ${state.starredCount}（当前来源、时间、关键词与规则范围，折叠前）")
            IconAction("重置当前筛选", Icons.Outlined.RestartAlt, { apply(SearchScope()); after = ""; before = "" })
            Row {
                Text(if (state.scope.showHidden) "显示隐藏项：忽略隐藏已读及规则" else "遵循各源隐藏已读及规则", Modifier.weight(1f))
                IconAction("显示隐藏项", Icons.Outlined.Visibility, { apply(state.scope.copy(showHidden = !state.scope.showHidden)) }, selected = state.scope.showHidden)
            }
            Row {
                Text("全局列表外观", Modifier.weight(1f))
                IconAction(if (config.comfortable) "列表密度：舒适" else "列表密度：紧凑", Icons.Outlined.ViewAgenda,
                    { configure(config.copy(comfortable = !config.comfortable)) }, selected = config.comfortable)
                IconAction("显示文章摘要", Icons.Outlined.Subject, { configure(config.copy(showSummary = !config.showSummary)) }, selected = config.showSummary)
                IconAction("显示文章缩略图", Icons.Outlined.Image, { configure(config.copy(showThumbnail = !config.showThumbnail)) }, selected = config.showThumbnail)
            }
            Row {
                Text("折叠同链接副本（标记仅作用于显示副本）", Modifier.weight(1f))
                IconAction("折叠同链接", Icons.Outlined.Layers, { configure(config.copy(collapse = !config.collapse)) }, selected = config.collapse)
            }
            Text("来源（选择不联网）")
            IconAction("筛选全部来源", Icons.Outlined.SelectAll, { apply(state.scope.copy(source = null)) }, selected = source == null)
            state.subscriptions.forEach { s -> Row {
                Text(s.title + if (s.archived) " · 已取消" else if (s.paused) " · 已暂停" else "", Modifier.weight(1f))
                IconAction("筛选来源 ${s.title}", Icons.Outlined.Check, { apply(state.scope.copy(source = s.id)) }, selected = source == s.id)
            } }
            if (source?.startsWith("direct-rss:") == true) {
                val pref = config.sources[source] ?: SourceTriage()
                Row {
                    Text(if (pref.oldestFirst) "此来源：旧 → 新" else "此来源：新 → 旧", Modifier.weight(1f))
                    IconAction("切换此来源排序", Icons.Outlined.SwapVert, { configure(config.copy(sources = config.sources + (source to pref.copy(oldestFirst = !pref.oldestFirst)))) })
                }
                Row {
                    Text("此来源隐藏已读（全部视图也遵循）", Modifier.weight(1f))
                    IconAction("此来源隐藏已读", Icons.Outlined.VisibilityOff, { configure(config.copy(sources = config.sources + (source to pref.copy(hideRead = !pref.hideRead)))) }, selected = pref.hideRead)
                }
            }
            FlowRow { ArticleFilter.entries.filter { it != ArticleFilter.Saved }.forEach { filter -> IconAction(filter.label, when(filter) {
                ArticleFilter.All -> Icons.Outlined.SelectAll; ArticleFilter.Unread -> Icons.Outlined.MarkEmailUnread
                ArticleFilter.ReadLater, ArticleFilter.Saved -> Icons.Outlined.BookmarkAdd
                ArticleFilter.Archived -> Icons.Outlined.Archive
                ArticleFilter.Annotated -> Icons.Outlined.EditNote
                ArticleFilter.Starred -> Icons.Outlined.Star; ArticleFilter.Last24Hours -> Icons.Outlined.Schedule
            }, { apply(state.scope.copy(filter = filter)) }, selected = state.filter == filter) } }
            OutlinedTextField(after, { after = it.take(10) }, label = { Text("从日期（本地时区，含当天）") }, singleLine = true)
            OutlinedTextField(before, { before = it.take(10) }, label = { Text("至日期（本地时区，含当天）") }, singleLine = true)
            IconAction("应用日期范围", Icons.Outlined.DateRange, {
                runCatching {
                    val start = after.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() }
                    val end = before.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() - 1 }
                    apply(state.scope.copy(after = start, before = end))
                }.onFailure { error = "请输入有效 YYYY-MM-DD，开始不能晚于结束" }
            })
            var tag by rememberSaveable(state.scope.tag) { mutableStateOf(state.scope.tag) }
            OutlinedTextField(tag, { tag = it.take(80) }, label = { Text("本地标签") }, singleLine = true)
            IconAction("应用本地标签", Icons.Outlined.Label, { apply(state.scope.copy(tag = tag.trim())) })
            Text("保存完整筛选（最多 50 个）")
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("搜索名称") }, singleLine = true)
            IconAction("保存当前搜索", Icons.Outlined.BookmarkAdd, {
                configure(config.copy(saved = config.saved + SavedSearch(UUID.randomUUID().toString(), name.trim(), state.scope))); name = ""
            }, enabled = name.isNotBlank() && config.saved.size < 50)
            config.saved.forEach { saved -> Row {
                Text(saved.name + if (saved.scope.source != null && state.subscriptions.none { it.id == saved.scope.source }) " · 缺失来源" else "", Modifier.weight(1f))
                IconAction("应用搜索 ${saved.name}", Icons.Outlined.Search, { apply(saved.scope) })
                IconAction("删除搜索 ${saved.name}", Icons.Outlined.DeleteOutline, { configure(config.copy(saved = config.saved.filterNot { it.id == saved.id })) })
            } }
            HorizontalDivider()
            Text("保留 / 屏蔽规则：只隐藏，不删除正文或标记。多个保留规则满足任一即可；屏蔽优先。")
            Text("分类未知的旧文章：分类保留规则退回同一词的标题及纯正文匹配；有分类时优先分类。关键词规则始终匹配标题及纯正文。作者需完全匹配。")
            Row {
                Text(kind.label, Modifier.weight(1f))
                IconAction("切换规则类型", Icons.Outlined.Tune, { kind = RuleKind.entries[(kind.ordinal + 1) % RuleKind.entries.size]; candidate = null })
            }
            if (!kind.name.startsWith("Keep")) Row {
                Text(if (global) "作用于所有来源" else "仅当前来源", Modifier.weight(1f))
                IconAction("规则作用于所有来源", Icons.Outlined.Public, { global = !global; candidate = null }, selected = global)
            }
            OutlinedTextField(value, { value = it.take(256); candidate = null }, label = { Text("分类 / 关键词 / 作者") }, singleLine = true)
            IconAction("预览规则命中", Icons.Outlined.Preview, {
                val rule = TriageRule(UUID.randomUUID().toString(), if (global && !kind.name.startsWith("Keep")) null else source, kind, value.trim())
                showPreview(rule, true)
            }, enabled = value.isNotBlank() && (source != null || (global && !kind.name.startsWith("Keep"))))
            if (previewBusy) CircularProgressIndicator()
            candidate?.let { rule ->
                Text("命中 ${hits.size} 篇；${if (rule.kind.name.startsWith("Keep")) "保留匹配项，其他项隐藏" else "隐藏匹配项"}。只针对已保存内容。")
                hits.take(5).forEach { Text(it.title) }
                IconAction("确认启用规则", Icons.Outlined.Check, { configure(config.copy(rules = config.rules + rule)); candidate = null; value = "" }, enabled = config.rules.size < 100)
            }
            config.rules.forEach { rule ->
                Text("${rule.kind.label}：${rule.value} · ${if (rule.enabled) "已启用" else "已停用"} · ${rule.source?.let { id -> state.subscriptions.firstOrNull { it.id == id }?.title ?: "缺失来源" } ?: "全部来源"}")
                Row {
                    IconAction("${if (rule.enabled) "停用" else "启用"}规则 ${rule.value}", Icons.Outlined.PowerSettingsNew,
                        { configure(config.copy(rules = config.rules.map { if (it.id == rule.id) it.copy(enabled = !it.enabled) else it })) }, selected = rule.enabled)
                    IconAction("查看规则命中 ${rule.value}", Icons.Outlined.Visibility, { showPreview(rule, false) })
                    IconAction("删除规则 ${rule.value}", Icons.Outlined.DeleteOutline, { removed = rule; configure(config.copy(rules = config.rules.filterNot { it.id == rule.id })) })
                }
            }
            removed?.let { rule -> IconAction("撤销删除规则 ${rule.value}", Icons.Outlined.Undo, { configure(config.copy(rules = config.rules + rule)); removed = null }, enabled = config.rules.size < 100) }
            error?.let { Text(it) }
        }
    }
}
