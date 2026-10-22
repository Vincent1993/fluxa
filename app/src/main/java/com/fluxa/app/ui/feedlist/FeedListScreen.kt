package com.fluxa.app.ui.feedlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxa.app.domain.model.Article
import com.fluxa.app.data.sync.BackgroundSyncSettings
import com.fluxa.app.data.sync.BackgroundSyncScheduler
import kotlinx.coroutines.launch
import com.fluxa.app.ui.components.ActionRow
import com.fluxa.app.ui.components.MenuAction
import com.fluxa.app.ui.components.IconAction
import androidx.compose.ui.semantics.contentDescription

@Composable
fun FeedListRoute(
    onOpenArticle: (String) -> Unit,
    onLogin: () -> Unit,
    viewModel: FeedListViewModel = hiltViewModel(),
    onOpenGitHub: () -> Unit = {},
    onOpml: () -> Unit = {},
    reading: com.fluxa.app.ui.article.ReadingSessionViewModel = hiltViewModel()
) {
    val projected by viewModel.state.collectAsStateWithLifecycle()
    val editorQuery by viewModel.editorQuery.collectAsStateWithLifecycle()
    val state = projected.copy(query = editorQuery)
    val scope = rememberCoroutineScope()
    LaunchedEffect(reading, viewModel) { reading.retainList(viewModel.state) }
    val context = LocalContext.current.applicationContext
    val syncSettings = remember(context) { BackgroundSyncSettings(context) }
    val syncScheduler = remember(context) { BackgroundSyncScheduler(context, syncSettings) }
    var backgroundEnabled by remember { mutableStateOf(syncSettings.enabled) }
    var showOffline by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var showSwipes by rememberSaveable { mutableStateOf(false) }
    if (showSwipes) SwipeSettingsSheet(state.swipeConfig, state.source, state.subscriptions.firstOrNull { it.id == state.source }?.title,
        viewModel::configureSwipes, { showSwipes = false })
    var showBackup by rememberSaveable { mutableStateOf(false) }
    if (showBackup) LocalBackupSheet(close = { showBackup = false }, onRestored = viewModel::reloadPreferences)
    if (showNotifications) viewModel.notifications?.let { service ->
        LocalNotificationSheet(service, state.subscriptions) { showNotifications = false }
    }
    var showTriage by rememberSaveable { mutableStateOf(false) }
    if (showTriage) TriageSheet(state, viewModel::applyScope, viewModel::configure, viewModel::previewRule, { showTriage = false })
    val localSettings = remember(context) { com.fluxa.app.data.directrss.LocalOfflineSettings(context) }
    val localScheduler = remember(context) { com.fluxa.app.data.sync.DirectRssSyncScheduler(context, localSettings) }
    if (showOffline) LocalOfflineSheet(viewModel.offlinePolicy(), state.localFolders,
        save = { viewModel.updateOfflinePolicy(it); localScheduler.restore() }, usage = viewModel::offlineUsage,
        pin = viewModel::pinFolder, close = { showOffline = false }, isPinned = { localSettings.folderPinned(it) })
    FeedListScreen(state, { id ->
        val snapshot = if (state.articles.any { it.id == id }) state.articles.map { it.id }
            else state.groups.values.firstOrNull { group -> group.any { it.id == id } }?.map { it.id } ?: listOf(id)
        scope.launch {
            if (reading.reader.awaitAndFreeze(snapshot, id)) {
                onOpenArticle(id)
            }
        }
    }, onLogin, viewModel::refresh, viewModel::loadMore,
        viewModel::setQuery, viewModel::setFilter, viewModel::selectSource,
        viewModel::markRead, viewModel::toggleStar, viewModel::addSubscription,
        viewModel::retryPending, resolveAnchor = { id, surviving ->
            reading.reader.session.value.nearestSurvivor(id, surviving)
        }, backgroundEnabled = backgroundEnabled, onOpenGitHub = onOpenGitHub,
        onLocalSubscribe = viewModel::addLocalSubscription, onOpml = onOpml, onOffline = { showOffline = true },
        onNotifications = { showNotifications = true }, onBackup = { showBackup = true },
        onSwipeSettings = { showSwipes = true }, onSwipeAction = viewModel::swipe,
        onUndoFlag = viewModel::undoFlag, onDismissFlag = viewModel::dismissFlag,
        onSetRead = viewModel::setReadState, onPinArticle = viewModel::pinArticle,
        onReadLater = viewModel::setReadLater, onUndoReadLater = viewModel::undoReadLater,
        onDismissReadLater = viewModel::dismissReadLaterUndo,
        isArticlePinned = localSettings::articlePinned, onTriage = { showTriage = true }, onRetrySource = viewModel::retrySource,
        onOpenCopies = { ids, id -> scope.launch { if (reading.reader.awaitAndFreeze(ids.toList(), id)) onOpenArticle(id) } },
        onBulkRead = viewModel::bulkRead, onUndoRead = viewModel::undoRead, onDismissReceipt = viewModel::dismissReadReceipt,
        localActions = LocalSubscriptionActions(viewModel::discoverLocalSource, viewModel::previewLocalSource,
            viewModel::createLocalFolder, viewModel::organizeLocalSource)) {
            syncScheduler.setEnabled(it)
            backgroundEnabled = syncSettings.enabled
        }
}

