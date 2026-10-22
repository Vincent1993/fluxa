package com.fluxa.app.ui.github

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import com.fluxa.app.ui.components.IconAction
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxa.app.data.github.GitHubRepo
import com.fluxa.app.domain.GitHubRules
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubRoute(onBack: () -> Unit, viewModel: GitHubViewModel = hiltViewModel()) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val allRepos by viewModel.repos.collectAsStateWithLifecycle()
    // flatMapLatest may still emit the previous profile for one frame. Never display it.
    val repos = allRepos.filter { it.profile == settings.active }
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val batches by viewModel.batches.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val clock by viewModel.clock.collectAsStateWithLifecycle()
    var username by rememberSaveable(settings.active) { mutableStateOf(if (settings.active == "vincent1993") "Vincent1993" else settings.active) }
    var query by rememberSaveable(settings.active) { mutableStateOf("") }
    var purpose by rememberSaveable(settings.active) { mutableStateOf<String?>(null) }
    var detail by rememberSaveable(settings.active) { mutableStateOf<Long?>(null) }
    val selected = repos.firstOrNull { it.id == detail }
    val date = Instant.ofEpochMilli(clock).atZone(ZoneId.systemDefault()).toLocalDate()
    val libraryScroll = rememberLazyListState()
    val detailScroll = rememberLazyListState()
    LaunchedEffect(detail, settings.active) { if (detail != null) detailScroll.scrollToItem(0) }
    LaunchedEffect(settings.active) { libraryScroll.scrollToItem(0) }
    LaunchedEffect(selected?.id, settings.active) { selected?.let { viewModel.seen(it.profile, it.id) } }
    val back = { if (detail != null) detail = null else onBack() }
    BackHandler(onBack = back)
    Scaffold(topBar = { TopAppBar(title = { Text(if (detail == null) "GitHub 收藏" else "收藏详情") },
        navigationIcon = { IconAction("返回", Icons.AutoMirrored.Outlined.ArrowBack, back) }) }) { padding ->
        LazyColumn(Modifier.testTag("github-scroll").fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            state = if (detail == null) libraryScroll else detailScroll,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selected != null) {
                message?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                item { Text(selected.name, style = MaterialTheme.typography.headlineSmall) }
                item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selected.description.ifBlank { "暂无描述" })
                    Text("${selected.language.ifBlank { "未知语言" }} · ★ ${selected.stars}")
                    Text(GitHubRules.unpack(selected.topics).joinToString(" · "))
                    RepositoryLink(selected.url)
                } }
                item { TagEditor(selected, viewModel::tags) }
                item { Text("相似收藏 · 本地匹配", style = MaterialTheme.typography.titleLarge) }
                val similar = GitHubRules.similar(selected, repos)
                if (similar.isEmpty()) item { Text("自己的收藏中暂无有效相似项。导入更多收藏后再看看。") }
                items(similar, key = { "similar/${it.repo.id}" }) { match -> RepoCard(match.repo, { detail = match.repo.id }, reasons = match.reasons.joinToString("\n")) }
            } else {
                item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("公开 Stars · 无需登录", style = MaterialTheme.typography.headlineSmall)
                    Text("仅增量保存到本机。取消 Star 不会删除本地收藏；不修改 GitHub，不需要 token。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(username, { username = it }, label = { Text("GitHub 用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    IconAction("切换本地库", Icons.Outlined.SwitchAccount, { focus.clearFocus(force = true); keyboard?.hide(); viewModel.select(username) })
                    Text("当前用户：${settings.active} · ${repos.size} 个收藏")
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    progress?.takeIf { it.username == settings.active }?.let { Text("${it.status} · 已完成 ${it.pages} 页") }
                    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在串行导入，切换用户不会改变导入归属") }
                    val cooling = settings.cooldownUntil > clock
                    if (cooling) Text("共享限流冷却至 ${Instant.ofEpochMilli(settings.cooldownUntil).atZone(ZoneId.systemDefault()).toLocalDateTime()}，所有用户均需等待")
                    IconAction(if (progress?.takeIf { it.username == settings.active }?.cursor != null) "继续导入" else "导入公开 Stars", Icons.Outlined.Download, viewModel::importStars, enabled = !busy && !cooling)
                    IconAction("重新从第一页导入（保留收藏）", Icons.Outlined.RestartAlt, viewModel::restart, enabled = !busy)
                } }
                val daily = batches.firstOrNull { it.profile == settings.active && it.date == date.toString() }
                item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("每日 3", style = MaterialTheme.typography.titleLarge)
                    Text(daily?.let { "${it.date} · 第 ${it.batch} 批 · 本地规则推荐" } ?: "$date · 导入后生成，不足 3 个按实际展示")
                    IconAction("换一批", Icons.Outlined.Shuffle, viewModel::change, enabled = repos.isNotEmpty())
                } }
                daily?.ids?.split(',')?.mapNotNull { id -> repos.firstOrNull { it.id.toString() == id } }?.forEach { repo ->
                    item(key = "daily/${repo.id}") { RepoCard(repo, { detail = repo.id }, modifier = Modifier.testTag("github-daily-${repo.id}")) }
                }
                item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, label = { Text("搜索名称、描述、主题、语言、用途") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(force = true); keyboard?.hide() }), modifier = Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf("全部", "未分类") + GitHubRules.purposes + repos.flatMap(GitHubRules::tags)).distinct().forEach { tag ->
                            Column {
                                Text(tag)
                                IconAction(tag, Icons.Outlined.FilterAlt, { purpose = tag.takeUnless { it == "全部" } },
                                    selected = if (tag == "全部") purpose == null else purpose == tag,
                                    testTag = "github-filter-$tag")
                            }
                        }
                    }
                } }
                val results = GitHubRules.search(repos, query, purpose)
                item { Text("收藏库 · ${results.size} 个结果", style = MaterialTheme.typography.titleLarge) }
                if (results.isEmpty()) item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (repos.isEmpty()) "本地库为空。输入公开用户名并导入；之后可离线浏览。" else "没有匹配收藏，试试其他关键词或用途。")
                    IconAction("清除搜索和筛选", Icons.Outlined.FilterAltOff, { query = ""; purpose = null })
                } }
                items(results, key = { "repo/${it.id}" }) { repo -> RepoCard(repo, { detail = repo.id }, modifier = Modifier.testTag("github-result-${repo.id}")) }
            }
        }
    }
}

