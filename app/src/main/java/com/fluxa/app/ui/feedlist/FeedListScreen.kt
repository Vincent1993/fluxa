package com.fluxa.app.ui.feedlist

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxa.app.domain.model.Article
import com.fluxa.app.data.sync.BackgroundSyncSettings
import com.fluxa.app.data.sync.BackgroundSyncScheduler

@Composable
fun FeedListRoute(
    onOpenArticle: (String) -> Unit,
    onLogin: () -> Unit,
    viewModel: FeedListViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val syncSettings = remember(context) { BackgroundSyncSettings(context) }
    val syncScheduler = remember(context) { BackgroundSyncScheduler(context, syncSettings) }
    var backgroundEnabled by remember { mutableStateOf(syncSettings.enabled) }
    FeedListScreen(state, onOpenArticle, onLogin, viewModel::refresh, viewModel::loadMore,
        viewModel::setQuery, viewModel::setFilter, viewModel::selectSource,
        viewModel::markRead, viewModel::toggleStar, viewModel::addSubscription,
        viewModel::retryPending, backgroundEnabled) {
            syncScheduler.setEnabled(it)
            backgroundEnabled = syncSettings.enabled
        }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    backgroundEnabled: Boolean = false,
    onBackgroundSync: (Boolean) -> Unit = {}
) {
    var showSources by rememberSaveable { mutableStateOf(false) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Fluxa") }, actions = {
            IconButton(onClick = { showSources = true }) { Icon(Icons.AutoMirrored.Outlined.List, "选择订阅") }
            IconButton(onClick = { showAdd = true }) { Icon(Icons.Outlined.Add, "添加订阅") }
            IconButton(onClick = onRefresh, enabled = !state.busy) { Icon(Icons.Outlined.Refresh, "刷新") }
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Settings, "账户与同步设置") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text("内容由 NewsBlur 同步", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            OutlinedTextField(value = state.query, onValueChange = onQuery,
                label = { Text("搜索已缓存文章") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArticleFilter.entries.forEach { filter ->
                    FilterChip(state.filter == filter, { onFilter(filter) }, { Text(filter.label) })
                }
                if (state.source != null) {
                    InputChip(true, { showSources = true }, {
                        Text(state.subscriptions.firstOrNull { it.id == state.source }?.title ?: "当前订阅")
                    })
                }
            }
            state.message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall) }
            if (state.pendingCount > 0) {
                TextButton(onClick = onRetryPending, enabled = !state.busy) {
                    Text("${state.pendingCount} 项操作待同步 · 重试")
                }
            }
            PullToRefreshBox(state.busy, onRefresh, Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.articles.isEmpty()) {
                        item {
                            Text(if (state.query.isNotBlank() || state.filter != ArticleFilter.All)
                                "没有匹配的缓存文章" else "暂无缓存文章。登录后添加订阅，再刷新。",
                                style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    items(state.articles, key = { it.id }) { article ->
                        ArticleCard(article, { onOpenArticle(article.id) },
                            { onMarkRead(article.id) }, { onToggleStar(article.id) })
                    }
                    item {
                        TextButton(onClick = onLoadMore, enabled = !state.busy) {
                            Text(if (state.busy) "同步中…" else "加载更多")
                        }
                    }
                }
            }
        }
    }
    if (showSources) {
        AlertDialog(onDismissRequest = { showSources = false },
            title = { Text("订阅") }, text = {
                LazyColumn {
                    item { TextButton(onClick = { onSource(null); showSources = false },
                        enabled = !state.busy) { Text("全部订阅") } }
                    items(state.subscriptions, key = { it.id }) { subscription ->
                        TextButton(onClick = { onSource(subscription.id); showSources = false },
                            enabled = !state.busy) { Text(subscription.title) }
                    }
                    if (state.subscriptions.isEmpty()) item { Text("登录并刷新后会显示订阅") }
                }
            }, confirmButton = { TextButton(onClick = { showSources = false }) { Text("关闭") } })
    }
    if (showAdd) AddSubscriptionDialog(state.busy, { showAdd = false }) {
        onSubscribe(it); showAdd = false
    }
    if (showSettings) {
        AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("账户与同步") },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("后台刷新", modifier = Modifier.weight(1f).padding(top = 12.dp))
                        Switch(backgroundEnabled, onBackgroundSync, modifier = Modifier)
                    }
                    Text("每小时尝试；仅不计费网络且电量充足时。系统可能延迟。需已有登录会话。",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showSettings = false; onLogin() }) { Text("登录 NewsBlur") }
                }
            }, confirmButton = { TextButton(onClick = { showSettings = false }) { Text("完成") } })
    }
}

@Composable
private fun ArticleCard(article: Article, onOpen: () -> Unit, onRead: () -> Unit, onStar: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp)) {
            Text(article.title, style = MaterialTheme.typography.titleMedium,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("${article.feedName} · ${article.publishedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Row {
                TextButton(onClick = onRead, enabled = !article.isRead) {
                    Text(if (article.isRead) "已读" else "标记已读")
                }
                TextButton(onClick = onStar) { Text(if (article.isStarred) "取消收藏" else "收藏文章") }
            }
        }
    }
}

@Composable
private fun AddSubscriptionDialog(busy: Boolean, onClose: () -> Unit, onAdd: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text("添加 RSS 订阅") }, text = {
        OutlinedTextField(url, { url = it }, label = { Text("HTTP / HTTPS RSS 地址") }, singleLine = true)
    }, confirmButton = {
        TextButton(onClick = { onAdd(url) }, enabled = url.isNotBlank() && !busy) { Text("订阅") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}