// Preserve the pre-local-RSS signature, including its trailing background callback.
@Composable
fun FeedListScreen(
    state: FeedListState,
    onOpenArticle: (String) -> Unit,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onQuery: (String) -> Unit,
    onFilter: (ArticleFilter) -> Unit,
    onSource: (String?) -> Unit,
    onMarkRead: (String) -> Unit,
    onToggleStar: (String) -> Unit,
    onSubscribe: (String) -> Unit,
    onRetryPending: () -> Unit,
    resolveAnchor: (String, Set<String>) -> String? = { id, surviving -> id.takeIf { it in surviving } },
    backgroundEnabled: Boolean = false,
    onOpenGitHub: () -> Unit = {},
    onBackgroundSync: (Boolean) -> Unit = {}
) = FeedListScreen(state, onOpenArticle, onLogin, onRefresh, onLoadMore, onQuery, onFilter,
    onSource, onMarkRead, onToggleStar, onSubscribe, onRetryPending, resolveAnchor,
    backgroundEnabled, onOpenGitHub, onLocalSubscribe = { _, _ -> }, onBackgroundSync = onBackgroundSync)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FeedListScreen(
    state: FeedListState,
    onOpenArticle: (String) -> Unit,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onQuery: (String) -> Unit,
    onFilter: (ArticleFilter) -> Unit,
    onSource: (String?) -> Unit,
    onMarkRead: (String) -> Unit,
    onToggleStar: (String) -> Unit,
    onSubscribe: (String) -> Unit,
    onRetryPending: () -> Unit,
    resolveAnchor: (String, Set<String>) -> String? = { id, surviving -> id.takeIf { it in surviving } },
    backgroundEnabled: Boolean = false,
    onOpenGitHub: () -> Unit = {},
    onLocalSubscribe: (String, Boolean) -> Unit,
    localActions: LocalSubscriptionActions = LocalSubscriptionActions(),
    onOpml: () -> Unit = {},
    onOffline: (() -> Unit)? = null,
    onNotifications: (() -> Unit)? = null,
    onBackup: (() -> Unit)? = null,
    onSwipeSettings: (() -> Unit)? = null,
    onSwipeAction: ((String, SwipeAction) -> Unit)? = null,
    onUndoFlag: (() -> Unit)? = null,
    onDismissFlag: (() -> Unit)? = null,
    onTriage: (() -> Unit)? = null,
    onRetrySource: ((String) -> Unit)? = null,
    onOpenCopies: ((List<String>, String) -> Unit)? = null,
    onSetRead: ((String, Boolean) -> Unit)? = null,
    onPinArticle: ((String, Boolean) -> Unit)? = null,
    onReadLater: ((String, Boolean) -> Unit)? = null,
    onUndoReadLater: (() -> Unit)? = null,
    onDismissReadLater: (() -> Unit)? = null,
    isArticlePinned: (String) -> Boolean = { false },
    onBulkRead: ((List<String>) -> Unit)? = null,
    onUndoRead: (() -> Unit)? = null,
    onDismissReceipt: (() -> Unit)? = null,
    onBackgroundSync: (Boolean) -> Unit = {}
) {
    val listState = rememberLazyListState()
    val appearance = Triple(state.triage.comfortable, state.triage.showSummary, state.triage.showThumbnail)
    // Capture before the new row measurements; keyed IDs and the prior pixel offset
    // survive density/summary/cover changes without touching the frozen reader queue.
    val appearanceAnchor = remember(appearance) {
        Triple(state.articles.getOrNull(listState.firstVisibleItemIndex - 1)?.id,
            listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
    }
    LaunchedEffect(appearance) {
        val index = appearanceAnchor.first?.let { id -> state.articles.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) }
            ?: appearanceAnchor.second
        listState.scrollToItem(index, appearanceAnchor.third)
    }
    val focusManager = LocalFocusManager.current
    val landingFocus = remember { FocusRequester() }
    // Own non-editing focus on composition entry, not on foreground/resume while typing.
    LaunchedEffect(Unit) {
        landingFocus.requestFocus()
    }
    val scope = rememberCoroutineScope()
    var anchor by rememberSaveable { mutableStateOf<String?>(null) }
    var anchorOffset by rememberSaveable { mutableIntStateOf(0) }
    var headerAnchor by rememberSaveable { mutableStateOf(false) }
    // This effect runs when the retained list composition returns, not on every read mutation.
    var restoreAnchor by remember { mutableStateOf(true) }
    LaunchedEffect(state.articles) {
        if (restoreAnchor && state.articles.isNotEmpty()) {
            if (headerAnchor) listState.scrollToItem(0, anchorOffset)
            else anchor?.let { id ->
                val surviving = state.articles.map { it.id }.toSet()
                val restored = id.takeIf { it in surviving } ?: resolveAnchor(id, surviving)
                val found = state.articles.indexOfFirst { it.id == restored }
                listState.scrollToItem(if (found >= 0) found + 1 else 0, if (found >= 0 && restored == id) anchorOffset else 0)
            }
            anchor = null
            headerAnchor = false
            restoreAnchor = false
        }
    }
    var showSearch by rememberSaveable { mutableStateOf(state.query.isNotBlank()) }
    var showStatus by rememberSaveable { mutableStateOf(false) }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showManager by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var bulkSnapshot by remember { mutableStateOf<List<Article>?>(null) }
    var cutoffText by remember { mutableStateOf("") }
    bulkSnapshot?.let { snapshot ->
        val date = runCatching { java.time.LocalDate.parse(cutoffText) }.getOrNull()
        val cutoff = date?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant()
        val valid = cutoffText.isBlank() || cutoff != null
        val ids = snapshot.filter { !it.isRead && (cutoff == null || it.publishedAt.isBefore(cutoff)) }.map { it.id }
        AlertDialog(onDismissRequest = { bulkSnapshot = null }, title = { Text("批量标为已读") },
            text = { Column {
                Text("仅限打开面板时当前显示的 ${snapshot.size} 篇；将更改 ${if (valid) ids.size else 0} 篇。新到文章不包括在内。")
                OutlinedTextField(cutoffText, { cutoffText = it }, label = { Text("日期之前（YYYY-MM-DD，可留空）") },
                    supportingText = { Text("使用本地时区当天零点，不含该日期") }, isError = !valid, singleLine = true)
            } }, confirmButton = { IconAction("确认当前范围批量已读", Icons.Outlined.Check,
                { onBulkRead?.invoke(ids); bulkSnapshot = null }, enabled = valid && ids.isNotEmpty()) },
            dismissButton = { IconAction("取消批量已读", Icons.Outlined.Close, { bulkSnapshot = null }) })
    }
    var actionArticle by remember { mutableStateOf<Article?>(null) }
    actionArticle?.let { article ->
        ModalBottomSheet(onDismissRequest = { actionArticle = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text(article.title, maxLines = 2)
                state.groups[article.id]?.let { group ->
                    Text("同链接 ${group.size} 个副本 · ${group.map { it.source }.distinct().size} 个来源；已读与收藏仅代表所选副本")
                    group.forEach { copy -> Row {
                        Text("${copy.feedName} · ${copy.title}", Modifier.weight(1f), maxLines = 2)
                        IconAction("阅读副本 ${copy.feedName} ${copy.title}", Icons.Outlined.Article,
                            { actionArticle = null; onOpenCopies?.invoke(group.map { it.id }, copy.id) ?: onOpenArticle(copy.id) })
                    } }
                }
                onSetRead?.let { set -> IconAction(if (article.isRead) "标记未读" else "标记已读", Icons.Outlined.MarkEmailUnread,
                    { set(article.id, !article.isRead); actionArticle = null }) }
                if (article.id.startsWith("direct-rss:")) onPinArticle?.let { pin ->
                    val pinned = isArticlePinned(article.id)
                    Text(if (pinned) "文章已保留" else "文章未手动保留")
                    IconAction(if (pinned) "取消文章离线保留" else "下载并保留文章",
                        if (pinned) Icons.Outlined.RemoveCircleOutline else Icons.Outlined.Download,
                        { pin(article.id, !pinned); actionArticle = null }, selected = pinned)
                }
                IconAction(if (article.isStarred) "取消收藏" else "收藏文章", Icons.Outlined.Star,
                    { onToggleStar(article.id); actionArticle = null }, selected = article.isStarred)
                if (article.source.startsWith(com.fluxa.app.data.directrss.LOCAL_PREFIX) &&
                    article.source != com.fluxa.app.data.directrss.SAVED_PAGE_SOURCE) onReadLater?.let { set ->
                    IconAction(if (article.readLater) "移出稍后读" else "加入稍后读", Icons.Outlined.BookmarkAdd,
                        { set(article.id, !article.readLater); actionArticle = null }, selected = article.readLater)
                }
                IconAction("关闭文章操作", Icons.Outlined.Close, { actionArticle = null })
            }
        }
    }
    Scaffold(snackbarHost = {
        if (state.flagReceipt != null) Snackbar { Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已更改标记", Modifier.weight(1f))
            onUndoFlag?.let { IconAction("撤销标记更改", Icons.Outlined.Undo, it) }
            onDismissFlag?.let { IconAction("关闭标记结果", Icons.Outlined.Close, it) }
        } }
        else if (state.readLaterUndo != null) Snackbar { Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已更新稍后读", Modifier.weight(1f))
            onUndoReadLater?.let { IconAction("撤销稍后读更改", Icons.Outlined.Undo, it) }
            onDismissReadLater?.let { IconAction("关闭稍后读结果", Icons.Outlined.Close, it) }
        } }
        else state.readReceipt?.let { receipt ->
            Snackbar { Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已更改 ${receipt.changes.size} 篇" + receipt.failures.joinToString("；"), Modifier.weight(1f))
                onUndoRead?.let { undo -> IconAction("撤销批量已读", Icons.Outlined.Undo, undo) }
                onDismissReceipt?.let { dismiss -> IconAction("关闭批量结果", Icons.Outlined.Close, dismiss) }
            } }
        }
    }, modifier = Modifier
        .focusRequester(landingFocus)
        // Low-level target: no interactive/accessibility control or visual change.
        .focusTarget(), topBar = {
        TopAppBar(title = { Text("Fluxa", style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                IconAction("选择订阅", Icons.AutoMirrored.Outlined.List, { focusManager.clearFocus(); showSources = true })
            }, actions = {
            IconAction("搜索", Icons.Outlined.Search, onClick = {
                val closing = showSearch || state.query.isNotEmpty()
                if (closing) { onQuery(""); focusManager.clearFocus() }
                showSearch = !closing
                if (showSearch) scope.launch { listState.animateScrollToItem(0) }
            })
            IconAction("刷新", Icons.Outlined.Refresh, onRefresh, enabled = !state.busy,
                stateLabel = if (state.busy) "同步中…" else null)
            Box {
                IconAction("更多", Icons.Outlined.MoreVert, { showMore = true },
                    stateLabel = if (state.pendingCount > 0) "${state.pendingCount} 项操作待同步" else null)
                DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                    MenuAction("同步详情", Icons.Outlined.Sync, { showMore = false; showStatus = true })
                    MenuAction("GitHub 收藏", Icons.Outlined.Star, { showMore = false; onOpenGitHub() })
                    MenuAction("OPML 导入与导出", Icons.Outlined.ImportExport, { showMore = false; onOpml() })
                    onBackup?.let { MenuAction("本地备份与恢复", Icons.Outlined.Save, { showMore = false; it() }) }
                    onSwipeSettings?.let { MenuAction("列表滑动设置", Icons.Outlined.Swipe, { showMore = false; it() }) }
                    MenuAction("添加订阅", Icons.Outlined.Add, { showMore = false; showAdd = true })
                    MenuAction("账户与同步设置", Icons.Outlined.Settings, { showMore = false; showSettings = true })
                    onNotifications?.let { open -> MenuAction("来源通知", Icons.Outlined.Notifications, { showMore = false; open() }) }
                    onTriage?.let { open -> MenuAction("筛选与规则", Icons.Outlined.Tune, { showMore = false; open() }) }
                    onOffline?.let { open -> MenuAction("本地 RSS 离线与后台", Icons.Outlined.Download, { showMore = false; open() }) }
                    if (onBulkRead != null) MenuAction("当前范围批量已读", Icons.Outlined.DoneAll, {
                        showMore = false; cutoffText = ""; bulkSnapshot = state.articles.toList()
                    })
                }
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            PullToRefreshBox(state.busy, onRefresh, Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxHeight().widthIn(max = 760.dp).fillMaxWidth().align(Alignment.TopCenter),
                    state = listState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    // Let filters scroll away so landscape/large text leaves room for articles.
                    item(key = "filters") {
                        Column(Modifier.padding(bottom = 12.dp)) {
                            Text(when {
                                state.query.isNotBlank() -> "搜索结果"
                                state.source != null -> "${state.subscriptions.firstOrNull { it.id == state.source }?.title ?: "当前订阅"} · ${state.filter.label}文章"
                                state.filter == ArticleFilter.Unread -> "未读文章"
                                state.filter == ArticleFilter.Starred -> "我的收藏"
                                state.filter == ArticleFilter.ReadLater -> "稍后读"
                                state.filter == ArticleFilter.Last24Hours -> "最近 24 小时"
                                else -> "全部文章"
                            }, style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp).semantics { heading() })
                            AnimatedVisibility(visible = showSearch || state.query.isNotEmpty()) {
                                OutlinedTextField(value = state.query, onValueChange = onQuery,
                                    label = { Text("搜索文章") }, singleLine = true,
                                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                    trailingIcon = {
                                        if (state.query.isNotEmpty()) IconAction("清空搜索", Icons.Outlined.Close, { onQuery("") })
                                    }, shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("当前显示 ${state.articles.size} 篇 · 未读 ${state.unreadCount} · 收藏 ${state.starredCount}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (state.scope.after != null || state.scope.before != null || state.scope.showHidden ||
                                        state.triage.rules.any { it.enabled } || state.triage.sources.values.any { it.hideRead }) {
                                        Text(listOfNotNull(
                                            "日期限定".takeIf { state.scope.after != null || state.scope.before != null },
                                            if (state.scope.showHidden) "显示隐藏项" else "隐藏规则生效".takeIf {
                                                state.triage.rules.any { it.enabled } || state.triage.sources.values.any { it.hideRead } }
                                        ).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (onTriage != null) IconAction("筛选与规则", Icons.Outlined.Tune, onTriage)
                            }
                            Column(Modifier.fillMaxWidth()) {
                                FlowRow(Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ArticleFilter.entries.forEach { filter ->
                                        FilterChip(selected = state.filter == filter,
                                            onClick = { onFilter(filter) },
                                            label = { Text(filter.label, Modifier.clearAndSetSemantics {}) },
                                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = filter.label })
                                    }
                                    if (state.source != null) {
                                        IconAction("${state.subscriptions.firstOrNull { it.id == state.source }?.title ?: "当前订阅"} · 筛选",
                                            Icons.Outlined.RssFeed, { focusManager.clearFocus(); showSources = true }, selected = true)
                                        IconAction("清除来源筛选", Icons.Outlined.Close, { onSource(null) }, enabled = !state.busy)
                                    }
                                }
                            }
                            val status = state.message?.takeIf { it.isNotBlank() }
                                ?: state.syncErrors.firstOrNull()
                                ?: if (state.pendingCount > 0) "${state.pendingCount} 项操作待同步"
                                else if (state.awaitingConfirmation > 0) "${state.awaitingConfirmation} 项等待服务端确认" else null
                            if (status != null) {
                                ActionRow(status, Icons.Outlined.Sync, { showStatus = true },
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    if (state.searchPending) {
                        item { Text("正在搜索已保存的标题与正文…", Modifier.padding(24.dp)) }
                    } else if (state.articles.isEmpty()) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 32.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Outlined.RssFeed, null, tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp))
                                Text(if (state.query.isNotBlank() || state.filter != ArticleFilter.All || state.source != null)
                                    "没有匹配的文章" else "暂无文章。添加订阅后刷新即可阅读。",
                                    style = MaterialTheme.typography.bodyLarge)
                                Text(if (state.query.isNotBlank()) "换个关键词，或清除搜索查看当前范围。"
                                    else if (state.filter != ArticleFilter.All || state.source != null) "试试其他分类或订阅；筛选条件可在上方调整。"
                                    else "添加你关心的 RSS 订阅，文章会保存在此设备。", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (state.query.isNotBlank()) ActionRow("查看当前范围", Icons.Outlined.Close, { onQuery("") })
                                ActionRow("添加本地 RSS", Icons.Outlined.Add, { showAdd = true }, enabled = !state.busy)
                                ActionRow("获取文章", Icons.Outlined.Refresh, onRefresh, enabled = !state.busy,
                                    stateLabel = if (state.busy) "同步中…" else null)
                            }
                        }
                    }
                    items(state.articles, key = { it.id }) { article ->
                        val group = state.groups[article.id]
                        ArticleCard(if (group == null) article else article.copy(feedName = "${article.feedName} · ${group.map { it.source }.distinct().size} 来源 / ${group.size} 副本 · 长按展开"), {
                            landingFocus.requestFocus()
                            anchorOffset = listState.firstVisibleItemScrollOffset
                            headerAnchor = listState.firstVisibleItemIndex == 0
                            anchor = state.articles.getOrNull(listState.firstVisibleItemIndex - 1)?.id
                            onOpenArticle(article.id)
                        },
                            { onMarkRead(article.id) }, { onToggleStar(article.id) },
                            onActions = if (onSetRead != null || onPinArticle != null || group != null) ({ actionArticle = article }) else null,
                            comfortable = state.triage.comfortable, showSummary = state.triage.showSummary, showThumbnail = state.triage.showThumbnail,
                            swipeChoice = state.swipeConfig.choice(article.source), onSwipeAction = onSwipeAction?.let { action -> { selected -> action(article.id, selected) } })
                    }
                    item {
                        val localWindow = if (state.source != null) state.subscriptions.any { it.id == state.source && it.isLocal }
                            else state.subscriptions.isNotEmpty() && state.subscriptions.all { it.isLocal }
                        ActionRow(if (localWindow) "近期窗口 · 刷新获取最新" else "加载更多",
                            if (localWindow) Icons.Outlined.Refresh else Icons.Outlined.ExpandMore,
                            if (localWindow) onRefresh else onLoadMore, enabled = !state.busy,
                            stateLabel = if (state.busy) "同步中…" else null)
                    }
                }
            }
        }
    }
    if (showStatus) AlertDialog(onDismissRequest = { showStatus = false }, title = { Text("同步状态") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(state.message ?: if (state.busy) "同步中…" else "暂无同步问题")
            Text("${state.pendingCount} 项操作待同步")
            Text("${state.awaitingConfirmation} 项已发送、等待服务端文章确认")
            state.syncErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            state.subscriptions.filter { it.isLocal }.forEach { source ->
                Text("${source.title} · ${state.sourceCounts[source.id] ?: 0} 篇 · ${if (source.archived) "已取消" else if (source.paused) "已暂停" else "启用"}")
                Text("最近成功：${source.lastSuccess.takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it) } ?: "尚无"} · ${source.status}")
                onRetrySource?.let { retry -> IconAction("重试来源 ${source.title}", Icons.Outlined.Refresh, { retry(source.id) }, enabled = !state.busy && !source.paused && !source.archived) }
            }
            IconAction("刷新重试", Icons.Outlined.Refresh, onRefresh, enabled = !state.busy)
            IconAction("重试待同步操作", Icons.Outlined.Sync, onRetryPending, enabled = !state.busy)
            IconAction("登录 NewsBlur", Icons.Outlined.Cloud, { showStatus = false; onLogin() })
        } }, confirmButton = { IconAction("关闭", Icons.Outlined.Close, { showStatus = false }) })
    if (showSources) {
        ModalBottomSheet(onDismissRequest = { showSources = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).imePadding()) {
                Text("订阅", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
                Text("${state.subscriptions.count { !it.archived }} 个订阅 · 当前列表 ${state.articles.size} 篇",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                LazyColumn(Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item(key = "all-sources") {
                        SourceChoice("全部订阅", state.source == null, !state.busy) {
                            onSource(null); showSources = false
                        }
                    }
                    items(state.subscriptions.filterNot { it.archived }, key = { it.id }) { subscription ->
                        SourceChoice(subscription.title + if (subscription.paused) "\n已暂停" else "",
                            state.source == subscription.id, !state.busy) {
                            onSource(subscription.id); showSources = false
                        }
                    }
                    if (state.subscriptions.none { !it.archived }) item {
                        Text("添加本地 RSS 或登录并刷新后会显示订阅", Modifier.padding(vertical = 16.dp))
                    }
                }
                ActionRow("管理本地订阅", Icons.Outlined.Folder, { showSources = false; showManager = true })
                ActionRow("添加订阅", Icons.Outlined.Add, { showSources = false; showAdd = true })
                ActionRow("关闭", Icons.Outlined.Close, { showSources = false },
                    modifier = Modifier.align(Alignment.End).padding(vertical = 8.dp))
            }
        }
    }
    if (showManager) LocalSubscriptionManager(state.subscriptions, state.localFolders, localActions) { showManager = false }
    if (showAdd) {
        val initialSuccess = rememberSaveable { state.subscriptionSuccess }
        LaunchedEffect(state.subscriptionSuccess) {
            if (state.subscriptionSuccess != initialSuccess) showAdd = false
        }
        AddSubscriptionDialog(state.busy, state.message, { showAdd = false }, onSubscribe, onLocalSubscribe, localActions)
    }
    if (showSettings) {
        AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("账户与同步") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("NewsBlur 后台刷新", modifier = Modifier.weight(1f).padding(top = 12.dp))
                        IconAction("NewsBlur 后台刷新", Icons.Outlined.Sync, { onBackgroundSync(!backgroundEnabled) }, selected = backgroundEnabled)
                    }
                    Text("NewsBlur 账号每小时尝试；仅不计费网络且电量充足时。系统可能延迟，需已有登录会话。本地 RSS 使用独立的离线与后台设置，不参与账号同步。",
                        style = MaterialTheme.typography.bodySmall)
                    IconAction("登录 NewsBlur", Icons.Outlined.Cloud, { showSettings = false; onLogin() })
                }
            }, confirmButton = { IconAction("完成", Icons.Outlined.Check, { showSettings = false }) })
    }
}