@Composable
private fun RepositoryLink(raw: String) {
    val context = LocalContext.current
    val url = GitHubRules.repositoryUrl(raw)
    var error by remember(raw) { mutableStateOf<String?>(null) }
    Text(raw.ifBlank { "未提供仓库网址" })
    IconAction("Open on GitHub", Icons.Outlined.OpenInNew, onClick = {
        error = null
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, checkNotNull(url).toUri()))
        } catch (_: ActivityNotFoundException) {
            error = "无法打开：未安装可处理 GitHub 链接的应用。"
        } catch (_: SecurityException) {
            error = "无法打开：系统不允许此应用处理 GitHub 链接。"
        }
    }, enabled = url != null)
    if (url == null) Text("链接不可用：需要有效的 HTTPS github.com 仓库网址。", style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun RepoCard(repo: GitHubRepo, onClick: () -> Unit, modifier: Modifier = Modifier, reasons: String? = null) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(repo.name, style = MaterialTheme.typography.titleMedium)
            Text(repo.description.ifBlank { "暂无描述" }, style = MaterialTheme.typography.bodyMedium)
            Text(GitHubRules.tags(repo).joinToString(" · ").ifBlank { "未分类" }, color = MaterialTheme.colorScheme.primary)
            reasons?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun TagEditor(repo: GitHubRepo, save: (String, Long, List<String>?) -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var chosen by rememberSaveable(repo.profile, repo.id, repo.manualTags) { mutableStateOf(GitHubRules.pack(GitHubRules.tags(repo))) }
    var custom by rememberSaveable(repo.profile, repo.id) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("用途 · ${if (repo.manualTags == null) "自动分类" else "手动分类"}", style = MaterialTheme.typography.titleLarge)
    Text("可选择多个用途，或添加自定义用途。清空后保存会保留空的手动覆盖。")
    (GitHubRules.purposes + GitHubRules.unpack(chosen)).distinct().forEach { tag ->
        val tags = GitHubRules.unpack(chosen)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(tag, Modifier.weight(1f))
            IconAction(tag, Icons.Outlined.Check, { chosen = GitHubRules.pack(if (tag in tags) tags - tag else tags + tag) },
                selected = tag in tags, testTag = "purpose-selector-$tag")
        }
    }
    OutlinedTextField(custom, { custom = it }, label = { Text("自定义用途") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    IconAction("添加用途", Icons.Outlined.Add, { focus.clearFocus(force = true); keyboard?.hide(); chosen = GitHubRules.pack(GitHubRules.unpack(chosen) + custom); custom = "" }, enabled = custom.isNotBlank())
    IconAction("清空用途", Icons.Outlined.Clear, { chosen = "" })
    IconAction("保存用途", Icons.Outlined.Check, { focus.clearFocus(force = true); keyboard?.hide(); save(repo.profile, repo.id, GitHubRules.unpack(chosen)) })
    IconAction("恢复自动分类", Icons.Outlined.RestartAlt, { save(repo.profile, repo.id, null); chosen = GitHubRules.pack(GitHubRules.tags(repo.copy(manualTags = null))) })
    }
}
