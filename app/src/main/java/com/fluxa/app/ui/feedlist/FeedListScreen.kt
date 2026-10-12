package com.fluxa.app.ui.feedlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxa.app.domain.model.Article
import com.fluxa.app.data.sync.BackgroundSyncSettings
import com.fluxa.app.data.sync.BackgroundSyncScheduler
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun FeedListRoute(onOpenArticle: (String) -> Unit, onLogin: () -> Unit, viewModel: FeedListViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { BackgroundSyncSettings(context) }
    val scheduler = remember(context) { BackgroundSyncScheduler(context, settings) }
    var backgroundEnabled by remember { mutableStateOf(settings.enabled) }
    FeedListScreen(state, onOpenArticle, onLogin, viewModel::refresh, viewModel::loadMore,
        viewModel::setQuery, viewModel::setFilter, viewModel::selectSource, viewModel::markRead,
        viewModel::toggleStar, viewModel::addSubscription, viewModel::retryPending,
        backgroundEnabled = backgroundEnabled, onBackgroundSync = { scheduler.setEnabled(it); backgroundEnabled = settings.enabled },
        onArchive = viewModel::archive, onSave = viewModel::save, onAdvanced = viewModel::setAdvancedFilters,
        onImportLegacy = viewModel::importLegacy)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedListScreen(state: FeedListState, onOpenArticle: (String) -> Unit, onLogin: () -> Unit,
    onRefresh: () -> Unit, onLoadMore: () -> Unit, onQuery: (String) -> Unit, onFilter: (ArticleFilter) -> Unit,
    onSource: (String?) -> Unit, onMarkRead: (String) -> Unit, onToggleStar: (String) -> Unit,
    onSubscribe: (String) -> Unit, onRetryPending: () -> Unit,
    backgroundEnabled: Boolean = false, onBackgroundSync: (Boolean) -> Unit = {},
    onArchive: (String) -> Unit = {}, onSave: (String) -> Unit = {},
    onAdvanced: (String, String, String) -> Boolean = { _,_,_ -> false }, onImportLegacy: () -> Unit = {}) {
    var showSources by rememberSaveable { mutableStateOf(false) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Fluxa") }, actions = {
            IconButton(onClick = { showSources = true }) { Icon(Icons.AutoMirrored.Outlined.List, "选择订阅") }
            IconButton(onClick = { showAdd = true }) { Icon(Icons.Outlined.Add, "添加订阅") }
            IconButton(onClick = onRefresh, enabled = !state.busy) { Icon(Icons.Outlined.Refresh, "刷新") }
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Settings, "账户与同步设置") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            OutlinedTextField(state.query, onQuery, label = { Text("搜索已缓存文章") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                ArticleFilter.entries.forEach { filter ->
                    IconToggleButton(state.filter == filter, { onFilter(filter) }) { Icon(filterIcon(filter), filter.label) }
                }
                IconButton(onClick = { showFilters = true }) { Icon(Icons.Outlined.FilterAlt, "标签与日期筛选") }
                if (state.pendingCount > 0) IconButton(onClick = onRetryPending) {
                    BadgedBox(badge = { Badge { Text(state.pendingCount.toString()) } }) { Icon(Icons.Outlined.SyncProblem, "重试待同步操作") }
                }
            }
            if (state.source != null || state.tag.isNotBlank() || state.dateFrom.isNotBlank() || state.dateTo.isNotBlank()) {
                Text(listOfNotNull(state.source?.let { state.subscriptions.firstOrNull { s -> s.id == it }?.title ?: "当前订阅" },
                    state.tag.takeIf { it.isNotBlank() }, state.dateFrom.takeIf { it.isNotBlank() }, state.dateTo.takeIf { it.isNotBlank() })
                    .joinToString(" · "), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelSmall)
            }
            state.message?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
            PullToRefreshBox(state.busy, onRefresh, Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                    if (state.articles.isEmpty()) item { Text("没有匹配的缓存文章", Modifier.padding(vertical = 24.dp)) }
                    items(state.articles, key = { it.id }) { article ->
                        ArticleRow(article, { onOpenArticle(article.id) }, { onMarkRead(article.id) },
                            { onToggleStar(article.id) }, { onSave(article.id) }, { onArchive(article.id) })
                    }
                    item { Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        IconButton(onClick = onLoadMore, enabled = !state.busy) { Icon(Icons.Outlined.ExpandMore, "加载更多") }
                    } }
                }
            }
        }
    }
    if (showSources) AlertDialog(onDismissRequest = { showSources = false }, title = { Text("订阅") }, text = {
        LazyColumn {
            item { ListItem(headlineContent = { Text("全部订阅") }, modifier = Modifier.clickable { onSource(null); showSources = false }) }
            items(state.subscriptions, key = { it.id }) { subscription ->
                ListItem(headlineContent = { Text(subscription.title) }, modifier = Modifier.clickable { onSource(subscription.id); showSources = false })
            }
        }
    }, confirmButton = { IconButton(onClick = { showSources = false }) { Icon(Icons.Outlined.Close, "关闭") } })
    if (showAdd) AddSubscriptionDialog(state.busy, { showAdd = false }) { onSubscribe(it); showAdd = false }
    if (showFilters) {
        var tag by rememberSaveable { mutableStateOf(state.tag) }
        var from by rememberSaveable { mutableStateOf(state.dateFrom) }
        var to by rememberSaveable { mutableStateOf(state.dateTo) }
        var invalid by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { showFilters = false }, title = { Text("标签与日期") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(tag, { tag = it }, label = { Text("标签（可留空）") })
                OutlinedTextField(from, { from = it }, label = { Text("起始日期 YYYY-MM-DD") })
                OutlinedTextField(to, { to = it }, label = { Text("结束日期 YYYY-MM-DD") })
                Text("日期按本机时区，包含起止两天。", style = MaterialTheme.typography.labelSmall)
                if (invalid) Text("日期无效或结束早于起始。", color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { IconButton(onClick = { if (onAdvanced(tag, from, to)) showFilters = false else invalid = true }) { Icon(Icons.Outlined.Check, "应用筛选") } },
            dismissButton = { IconButton(onClick = { onAdvanced("", "", ""); showFilters = false }) { Icon(Icons.Outlined.ClearAll, "清空筛选") } })
    }
    if (showSettings) AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("账户与同步") }, text = {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("后台刷新", Modifier.weight(1f).padding(top = 12.dp)); Switch(backgroundEnabled, onBackgroundSync)
            }
            Text("每小时；不计费网络且电量充足。笔记、标签、进度、归档和稍后读仅本机保存。", style = MaterialTheme.typography.bodySmall)
            Row {
                IconButton(onClick = { showSettings = false; onLogin() }) { Icon(Icons.Outlined.Login, "登录 NewsBlur") }
                IconButton(onClick = { onImportLegacy(); showSettings = false }) { Icon(Icons.Outlined.Restore, "保全导入当前渠道旧本地数据") }
            }
        }
    }, confirmButton = { IconButton(onClick = { showSettings = false }) { Icon(Icons.Outlined.Check, "完成") } })
}