@Composable
internal fun ArticleCard(article: Article, onOpen: () -> Unit, onRead: () -> Unit, onStar: () -> Unit, onActions: (() -> Unit)? = null,
    comfortable: Boolean = false, showSummary: Boolean = true, showThumbnail: Boolean = true,
    swipeChoice: SwipeChoice = SwipeChoice(), onSwipeAction: ((SwipeAction) -> Unit)? = null) {
    ArticleSwipe(article.isRead, article.isStarred, onRead, onStar, articleId = article.id, onOpen = onOpen, onActions = onActions,
        choice = swipeChoice, onAction = onSwipeAction, readLater = article.readLater) {
    val colors = MaterialTheme.colorScheme
    // Opaque theme text colors: soften both text levels without changing metrics.
    val titleColor = if (article.isRead) colors.onSurfaceVariant else colors.onSurface
    val summaryColor = if (article.isRead) colors.onSurfaceVariant
        else lerp(colors.onSurface, colors.onSurfaceVariant, .5f)
    Column(Modifier.fillMaxWidth().semantics {
        stateDescription = "${if (article.isRead) "已读" else "未读"} · ${if (article.isStarred) "已收藏" else "未收藏"}"
    }) {
        Column(Modifier.fillMaxWidth().padding(top = if (comfortable) 22.dp else 16.dp, bottom = if (comfortable) 24.dp else 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(article.feedName, Modifier.weight(1f).testTag("article-source"),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal), color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(article.publishedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),
                        Modifier.testTag("article-date"), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                        color = colors.onSurfaceVariant, maxLines = 1, softWrap = false)
                    // Always reserve the same tiny slot; starring cannot resize the source.
                    Box(Modifier.padding(start = 4.dp).size(12.dp).clearAndSetSemantics {}) {
                        if (article.isStarred) Icon(Icons.Outlined.Star, null,
                            tint = colors.onSurfaceVariant, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(article.title, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, color = titleColor,
                        modifier = Modifier.testTag("article-title"), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (showSummary && article.summary.isNotBlank()) Text(article.summary, maxLines = if (comfortable) 2 else 1, overflow = TextOverflow.Ellipsis,
                        color = summaryColor,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
                        modifier = Modifier.padding(top = 3.dp).testTag("article-summary"))
                }
                if (showThumbnail && article.source.startsWith("direct-rss:")) {
                    // CachedArticleCover emits nothing until decoding succeeds, including this gap.
                    com.fluxa.app.ui.components.CachedArticleCover(article.contentHtml,
                        Modifier.padding(start = 10.dp).testTag("article-thumbnail").size(56.dp), thumbnail = true)
                }
            }
        }
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .55f))
    }
    }
}

@Composable
private fun SourceChoice(title: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    ActionRow(title, Icons.Outlined.RssFeed, onClick, enabled = enabled, selected = selected)
}
