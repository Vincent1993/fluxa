package com.fluxa.app.ui.article

import android.content.Intent
import android.view.ViewOutlineProvider
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxa.app.ui.components.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleRoute(onBack: () -> Unit, viewModel: ArticleViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val fontSize by viewModel.fontSize.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(title = { Text("阅读") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回文章列表") }
        }, actions = {
            val current = state
            if (current is UiState.Success) {
                val article = current.data
                TextButton(onClick = viewModel::toggleStar) {
                    Text(if (article.isStarred) "取消收藏" else "收藏")
                }
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(16, 18, 20, 24).forEach { size ->
                    FilterChip(fontSize == size, { viewModel.setFontSize(size) }, { Text("${size}号") })
                }
            }
            error?.let { Text(it, Modifier.padding(16.dp)) }
            when (val articleState = state) {
                UiState.Loading -> CircularProgressIndicator()
                UiState.Empty -> Text("暂无内容")
                is UiState.Error -> Text(articleState.message, Modifier.padding(16.dp))
                is UiState.Success -> {
                    Text(articleState.data.title, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    ArticleContent(articleState.data.contentHtml, fontSize, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ArticleContent(content: String, fontSize: Int, modifier: Modifier) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val html = remember(content, fontSize, dark) {
        val text = if (dark) "#e6e1db" else "#20231f"
        val background = if (dark) "#161a17" else "#fafbf6"
        // Feed markup is untrusted: CSP blocks scripts, frames, forms and external
        // styles. Images may load online; HTML itself remains available offline.
        """<!doctype html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="Content-Security-Policy"
          content="default-src 'none'; img-src https: http: data:; style-src 'unsafe-inline'; form-action 'none'; base-uri 'none'">
        <style>body{font-size:${fontSize}px;line-height:1.8;padding:16px;max-width:720px;
        margin:auto;overflow-wrap:anywhere;color:$text;background:$background}
        img,video{max-width:100%;height:auto}pre{white-space:pre-wrap}a{color:#568a68}</style>
        </head><body>${content.ifBlank { "<p>此文章没有缓存正文。</p>" }}</body></html>"""
    }
    AndroidView(modifier = modifier.fillMaxWidth(), factory = { context ->
        WebView(context).apply {
            outlineProvider = ViewOutlineProvider.BOUNDS
            clipToOutline = true
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (request.isForMainFrame && (uri.scheme == "https" || uri.scheme == "http")) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }
                    return true
                }
            }
        }
    }, onRelease = { it.destroy() }, update = { view ->
        // State changes such as starring should not reset the reader's scroll.
        if (view.tag != html) {
            view.tag = html
            view.loadDataWithBaseURL("https://www.newsblur.com/", html, "text/html", "utf-8", null)
        }
    })
}
