package com.fluxa.app.ui.article

import android.content.Intent
import android.view.ViewOutlineProvider
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxa.app.data.reader.ReaderAnnotation
import com.fluxa.app.data.reader.ReaderHtml
import com.fluxa.app.ui.components.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleRoute(onBack: () -> Unit, viewModel: ArticleViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val fontSize by viewModel.fontSize.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val annotations by viewModel.annotations.collectAsStateWithLifecycle()
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var showLibrary by rememberSaveable { mutableStateOf(false) }
    val article = (state as? UiState.Success)?.data
    Scaffold(topBar = {
        TopAppBar(title = { Text("阅读", maxLines = 1) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回文章列表") }
        }, actions = {
            if (article != null) {
                IconButton(onClick = viewModel::toggleStar) { Icon(Icons.Outlined.Star,
                    if (article.isStarred) "取消收藏" else "收藏", tint = if (article.isStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { editor = "note" }) { Icon(Icons.Outlined.EditNote, "添加本地笔记") }
                IconButton(onClick = { editor = "highlight" }) { Icon(Icons.Outlined.Highlight, "添加本地高亮") }
                IconButton(onClick = viewModel::save) { Icon(Icons.Outlined.BookmarkBorder,
                    if (article.savedAtEpochSeconds == null) "稍后读" else "取消稍后读") }
                IconButton(onClick = viewModel::archive) { Icon(Icons.Outlined.Archive,
                    if (article.archivedAtEpochSeconds == null) "归档文章" else "恢复文章") }
                IconButton(onClick = { showLibrary = true }) { Icon(Icons.Outlined.MoreHoriz, "阅读设置与本地记录") }
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            when (val value = state) {
                UiState.Loading -> CircularProgressIndicator()
                UiState.Empty -> Text("暂无内容")
                is UiState.Error -> Text(value.message, Modifier.padding(16.dp))
                is UiState.Success -> {
                    val item = value.data
                    Text(item.title, style = MaterialTheme.typography.headlineSmall,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    Text(listOf(item.feedName, item.author.takeIf { it.isNotBlank() }, "${item.wordCount} 字／词").filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                    LinearProgressIndicator(progress = { item.readingProgress }, modifier = Modifier.fillMaxWidth())
                    ArticleContent(item.contentHtml, item.url, fontSize, annotations,
                        item.readingProgress, viewModel::saveProgress, Modifier.weight(1f))
                }
            }
        }
    }
    if (editor != null) {
        val editing = annotations.firstOrNull { editor == "edit/" + it.id }
        val isHighlight = editor == "highlight" || editing?.kind == "highlight"
        var quote by rememberSaveable(editor) { mutableStateOf(editing?.quote ?: "") }
        var note by rememberSaveable(editor) { mutableStateOf(editing?.note ?: "") }
        AlertDialog(onDismissRequest = { editor = null }, title = { Text(if (isHighlight) "本地高亮" else "本地笔记") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("仅保存在本机，不发送给 NewsBlur。")
                    if (isHighlight) OutlinedTextField(quote, { quote = it }, label = { Text("从正文复制需要高亮的文字") })
                    OutlinedTextField(note, { note = it }, label = { Text(if (isHighlight) "备注（可选）" else "笔记") })
                }
            }, confirmButton = {
                IconButton(onClick = { if (editing != null) viewModel.editAnnotation(editing.id, quote, note) else if (isHighlight) viewModel.highlight(quote, note) else viewModel.note(note); editor = null },
                    enabled = if (isHighlight) quote.isNotBlank() else note.isNotBlank()) { Icon(Icons.Outlined.Check, "保存本地记录") }
            }, dismissButton = { IconButton(onClick = { editor = null }) { Icon(Icons.Outlined.Close, "取消") } })
    }
    if (showLibrary && article != null) {
        var tags by rememberSaveable { mutableStateOf(article.tags.joinToString(", ")) }
        ModalBottomSheet(onDismissRequest = { showLibrary = false }) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 20.dp)) {
                item {
                    Text("阅读设置", style = MaterialTheme.typography.titleMedium)
                    Text("字号 $fontSize", style = MaterialTheme.typography.bodySmall)
                    Slider(fontSize.toFloat(), { viewModel.setFontSize(it.toInt()) }, valueRange = 14f..32f, steps = 17)
                    Row {
                        listOf(16,18,20,24).forEach { size ->
                            IconButton(onClick = { viewModel.setFontSize(size) }) { Icon(Icons.Outlined.FormatSize, "${size}号") }
                        }
                    }
                    OutlinedTextField(tags, { tags = it }, label = { Text("本地标签，用逗号分隔") }, modifier = Modifier.fillMaxWidth())
                    IconButton(onClick = { viewModel.tags(tags.split(',', '，')) }) { Icon(Icons.Outlined.Check, "保存本地标签") }
                    Text("笔记与高亮 · 仅本机", style = MaterialTheme.typography.titleMedium)
                }
                items(annotations, key = { it.id }) { annotation ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Column(Modifier.weight(1f).clickable { editor = "edit/" + annotation.id }) {
                            if (annotation.quote.isNotBlank()) Text(annotation.quote, style = MaterialTheme.typography.bodyMedium)
                            if (annotation.note.isNotBlank()) Text(annotation.note, style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton(onClick = { viewModel.deleteAnnotation(annotation.id) }) { Icon(Icons.Outlined.DeleteOutline, "删除本地记录") }
                    }
                    HorizontalDivider()
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun ArticleContent(content: String, url: String, fontSize: Int, annotations: List<ReaderAnnotation>,
    savedProgress: Float, onProgress: (Float) -> Unit, modifier: Modifier) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val progressCallback by rememberUpdatedState(onProgress)
    val progressValue by rememberUpdatedState(savedProgress)
    val html = remember(content, url, fontSize, dark, annotations) {
        val text = if (dark) "#e4e0d8" else "#24221f"
        val background = if (dark) "#191816" else "#faf8f3"
        """<!doctype html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: http: data:; style-src 'unsafe-inline'; form-action 'none'; base-uri 'none'">
        <style>body{font-family:Georgia,serif;font-size:${fontSize}px;line-height:1.85;padding:20px;max-width:680px;
        margin:auto;overflow-wrap:anywhere;color:$text;background:$background}img{max-width:100%;height:auto}pre{white-space:pre-wrap}a{color:#917348}</style>
        </head><body>${ReaderHtml.render(content.ifBlank { "<p>此文章没有缓存正文。</p>" }, url, annotations)}</body></html>"""
    }
    AndroidView(modifier = modifier.fillMaxWidth(), factory = { context ->
        WebView(context).apply {
            outlineProvider = ViewOutlineProvider.BOUNDS; clipToOutline = true
            settings.javaScriptEnabled = false; settings.allowFileAccess = false
            settings.allowContentAccess = false; settings.domStorageEnabled = false
            var lastProgress = savedProgress
            setOnScrollChangeListener { _, _, _, _, _ ->
                @Suppress("DEPRECATION") val extent = contentHeight * scale - height
                if (progress == 100 && extent > 0) {
                    val value = (scrollY / extent).coerceIn(0f, 1f)
                    if (kotlin.math.abs(value - lastProgress) >= 0.02f || value == 1f) {
                        lastProgress = value; progressCallback(value)
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, address: String) {
                    view.post {
                        @Suppress("DEPRECATION") val extent = (view.contentHeight * view.scale - view.height).coerceAtLeast(0f)
                        view.scrollTo(0, (extent * progressValue).toInt())
                    }
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (request.isForMainFrame && uri.scheme in listOf("https", "http")) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    return true
                }
            }
        }
    }, onRelease = { view ->
        @Suppress("DEPRECATION") val extent = view.contentHeight * view.scale - view.height
        if (view.progress == 100 && extent > 0) progressCallback((view.scrollY / extent).coerceIn(0f, 1f))
        view.destroy()
    }, update = { view -> if (view.tag != html) { view.tag = html; view.loadDataWithBaseURL(url.ifBlank { "https://www.newsblur.com/" }, html, "text/html", "utf-8", null) } })
}