private fun filterIcon(filter: ArticleFilter): ImageVector = when (filter) {
    ArticleFilter.All -> Icons.AutoMirrored.Outlined.List
    ArticleFilter.Unread -> Icons.Outlined.MarkEmailUnread
    ArticleFilter.Starred -> Icons.Outlined.StarBorder
    ArticleFilter.Saved -> Icons.Outlined.BookmarkBorder
    ArticleFilter.Archived -> Icons.Outlined.Archive
    ArticleFilter.Annotated -> Icons.Outlined.EditNote
}

/** Three increasing drag segments; actions also have screen-reader alternatives. */
@Composable
private fun ArticleRow(article: Article, onOpen: () -> Unit, onRead: () -> Unit, onStar: () -> Unit,
    onSave: () -> Unit, onArchive: () -> Unit) {
    val density = LocalDensity.current.density
    var offset by remember(article.id) { mutableFloatStateOf(0f) }
    val stage = when { abs(offset) / density >= 220 -> 3; abs(offset) / density >= 120 -> 2; abs(offset) / density >= 48 -> 1; else -> 0 }
    val actions by rememberUpdatedState(listOf(onRead, onStar, onSave, onArchive))
    Box {
        if (stage > 0) Icon(when {
            offset < 0 && stage == 1 -> Icons.Outlined.Archive
            stage == 2 && offset >= 0 -> Icons.Outlined.StarBorder
            stage == 3 && offset >= 0 || stage == 2 && offset < 0 -> Icons.Outlined.BookmarkBorder
            else -> Icons.Outlined.Done
        }, "手势第 $stage 段", Modifier.align(if (offset >= 0) androidx.compose.ui.Alignment.CenterStart else androidx.compose.ui.Alignment.CenterEnd))
        Surface(Modifier.fillMaxWidth().testTag("article-row-${article.id}").offset { IntOffset(offset.roundToInt(), 0) }
            .semantics { customActions = listOf(
                CustomAccessibilityAction("标记已读") { onRead(); true }, CustomAccessibilityAction("收藏文章") { onStar(); true },
                CustomAccessibilityAction("稍后读") { onSave(); true }, CustomAccessibilityAction("归档或恢复") { onArchive(); true }) }
            .pointerInput(article.id) {
                detectHorizontalDragGestures(onDragEnd = {
                    val distance = abs(offset) / density
                    val selected = when {
                        distance < 48 -> null
                        offset >= 0 && distance >= 220 -> actions[2]
                        offset >= 0 && distance >= 120 -> actions[1]
                        offset >= 0 -> actions[0]
                        distance >= 220 -> actions[0]
                        distance >= 120 -> actions[2]
                        else -> actions[3]
                    }
                    offset = 0f
                    selected?.invoke()
                },
                    onDragCancel = { offset = 0f }, onHorizontalDrag = { change, amount -> change.consume(); offset += amount })
            }.clickable(onClick = onOpen), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Row {
                    Text(article.title, style = MaterialTheme.typography.titleMedium, maxLines = 3,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        color = if (article.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    if (article.isStarred) Icon(Icons.Outlined.Star, "已收藏", Modifier.size(16.dp))
                }
                if (article.excerpt.isNotBlank()) Text(article.excerpt, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp))
                Text("${article.feedName} · ${article.publishedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate()}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun AddSubscriptionDialog(busy: Boolean, onClose: () -> Unit, onAdd: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text("添加 RSS 订阅") }, text = {
        OutlinedTextField(url, { url = it }, label = { Text("HTTP / HTTPS RSS 地址") }, singleLine = true)
    }, confirmButton = { IconButton(onClick = { onAdd(url) }, enabled = url.isNotBlank() && !busy) { Icon(Icons.Outlined.Check, "订阅") } },
        dismissButton = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "取消") } })
}
