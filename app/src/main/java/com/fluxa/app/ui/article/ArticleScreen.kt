package com.fluxa.app.ui.article

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.fluxa.app.ui.components.UiState

@Composable
fun ArticleRoute(viewModel: ArticleViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    when (val state = uiState) {
        UiState.Loading -> CircularProgressIndicator()
        is UiState.Error -> Text(state.message)
        UiState.Empty -> Text("No article content")
        is UiState.Success -> Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { viewModel.addHighlight("默认高亮") }) { Text("addHighlight") }
                OutlinedButton(onClick = { viewModel.addNote("默认笔记") }) { Text("addNote") }
                Button(onClick = viewModel::archive) { Text("archive") }
                Button(onClick = viewModel::saveForLater) { Text("saveForLater") }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        webViewClient = WebViewClient()
                        settings.javaScriptEnabled = false
                    }
                },
                update = { webView ->
                    val css = "body{font-size:18px;line-height:1.7;padding:24px;max-width:720px;margin:auto;}"
                    val html = "<style>$css</style>${state.data.contentHtml}"
                    webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            )
        }
    }
}
