package com.fluxa.app.ui.article

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.fluxa.app.ui.components.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleRoute(viewModel: ArticleViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val fontScalePreset by viewModel.fontScalePreset.collectAsState()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("阅读") },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            FontPresetSelector(
                selected = fontScalePreset,
                onSelect = viewModel::setFontScalePreset,
                modifier = Modifier.fillMaxWidth()
            )

            AnimatedContent(
                targetState = uiState,
                label = "article-state"
            ) { state ->
                when (state) {
                    UiState.Loading -> LoadingState()
                    is UiState.Error -> MessageState(state.message)
                    UiState.Empty -> MessageState("No article content")
                    is UiState.Success -> AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                webViewClient = WebViewClient()
                                settings.javaScriptEnabled = false
                            }
                        },
                        update = { webView ->
                            val css = """
                                body{
                                  font-size:${fontScalePreset.cssPx}px;
                                  line-height:1.8;
                                  padding:24px;
                                  max-width:760px;
                                  margin:auto;
                                }
                                img{max-width:100%;height:auto;}
                            """.trimIndent()
                            val html = "<style>$css</style>${state.data.contentHtml}"
                            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FontPresetSelector(
    selected: FontScalePreset,
    onSelect: (FontScalePreset) -> Unit,
    modifier: Modifier = Modifier
) {
    val presets = listOf(
        FontScalePreset.Small to "S",
        FontScalePreset.Medium to "M",
        FontScalePreset.Large to "L",
        FontScalePreset.ExtraLarge to "XL"
    )

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("字体大小", style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (preset, label) ->
                AssistChip(
                    onClick = { onSelect(preset) },
                    label = { Text(label) },
                    enabled = true,
                    border = if (preset == selected) {
                        androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun MessageState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message)
    }
}
