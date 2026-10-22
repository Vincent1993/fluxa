package com.fluxa.app.ui.article

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.view.MotionEvent
import android.view.Window
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import com.fluxa.app.ui.components.ActionRow
import com.fluxa.app.ui.components.IconAction
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxa.app.data.local.*
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.components.UiState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleRoute(onBack: () -> Unit, viewModel: ArticleViewModel = hiltViewModel()) {
    val presentation by viewModel.state.collectAsStateWithLifecycle()
    val state = when (val value = presentation.article) {
        is UiState.Success -> if (presentation.retainedAnnotations.isEmpty()) value else UiState.Success(value.data.copy(
            contentHtml = com.fluxa.app.data.reader.ReaderHtml.render(value.data.contentHtml, value.data.originalUrl, presentation.retainedAnnotations)))
        else -> value
    }
    val selectedId = (state as? UiState.Success<Article>)?.data?.id
    val settings by viewModel.reader.settings.collectAsStateWithLifecycle()
    val ready by viewModel.reader.ready.collectAsStateWithLifecycle()
    val session by viewModel.reader.session.collectAsStateWithLifecycle()
    val available by viewModel.availableIds.collectAsStateWithLifecycle()
    val adjacentTitles by viewModel.adjacentTitles.collectAsStateWithLifecycle()
    val error = presentation.error
    val mode = presentation.mode
    val fullText = presentation.fullText
    val extractionError = presentation.extractionError
    var showModes by remember(selectedId) { mutableStateOf(false) }
    var showTools by remember(selectedId) { mutableStateOf(false) }
    var showSpeech by remember(selectedId) { mutableStateOf(false) }
    var showTranslation by remember(selectedId) { mutableStateOf(false) }
    var showNotes by remember(selectedId) { mutableStateOf(false) }
    var showRetained by remember(selectedId) { mutableStateOf(false) }
    val notes = presentation.notes
    val readLaterUndo = presentation.readLaterUndo
    LaunchedEffect((state as? UiState.Success<Article>)?.data?.id, fullText) {
        showSpeech = false; showTranslation = false; showNotes = false; showTools = false
    }
    val unreadScope = rememberCoroutineScope()
    var unreadBusy by remember { mutableStateOf(false) }
    var unreadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var unreadMessage by remember(selectedId) { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val lease = Any()
        viewModel.attachActiveOwner(lease, lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.updateActiveOwner(lease, true)
            if (event == Lifecycle.Event.ON_PAUSE) { unreadJob?.cancel(); viewModel.updateActiveOwner(lease, false) }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { viewModel.releaseActiveOwner(lease); lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var showSettings by rememberSaveable(selectedId) { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var cue by remember { mutableStateOf<String?>(null) }
    var activePage by remember { mutableStateOf<ReaderPage?>(null) }
    var leaving by remember { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (showSettings) showSettings = false
        else if (!leaving) {
            leaving = true
            activePage?.retire()
            viewModel.persistRetainedProgress() // Idempotent: an unavailable/disposed page must not trap Back.
            unreadJob?.cancel(); viewModel.setActive(false); onBack()
        }
    }
    androidx.activity.compose.BackHandler(onBack = leave)
    val retireSelection: () -> Boolean = {
        (activePage?.retire() != false).also { accepted ->
            if (accepted) { cue = null; unreadMessage = null; chrome = true }
        }
    }
    val navigate: (String) -> Boolean = { target ->
        !leaving && !unreadBusy && viewModel.selectAdjacent(target, retireSelection)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state is UiState.Success, error, extractionError, showSettings) { chrome = true }
    val dark = settings.theme == ReadingTheme.Dark ||
        (settings.theme == ReadingTheme.System && isSystemInDarkTheme())
    val palette = when {
        settings.theme == ReadingTheme.Sepia -> lightColorScheme(
            background = Color(0xfff4ecd9), surface = Color(0xfff4ecd9),
            surfaceContainer = Color(0xffeee3cc), surfaceContainerHigh = Color(0xffeee3cc),
            surfaceContainerLowest = Color(0xfffaf3e5), surfaceContainerLow = Color(0xfff1e7d2),
            surfaceContainerHighest = Color(0xffe5d4b4), surfaceVariant = Color(0xffe5d4b4),
            onPrimary = Color(0xffffffff), primaryContainer = Color(0xffe5d4b4), onPrimaryContainer = Color(0xff383024),
            secondary = Color(0xff615643), onSecondary = Color(0xffffffff),
            tertiary = Color(0xff775328), onTertiary = Color(0xffffffff),
            tertiaryContainer = Color(0xffe5d4b4), onTertiaryContainer = Color(0xff383024),
            outline = Color(0xff81735c), outlineVariant = Color(0xffcdbfa5),
            onSurfaceVariant = Color(0xff615643), secondaryContainer = Color(0xffe5d4b4),
            onSecondaryContainer = Color(0xff383024),
            onBackground = Color(0xff383024), onSurface = Color(0xff383024), primary = Color(0xff775328))
        dark -> darkColorScheme(background = Color(0xff171b19), surface = Color(0xff171b19),
            surfaceContainer = Color(0xff222923), surfaceContainerHigh = Color(0xff29302a),
            surfaceContainerLowest = Color(0xff101411), surfaceContainerLow = Color(0xff1c211d),
            surfaceContainerHighest = Color(0xff343b35), surfaceVariant = Color(0xff414b43),
            onSurfaceVariant = Color(0xffc0cabf), outline = Color(0xff8b968c), outlineVariant = Color(0xff414b43),
            onPrimary = Color(0xff083921), primaryContainer = Color(0xff244f38), onPrimaryContainer = Color(0xffccebd6),
            secondary = Color(0xffb5ccba), onSecondary = Color(0xff213528),
            tertiary = Color(0xffdcc39b), onTertiary = Color(0xff3e2e16),
            tertiaryContainer = Color(0xff554329), onTertiaryContainer = Color(0xfff3dfbb),
            secondaryContainer = Color(0xff324a3c), onSecondaryContainer = Color(0xffe2e6df),
            onBackground = Color(0xffe2e6df), onSurface = Color(0xffe2e6df), primary = Color(0xff9ccdb0))
        else -> lightColorScheme(background = Color(0xfffafbf8), surface = Color(0xfffafbf8),
            surfaceContainer = Color(0xffeef2ea), surfaceContainerHigh = Color(0xffe7ece3),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xfff3f6ef),
            surfaceContainerHighest = Color(0xffe0e6dc), surfaceVariant = Color(0xffdfe6dc),
            onSurfaceVariant = Color(0xff4d584f), outline = Color(0xff707b71), outlineVariant = Color(0xffc1cabe),
            onPrimary = Color.White, primaryContainer = Color(0xffccebd6), onPrimaryContainer = Color(0xff123b27),
            secondary = Color(0xff526457), onSecondary = Color.White,
            tertiary = Color(0xff705c3c), onTertiary = Color.White,
            tertiaryContainer = Color(0xfff3dfbb), onTertiaryContainer = Color(0xff3b2c15),
            secondaryContainer = Color(0xffd5e8d9), onSecondaryContainer = Color(0xff222923),
            onBackground = Color(0xff222923), onSurface = Color(0xff222923), primary = Color(0xff286347))
    }
    MaterialTheme(colorScheme = palette) {
    ReaderSystemBars(dark)
    Box(Modifier.fillMaxSize().background(palette.background).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            when (val current = state) {
                UiState.Loading -> CircularProgressIndicator(Modifier.padding(top = 88.dp))
                UiState.Empty -> Text("暂无内容", Modifier.padding(top = 88.dp))
                is UiState.Error -> Text(current.message, Modifier.padding(top = 88.dp, start = 16.dp, end = 16.dp))
                is UiState.Success -> {
                    val id = current.data.id
                    val previous = session.adjacent(id, -1)
                    val next = session.adjacent(id, 1)
                    val positionKey = readerPositionKey(id, fullText != null)
                    if (ready) {
                        ArticleContent(current.data.copy(contentHtml = fullText ?: current.data.contentHtml), settings, viewModel.reader, Modifier.weight(1f),
                            previous != null && previous in available, next != null && next in available,
                            { chrome = it || showSettings || error != null || extractionError != null }, { message ->
                                val neighbor = if (message?.startsWith("下一篇") == true) next else previous
                                cue = message?.let { text -> neighbor?.let { adjacentTitles[it] }?.let { "$text\n$it" } ?: text }
                            }, { direction ->
                                session.adjacent(id, direction)?.takeIf { it in available }?.let {
                                    navigate(it)
                                } ?: false
                            }, { activePage = it }, positionKey, notes,
                            if (fullText != null) "FULL_TEXT" else if (current.data.source == com.fluxa.app.data.directrss.SAVED_PAGE_SOURCE) "SAVED" else "RSS")
                    }
                }
            }
        }
        if (chrome) TopAppBar(title = {
            val article = when (val value = state) { is UiState.Success -> value.data; else -> null }
            Text(if (article == null) "阅读" else "${session.ids.indexOf(article.id).let { if (it < 0) 0 else it + 1 }} / ${session.ids.size}",
                style = MaterialTheme.typography.labelLarge)
        }, navigationIcon = {
            IconAction("返回文章列表", Icons.AutoMirrored.Outlined.ArrowBack, leave)
        }, actions = {
            val article = (state as? UiState.Success<Article>)?.data
            if (article != null && article.source.startsWith(com.fluxa.app.data.directrss.LOCAL_PREFIX) && article.source != com.fluxa.app.data.directrss.SAVED_PAGE_SOURCE)
                IconAction("正文模式", Icons.Outlined.Article, { showModes = true })
            IconAction("阅读设置", Icons.Outlined.FormatSize, { showSettings = true })
            IconAction("更多阅读工具", Icons.Outlined.MoreVert, { showTools = true }, enabled = article != null)
        }, windowInsets = WindowInsets(0, 0, 0, 0))
        if (!chrome) Surface(
            modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(8.dp).size(48.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
        ) {
            IconAction("显示阅读工具", Icons.Outlined.Tune, { chrome = true })
        }
        (error ?: extractionError)?.let { message -> Surface(Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 64.dp),
            color = MaterialTheme.colorScheme.errorContainer) { Text(message, Modifier.padding(12.dp)) } }
        cue?.let { text -> Surface(Modifier.align(if (text.startsWith("下一篇"))
            androidx.compose.ui.Alignment.BottomCenter else androidx.compose.ui.Alignment.TopCenter)
            .padding(horizontal = 16.dp).padding(top = 64.dp, bottom = 64.dp),
            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
            Column(Modifier.padding(12.dp).semantics {
                contentDescription = if (text.startsWith("下一篇")) "下一篇" else "上一篇"
                stateDescription = text
            }, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Icon(if (text.startsWith("下一篇")) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward, null)
                LinearProgressIndicator(progress = {
                    Regex("(\\d+)%").find(text)?.groupValues?.get(1)?.toFloat()?.div(100f) ?: 0f
                }, modifier = Modifier.width(40.dp).padding(top = 4.dp))
            }
        } }
        val current = state
        if (chrome && current is UiState.Success) {
                    val id = current.data.id
                    val previous = session.adjacent(id, -1)
                    val next = session.adjacent(id, 1)
                    Surface(modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly) {
                            IconAction(if (previous == null) "已是首篇" else if (previous !in available) "上一篇暂不可用" else "上一篇",
                                Icons.Outlined.ArrowUpward, { previous?.takeIf { it in available }?.let { navigate(it) }; Unit },
                                modifier = Modifier.weight(1f), enabled = !unreadBusy && previous != null && previous in available)
                            IconAction(if (current.data.isStarred) "取消收藏" else "收藏",
                                if (current.data.isStarred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                viewModel::toggleStar, modifier = Modifier.weight(1f), selected = current.data.isStarred)
                            IconAction(if (next == null) "已是末篇" else if (next !in available) "下一篇暂不可用" else "下一篇",
                                Icons.Outlined.ArrowDownward, { next?.takeIf { it in available }?.let { navigate(it) }; Unit },
                                modifier = Modifier.weight(1f), enabled = !unreadBusy && next != null && next in available)
                            IconAction("跨来源下一未读", Icons.Outlined.MarkEmailUnread, {
                                if (com.fluxa.app.BuildConfig.DEBUG) android.util.Log.d("ReaderSelectionOwned",
                                    "next-unread requested page=${activePage?.diagnosticState()} retired=${activePage?.isRetired}")
                                unreadBusy = true
                                unreadJob = unreadScope.launch {
                                    try {
                                        val target = viewModel.nextUnread(retireSelection)
                                        if (com.fluxa.app.BuildConfig.DEBUG) android.util.Log.d("ReaderSelectionOwned",
                                            "next-unread completed accepted=${target != null} page=${activePage?.diagnosticState()}")
                                        if (target == null) unreadMessage = "已读到未读队列末尾；当前没有其他可读的未读文章。"
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                    catch (_: Exception) { unreadMessage = "暂时无法打开下一未读，请重试。" }
                                    finally { unreadBusy = false }
                                }
                            }, modifier = Modifier.weight(1f), enabled = !unreadBusy)
                            val original = com.fluxa.app.data.directrss.safeUrl(current.data.originalUrl)
                            IconAction("阅读原文 · 在浏览器打开", Icons.Outlined.OpenInBrowser,
                                enabled = original != null, modifier = Modifier.weight(1f), onClick = {
                                    original?.let { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } }
                                })
                            IconAction("调整阅读排版", Icons.Outlined.FormatSize, { showSettings = true }, modifier = Modifier.weight(1f))
                        }
                    }
        }
    }
    unreadMessage?.let { message -> AlertDialog(onDismissRequest = { unreadMessage = null },
        title = { Text("下一未读") }, text = { Text(message) },
        confirmButton = { IconAction("返回文章列表", Icons.Outlined.List, { unreadMessage = null; leave() }) },
        dismissButton = { IconAction("继续阅读当前文章", Icons.Outlined.Close, { unreadMessage = null }) }) }
    if (showModes) ModalBottomSheet(onDismissRequest = { showModes = false }) {
        Text("此来源的正文模式", Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            com.fluxa.app.data.directrss.LocalReadingMode.entries.forEach { choice ->
                val label = when (choice) {
                    com.fluxa.app.data.directrss.LocalReadingMode.RSS -> "RSS 正文"
                    com.fluxa.app.data.directrss.LocalReadingMode.FULL_TEXT -> "提取全文"
                    com.fluxa.app.data.directrss.LocalReadingMode.ORIGINAL -> "原网页 · 在浏览器打开"
                }
                ActionRow(label, when (choice) {
                    com.fluxa.app.data.directrss.LocalReadingMode.RSS -> Icons.Outlined.RssFeed
                    com.fluxa.app.data.directrss.LocalReadingMode.FULL_TEXT -> Icons.Outlined.Article
                    com.fluxa.app.data.directrss.LocalReadingMode.ORIGINAL -> Icons.Outlined.OpenInBrowser
                }, {
                    (state as? UiState.Success<Article>)?.data?.let { article ->
                        activePage?.capture()
                        viewModel.selectMode(article.source, choice)
                        if (choice == com.fluxa.app.data.directrss.LocalReadingMode.ORIGINAL)
                            com.fluxa.app.data.directrss.safeUrl(article.originalUrl)?.let { url ->
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                            }
                    }
                    showModes = false
                }, selected = mode == choice)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    val toolsArticle = (state as? UiState.Success<Article>)?.data
    if (showSpeech && toolsArticle != null) key(toolsArticle.id, fullText) {
        ReaderSpeechSheet(fullText ?: toolsArticle.contentHtml) { showSpeech = false }
    }
    if (showTranslation && toolsArticle != null) key(toolsArticle.id, fullText) {
        ReaderTranslationSheet(fullText ?: toolsArticle.contentHtml) { showTranslation = false }
    }
    if (showNotes && toolsArticle != null) key(toolsArticle.id, fullText) {
        ReaderNotesSheet(toolsArticle.id,
            if (fullText != null) "FULL_TEXT" else if (toolsArticle.source == com.fluxa.app.data.directrss.SAVED_PAGE_SOURCE) "SAVED" else "RSS",
            fullText ?: toolsArticle.contentHtml, notes, viewModel::saveNote,
            { noteId -> viewModel.deleteNote(noteId, toolsArticle.id) }) { showNotes = false }
    }
    if (showRetained && toolsArticle != null) RetainedReadingSheet(toolsArticle, { showRetained = false })
    if (showTools && toolsArticle != null) ModalBottomSheet(onDismissRequest = { showTools = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text("阅读工具", style = MaterialTheme.typography.titleLarge)
            if (toolsArticle.source.startsWith(com.fluxa.app.data.directrss.LOCAL_PREFIX) &&
                toolsArticle.source != com.fluxa.app.data.directrss.SAVED_PAGE_SOURCE) {
                ActionRow(if (toolsArticle.readLater) "移出稍后读" else "加入稍后读", Icons.Outlined.BookmarkAdd,
                    viewModel::toggleReadLater, selected = toolsArticle.readLater)
                if (readLaterUndo != null) ActionRow("撤销稍后读更改", Icons.Outlined.Undo, viewModel::undoReadLater)
            }
            if (!toolsArticle.id.startsWith(com.fluxa.app.data.directrss.LOCAL_PREFIX))
                ActionRow("本地笔记、高亮与标签", Icons.Outlined.EditNote, { showTools = false; showRetained = true })
            ActionRow("端侧朗读", Icons.Outlined.VolumeUp, { showTools = false; showSpeech = true })
            ActionRow("端侧翻译", Icons.Outlined.Translate, { showTools = false; showTranslation = true })
            if (toolsArticle.source.startsWith(com.fluxa.app.data.directrss.LOCAL_PREFIX))
                ActionRow("段落摘录与笔记", Icons.Outlined.EditNote, { showTools = false; showNotes = true })
            ActionRow("关闭阅读工具", Icons.Outlined.Close, { showTools = false })
        }
    }
    if (showSettings) ModalBottomSheet(onDismissRequest = { showSettings = false },
        containerColor = palette.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ReaderSystemBars(dark)
        Column(Modifier.fillMaxWidth().heightIn(max = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * .85f)
            .padding(horizontal = 24.dp).imePadding()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("阅读设置", style = MaterialTheme.typography.headlineSmall)
                Surface(shape = RoundedCornerShape(20.dp), color = palette.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().animateContentSize()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("阅读预览", style = MaterialTheme.typography.labelMedium, color = palette.primary)
                        Text(when (val preview = state) {
                            is UiState.Success -> preview.data.title
                            else -> "阅读"
                        },
                            modifier = Modifier.padding(top = 8.dp),
                            fontSize = settings.fontSize.sp,
                            lineHeight = (settings.fontSize * settings.lineSpacing).sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("字号 ${settings.fontSize}", style = MaterialTheme.typography.titleSmall)
                Slider(settings.fontSize.toFloat(), { viewModel.reader.update(settings.copy(fontSize = it.toInt())) },
                    valueRange = 14f..30f, steps = 15,
                    modifier = Modifier.testTag("reader-font").semantics { contentDescription = "阅读字号" })
                Text("行距 %.1f".format(settings.lineSpacing), style = MaterialTheme.typography.titleSmall)
                Slider(settings.lineSpacing, { viewModel.reader.update(settings.copy(lineSpacing = it)) }, valueRange = 1.3f..2.4f,
                    modifier = Modifier.testTag("reader-spacing").semantics { contentDescription = "阅读行距" })
                Text("阅读配色", style = MaterialTheme.typography.titleSmall)
                ReadingTheme.entries.chunked(2).forEach { themes ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        themes.forEach { theme ->
                            val label = when (theme) { ReadingTheme.System -> "跟随系统"; ReadingTheme.Light -> "浅色"; ReadingTheme.Dark -> "深色"; ReadingTheme.Sepia -> "纸色" }
                            ActionRow(label, when (theme) {
                                ReadingTheme.System -> Icons.Outlined.BrightnessAuto
                                ReadingTheme.Light -> Icons.Outlined.LightMode
                                ReadingTheme.Dark -> Icons.Outlined.DarkMode
                                ReadingTheme.Sepia -> Icons.Outlined.Palette
                            }, { viewModel.reader.update(settings.copy(theme = theme)) },
                                selected = settings.theme == theme, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            ActionRow("完成", Icons.Outlined.Check, { showSettings = false }, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
    }
}

/** RSS/saved-page keys remain byte-for-byte compatible; full text owns a separate anchor lease. */
internal fun readerPositionKey(id: String, fullText: Boolean): String = if (fullText) "$id::fulltext" else id

internal fun readerHtml(article: Article, settings: ReaderSettings, dark: Boolean): String {
    val colors = when {
        settings.theme == ReadingTheme.Sepia -> Triple("#f4ecd9", "#383024", "#775328")
        settings.theme == ReadingTheme.Dark || (settings.theme == ReadingTheme.System && dark) -> Triple("#171b19", "#e2e6df", "#9ccdb0")
        else -> Triple("#fafbf8", "#222923", "#286347")
    }
    val metadataColor = when (colors.first) {
        "#f4ecd9" -> "#756a58"
        "#171b19" -> "#9da69c"
        else -> "#737771"
    }
    fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    return """<!doctype html><html><head><title>${escape(article.title)}</title><meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: http: data:; style-src 'unsafe-inline'; form-action 'none'; base-uri 'none'">
        <style>
        :root{color-scheme:${if (colors.first == "#171b19") "dark" else "light"}}
        *{box-sizing:border-box}html{background:${colors.first};color:${colors.second}}
        body{font-family:system-ui,sans-serif;font-size:${settings.fontSize}px;line-height:${settings.lineSpacing};max-width:680px;margin:auto;padding:96px 24px 112px;overflow-wrap:anywhere;word-break:normal}
        h1{font-size:1.65em;line-height:1.35;font-weight:600;letter-spacing:-.02em;text-wrap:balance;margin:.45em 0 .55em}header{margin-bottom:2em;padding-bottom:1.4em;border-bottom:1px solid ${colors.second}20}header .article-date,header .article-source{font-size:.75em;font-weight:400;line-height:1.6;color:$metadataColor;margin:0}header .article-source{font-weight:600}
        h2{font-size:1.2em;line-height:1.45;font-weight:600;margin:1.8em 0 .7em}h3{font-size:1.05em;line-height:1.5;margin:1.5em 0 .6em}
        p{margin:1.1em 0}a{color:${colors.third};text-underline-offset:.18em}footer{margin-top:2.5em;padding-top:1em;border-top:1px solid ${colors.second}20;font-size:.85em}img,video,svg{max-width:100%;height:auto}img{border-radius:12px}figure{margin:1.4em 0}figcaption{font-size:.8em;line-height:1.6;opacity:.75;margin-top:.6em}
        blockquote{margin:1.4em 0;padding:.6em 1em;border-left:3px solid ${colors.third};border-inline-start:3px solid ${colors.third};background:rgba(128,128,128,.12)}
        pre{overflow-x:auto;padding:12px;background:rgba(128,128,128,.12);white-space:pre;font-size:.85em}
        code{font-family:monospace;overflow-wrap:anywhere}table{display:block;max-width:100%;overflow-x:auto;border-collapse:collapse}
        td,th{padding:.5em;border:1px solid #888;min-width:5em}iframe,form,object,embed{display:none}
        </style></head><body><header><p class="article-date">${article.publishedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate()}</p><h1>${escape(article.title)}</h1><p class="article-source">${escape(article.feedName)}</p></header>
        <main>${article.contentHtml.ifBlank { "<p>此文章尚未保存正文。可返回刷新订阅，或打开原文阅读。</p>" }}</main>${com.fluxa.app.data.directrss.safeUrl(article.originalUrl)?.let { "<footer><p><a href=\"${escape(it)}\">阅读原文 · 在浏览器打开</a></p>${article.sourceNote.takeIf { it.isNotBlank() }?.let { note -> "<p>${escape(note)}</p>" }.orEmpty()}</footer>" }.orEmpty()}</body></html>"""
}

@Composable
internal fun ArticleContent(article: Article, settings: ReaderSettings, store: ReaderPreferences, modifier: Modifier,
    previous: Boolean, next: Boolean, onChrome: (Boolean) -> Unit, onCue: (String?) -> Unit,
    onTurn: (Int) -> Boolean, onPage: (ReaderPage) -> Unit, positionKey: String = article.id,
    notes: List<com.fluxa.app.data.directrss.DirectReaderNote> = emptyList(), noteMode: String = "RSS") {
    val dark = isSystemInDarkTheme()
    val fontScale = LocalConfiguration.current.fontScale
    // Match readerHtml's actual document palette, including system theme, without
    // parsing HTML or relying on the caller's (possibly different) Material theme.
    val loadingBackground = when {
        settings.theme == ReadingTheme.Sepia -> Color(0xfff4ecd9)
        settings.theme == ReadingTheme.Dark || (settings.theme == ReadingTheme.System && dark) -> Color(0xff171b19)
        else -> Color(0xfffafbf8)
    }.toArgb()
    val html = remember(article.title, article.feedName, article.publishedAt, article.contentHtml, article.originalUrl, article.sourceNote, settings, dark, notes, noteMode) {
        val marked = runCatching { com.fluxa.app.data.directrss.ReaderQuotes.highlight(article.contentHtml, noteMode, notes) }.getOrDefault(article.contentHtml)
        readerHtml(article.copy(contentHtml = marked), settings, dark)
    }
    val owner = LocalLifecycleOwner.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember(positionKey, store, owner, context) { ReaderPage(positionKey, store) }
    var image by remember(positionKey) { mutableStateOf<String?>(null) }
    val embedded = remember(article.contentHtml) { ReaderImageResource.embedded(article.contentHtml) }
    val imageAlts = remember(article.contentHtml) {
        org.jsoup.Jsoup.parseBodyFragment(article.contentHtml).select("img[src][alt]")
            .filter { it.attr("alt").isNotBlank() }
            .associate { it.attr("src") to it.attr("alt").take(1000) }
    }
    if (image != null) ReaderImageViewer(checkNotNull(image), imageAlts[checkNotNull(image)]) { image = null }
    SideEffect {
        controller.onImage = { source ->
            controller.capture()
            if (source in embedded) image = source
            else android.widget.Toast.makeText(context, "此图片未保存，无法离线放大", android.widget.Toast.LENGTH_SHORT).show()
        }
        onPage(controller)
        controller.previous = previous; controller.next = next
        controller.onChrome = onChrome; controller.onCue = onCue; controller.onTurn = onTurn
    }
    DisposableEffect(owner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) controller.cancelGesture()
            if (event == Lifecycle.Event.ON_STOP) { controller.capture(); controller.cancelGesture() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); controller.release() }
    }
    // Only the native host is stable across positionKey changes. Never carry it into
    // another Context/Activity or lifecycle owner; article controllers remain independent.
    key(owner, context) {
    AndroidView(modifier = modifier.fillMaxWidth().testTag("reader-content"), factory = { context ->
        ReaderWebView(context).apply {
            // Keep Chromium's native document subtree: a host contentDescription can
            // replace its text for accessibility services. The HTML already has a heading.
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
            // Reader resources are embedded cache only. Links still leave through ACTION_VIEW.
            // This also prevents WebView from bypassing local RSS Wi-Fi/byte budgets.
            this.settings.blockNetworkLoads = true
            this.settings.javaScriptEnabled = false
            this.settings.allowFileAccess = false
            this.settings.allowContentAccess = false
            this.settings.domStorageEnabled = false
            // Body scale belongs to the reader's font setting. Chromium pinch zoom
            // otherwise changes native range/position outside that persisted state.
            // Embedded images use the separate ZoomImageView and retain pinch/pan.
            this.settings.setSupportZoom(false)
            this.settings.builtInZoomControls = false
            this.settings.displayZoomControls = false
        }
    }, onRelease = { it.disposeReader() }, update = { it.render(controller, html, (fontScale * 100).toInt(), loadingBackground) })
    }
}

/** Leases permit overlapping navigation compositions and out-of-order disposal. */
@Suppress("DEPRECATION")
private object ReaderBarLeases {
    private data class Lease(val owner: Any, var light: Boolean, var color: Int)
    private data class State(val status: Boolean, val navigation: Boolean,
        val color: Int?, val leases: MutableList<Lease>)
    private val windows = java.util.WeakHashMap<Window, State>()
    fun apply(window: Window, owner: Any, light: Boolean, color: Int) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val state = windows.getOrPut(window) { State(controller.isAppearanceLightStatusBars,
            controller.isAppearanceLightNavigationBars,
            if (Build.VERSION.SDK_INT in 26..34) window.navigationBarColor else null, mutableListOf()) }
        val lease = state.leases.firstOrNull { it.owner === owner }
        if (lease == null) state.leases.add(Lease(owner, light, color)) else {
            lease.light = light
            lease.color = color
        }
        controller.isAppearanceLightStatusBars = state.leases.last().light
        controller.isAppearanceLightNavigationBars = state.leases.last().light
        // API 35+ keeps platform-enforced transparent edge-to-edge bars untouched.
        if (state.color != null) window.navigationBarColor = state.leases.last().color
    }
    fun release(window: Window, owner: Any) {
        val state = windows[window] ?: return
        val previous = state.leases.lastOrNull() ?: return
        state.leases.removeAll { it.owner === owner }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        // An older reader must not overwrite a newer reader, or a subsequent external
        // theme owner that has already changed this Window's flags.
        if (previous.owner === owner && controller.isAppearanceLightStatusBars == previous.light &&
            controller.isAppearanceLightNavigationBars == previous.light) {
            controller.isAppearanceLightStatusBars = state.leases.lastOrNull()?.light ?: state.status
            controller.isAppearanceLightNavigationBars = state.leases.lastOrNull()?.light ?: state.navigation
        }
        // Restore only the last owner's still-current color, never an external change.
        if (previous.owner === owner && state.color != null && window.navigationBarColor == previous.color) {
            window.navigationBarColor = state.leases.lastOrNull()?.color ?: state.color
        }
        if (state.leases.isEmpty()) windows.remove(window)
    }
}
private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.activity()
    else -> null
}
@Composable
private fun ReaderSystemBars(dark: Boolean) {
    val view = LocalView.current
    val color = MaterialTheme.colorScheme.background.toArgb()
    val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.activity()?.window
    val owner = remember(window) { Any() }
    DisposableEffect(window, owner) {
        onDispose { window?.let { ReaderBarLeases.release(it, owner) } }
    }
    SideEffect { window?.let { ReaderBarLeases.apply(it, owner, !dark, color) } }
}

/** All methods run on the main thread. Each load owns a distinct URL and generation. */
internal class ReaderPage(private val id: String, private val store: ReaderPreferences) {
    companion object {
        private var lastTurnAt = -1000L
        private val documents = java.util.concurrent.atomic.AtomicLong()
    }
    var previous = false
    var next = false
    var onChrome: (Boolean) -> Unit = {}
    var onCue: (String?) -> Unit = {}
    var onTurn: (Int) -> Boolean = { false }
    var onImage: (String) -> Unit = {}
    private var multiPointerStream = false
    private var downAt = 0L
    private var tapSerial = 0L
    private var tapMoved = false
    private fun imageHit(view: WebView): String? = view.hitTestResult?.let {
        if (it.type == WebView.HitTestResult.IMAGE_TYPE || it.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) it.extra else null
    }
    private var retired = false
    private var documentReady = false
    internal val isRetired get() = retired
    private val work = android.os.Handler(android.os.Looper.getMainLooper())
    private var cancelTaps: (() -> Unit)? = null
    private fun post(view: WebView, delay: Long = 0, action: () -> Unit) {
        work.postDelayed({ if (!retired && web === view) action() }, delay)
    }
    private var pull: BoundaryPull? = null
    private var scrollOwnership: ReaderScrollOwnership? = null
    private var finger = false
    private var claimed = false
    // A canceled native stream must stay swallowed through its terminal event,
    // independently of generation/geometry/lifecycle gesture resets.
    private var draining = false
    private var edgeStream = false
    private var startX = 0f
    private var startY = 0f
    private var lastScroll = 0L
    private var gestureGeometry = Triple(0, 0, 0)
    private val guard = PageRestoreGuard()
    private var token = 0L
    private var web: WebView? = null
    private var signature: Pair<String, Int>? = null
    private var target = ReadingPosition()
    private var blocks = emptyList<NativeBlock>()
    private var snapshot: NativeDocumentSnapshot? = null
    private var userInteracted = false
    private var programmaticScroll = false
    private var snapshotSerial = 0L
    private var settlingGeneration = -1L
    private var accessibility: android.view.accessibility.AccessibilityManager? = null
    private var samplingBlocked = false
    private val accessibilityListener = android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener {
        accessibilityChanged()
    }
    private val explorationListener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener {
        accessibilityChanged()
    }
    private fun assistBlocked() = accessibility?.let { it.isEnabled && it.isTouchExplorationEnabled } == true
    private fun invalidateSnapshot() {
        snapshotSerial++
        snapshot?.cancel(); snapshot = null
        blocks = emptyList()
    }
    private fun accessibilityChanged() {
        val view = web ?: return
        if (retired) return
        val blocked = assistBlocked()
        if (blocked == samplingBlocked) return
        samplingBlocked = blocked
        // Invalidate even on disable: an earlier assist completion must not supply
        // geometry after a policy transition, reflow, or controller replacement.
        invalidateSnapshot()
        if (!documentReady) return
        if (guard.canWrite(id, token)) {
            // A policy toggle is not a request to return to the opening position.
            // Capture today's pixels without stale semantic geometry before resampling.
            capture()
            target = store.position(id)
        }
        if (assistBlocked()) finishRestore(view, token) else refresh(view, token, 0)
    }
    internal fun diagnosticState() = "keyHash=${com.fluxa.app.data.directrss.stableHash(id)} generation=$token retired=$retired ready=$documentReady " +
        "writable=${guard.canWrite(id, token)} snapshotSerial=$snapshotSerial blocks=${blocks.size} assistBlocked=${assistBlocked()}"
    private fun range(view: WebView) = (view as ReaderWebView).nativeMaxScrollOffset
    fun capture() {
        if (retired) return
        val view = web ?: return
        if (!guard.canWrite(id, token)) return
        if (assistBlocked()) invalidateSnapshot()
        val range = range(view)
        store.savePosition(id, NativeAnchors.capturePosition(blocks, view.scrollY, range,
            previous = store.position(id), preferred = if (!userInteracted) target.anchor else null))
    }
    fun attach(view: WebView) {
        check(!retired && web == null && (view as ReaderWebView).owns(this))
        web = view
        accessibility = view.context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        samplingBlocked = assistBlocked()
        accessibility?.addAccessibilityStateChangeListener(accessibilityListener)
        accessibility?.addTouchExplorationStateChangeListener(explorationListener)
        // Any terminal events from the old document are not a new gesture.
        // A fresh DOWN below is the only way to acquire this controller's stream.
        draining = true
        var pendingTap: Pair<Long, Long>? = null
        val taps = android.view.GestureDetector(view.context, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Native tap confirmation follows the double-tap arbitration window. Chromium
                // receives the complete stream; one read only, no polling and no synthetic hit.
                post(view) {
                    val candidate = pendingTap ?: return@post
                    pendingTap = null
                    if (!retired && web === view && view.isAttachedToWindow && candidate.first == tapSerial &&
                        guard.accepts(id, candidate.second) && !(view as ReaderWebView).selectionActive) {
                        imageHit(view)?.let(onImage)
                    }
                }
                return false
            }
            override fun onDoubleTap(e: MotionEvent): Boolean { pendingTap = null; tapSerial++; return false }
        }).apply { setIsLongpressEnabled(false) }
        cancelTaps = {
            pendingTap = null
            val now = android.os.SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            try { taps.onTouchEvent(cancel) } finally { cancel.recycle() }
        }
        var longClickDiagnostics = 0
        view.setOnLongClickListener {
            if (com.fluxa.app.BuildConfig.DEBUG && longClickDiagnostics++ < 4) {
                val hit = view.hitTestResult
                android.util.Log.d("ReaderNativeLifecycle",
                    "long-click view=${System.identityHashCode(view)} generation=$token uptime=${android.os.SystemClock.uptimeMillis()} type=${hit.type} sourceLength=${hit.extra?.length} sourceHash=${hit.extra?.let { com.fluxa.app.data.directrss.stableHash(it) }} retired=$retired current=${web === view} attached=${view.isAttachedToWindow} multiPointer=$multiPointerStream edge=$edgeStream")
            }
            if (retired || !documentReady || web !== view || multiPointerStream || edgeStream) return@setOnLongClickListener false
            val source = imageHit(view)
            cancelGesture(cancelNative = false)
            if (source != null) { onImage(source); true } else false
        }
        val density = view.resources.displayMetrics.density
        val gesture = BoundaryPull(android.view.ViewConfiguration.get(view.context).scaledTouchSlop.toFloat(), 88f * density)
        pull = gesture
        val ownership = ReaderScrollOwnership(android.view.ViewConfiguration.get(view.context).scaledTouchSlop.toFloat())
        scrollOwnership = ownership
        view.setOnScrollChangeListener { _, _, y, _, oldY ->
            if (retired) return@setOnScrollChangeListener
            lastScroll = android.os.SystemClock.uptimeMillis()
            // Chromium may deliver the drag/fling after UP. Attribute only matching
            // actual motion, within the same geometry/generation and bounded lease.
            if (gestureGeometry != Triple(view.width, view.height, range(view)) || programmaticScroll) ownership.clear()
            if (!claimed && !programmaticScroll && guard.canWrite(id, token) &&
                ownership.accepts(token, y - oldY, lastScroll)) {
                userInteracted = true
                if (kotlin.math.abs(y - oldY) > 2) onChrome(y < oldY)
                if (!view.canScrollVertically(1) || !view.canScrollVertically(-1)) onChrome(true)
            }
            capture()
        }
        view.setOnTouchListener { _, event ->
            // The reused host can still display outgoing pixels until visual commit.
            // Those pixels cannot acquire the incoming article's gesture/position lease.
            if (retired || !documentReady) return@setOnTouchListener true
            if (draining && event.actionMasked != MotionEvent.ACTION_DOWN) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) draining = false
                return@setOnTouchListener true
            }
            taps.onTouchEvent(event) // observe, never consume Chromium's native DOWN/UP
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pendingTap = null
                    tapSerial++
                    tapMoved = false
                    draining = false
                    multiPointerStream = false
                    downAt = event.eventTime
                    val now = android.os.SystemClock.uptimeMillis()
                    val stable = guard.canWrite(id, token) && now - lastScroll > 180 && now - lastTurnAt > 700
                    gestureGeometry = Triple(view.width, view.height, range(view))
                    finger = true; claimed = false
                    startX = event.x; startY = event.y
                    val boundary = when {
                        !view.canScrollVertically(-1) && previous -> -1
                        !view.canScrollVertically(1) && next -> 1
                        else -> 0
                    }
                    val insets = ViewCompat.getRootWindowInsets(view)
                        ?.getInsets(WindowInsetsCompat.Type.systemGestures())
                    val leftEdge = maxOf(24f * density, insets?.left?.toFloat() ?: 0f)
                    val rightEdge = maxOf(24f * density, insets?.right?.toFloat() ?: 0f)
                    edgeStream = event.x < leftEdge || event.x > view.width - rightEdge
                    ownership.begin(token, edgeStream || !guard.canWrite(id, token))
                    gesture.begin(token, boundary, range(view) > view.height, edgeStream,
                        stable && !(view as ReaderWebView).selectionActive)
                    userInteracted = true
                    guard.rendered(id, token)
                }
                MotionEvent.ACTION_MOVE -> {
                    val slop = android.view.ViewConfiguration.get(view.context).scaledTouchSlop
                    if (kotlin.math.abs(event.x - startX) >= slop || kotlin.math.abs(event.y - startY) >= slop) tapMoved = true
                    if (gestureGeometry != Triple(view.width, view.height, range(view))) {
                        cancelGesture()
                        return@setOnTouchListener draining
                    }
                    // DOWN has now reached Chromium, so hitTest belongs to this native stream.
                    if ((view as ReaderWebView).selectionActive || imageHit(view) != null) gesture.cancel()
                    if (event.pointerCount != 1) ownership.clear()
                    else ownership.move(event.x - startX, event.y - startY)
                    val take = gesture.move(event.x - startX, event.y - startY, event.pointerCount)
                    if (take && !claimed) {
                        ownership.clear()
                        claimed = true
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        view.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                    onCue(if (gesture.progress > 0f) {
                        (if (gesture.direction > 0) "下一篇" else "上一篇") + " · " +
                            if (gesture.progress >= 1f) "松手切换 100%" else "继续拉动 ${(gesture.progress * 100).toInt()}%"
                    } else null)
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    multiPointerStream = true
                    // Body text uses the persisted font setting, not transient pinch
                    // scale. Cancel Chromium's single-pointer stream before it sees
                    // the second pointer, then drain through the final UP. Merely
                    // rejecting pagination leaves the native zoom stream alive.
                    // Android edge streams and the separate image viewer keep their
                    // own input handling.
                    cancelGesture()
                    return@setOnTouchListener draining
                }
                MotionEvent.ACTION_CANCEL -> {
                    tapSerial++
                    val consumed = claimed || draining
                    ownership.clear()
                    gesture.cancel(); finger = false; claimed = false; draining = false; onCue(null)
                    return@setOnTouchListener consumed
                }
                MotionEvent.ACTION_UP -> {
                    finger = false
                    val slop = android.view.ViewConfiguration.get(view.context).scaledTouchSlop
                    if (!tapMoved && !multiPointerStream && !edgeStream && !claimed && !(view as ReaderWebView).selectionActive &&
                        event.eventTime - downAt < android.view.ViewConfiguration.getLongPressTimeout() &&
                        kotlin.math.abs(event.x - startX) < slop && kotlin.math.abs(event.y - startY) < slop) {
                        pendingTap = tapSerial to token
                    }
                    if (gestureGeometry != Triple(view.width, view.height, range(view))) {
                        gesture.cancel(); ownership.clear()
                    }
                    if (claimed) ownership.clear() else ownership.release(android.os.SystemClock.uptimeMillis())
                    val turn = gesture.release(token)
                    onCue(null)
                    val consumed = claimed
                    claimed = false
                    if (turn != 0) {
                        // The navigation callback retires synchronously only when a neighbor exists.
                        // Rejected navigation leaves this page usable, without a dead input lease.
                        onChrome(true)
                        if (onTurn(turn)) lastTurnAt = android.os.SystemClock.uptimeMillis()
                    }
                    return@setOnTouchListener consumed
                }
            }
            claimed
        }
    }
    fun cancelGesture(cancelNative: Boolean = true) {
        tapSerial++
        cancelTaps?.invoke()
        scrollOwnership?.clear()
        // Geometry, reload and lifecycle invalidation also terminate an unclaimed
        // native stream. Edge streams remain with Android; never install exclusions.
        if (cancelNative && finger && !claimed && !edgeStream) {
            val now = android.os.SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, startX, startY, 0)
            web?.onTouchEvent(cancel)
            cancel.recycle()
            draining = true
        }
        draining = draining || claimed
        pull?.cancel(); finger = false; claimed = false; onCue(null)
    }
    fun load(html: String, zoom: Int, background: Int = 0xfffafbf8.toInt()) {
        if (retired) return
        val view = web ?: return
        if (signature == (html to zoom)) return
        capture()
        cancelGesture(); onChrome(true)
        work.removeCallbacksAndMessages(null)
        (view as ReaderWebView).cancelPendingDocument()
        signature = html to zoom
        documentReady = false
        draining = true
        target = store.position(id)
        token = guard.begin(id)
        val generation = token
        view.finishSelection()
        view.stopLoading()
        snapshot?.cancel(); snapshot = null
        snapshotSerial++
        blocks = emptyList()
        userInteracted = false
        // Guard tokens are local to a controller (every new page starts at 1).
        // The native document URL must also differ across replacement controllers;
        // it contains no article identity and never grants network access.
        val document = documents.incrementAndGet()
        view.coverDocument(this, document, background)
        val url = "https://www.newsblur.com/fluxa-reader/$document/"
        view.settings.textZoom = zoom
        var pageFinishedDiagnostics = 0
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (!retired && documentReady && web === view && (view as ReaderWebView).owns(this@ReaderPage) &&
                    guard.accepts(id, generation) && request.isForMainFrame && request.hasGesture() &&
                    uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) {
                    runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }
                return true
            }
            override fun onPageFinished(view: WebView, finishedUrl: String?) {
                if (com.fluxa.app.BuildConfig.DEBUG && pageFinishedDiagnostics++ < 4) {
                    val accepted = guard.accepts(id, generation)
                    android.util.Log.d("ReaderNativeLifecycle",
                        "page-finished view=${System.identityHashCode(view)} document=$document generation=$generation finishedUrlHash=${finishedUrl?.let { com.fluxa.app.data.directrss.stableHash(it) }} expectedBaseUrlHash=${com.fluxa.app.data.directrss.stableHash(url)} matchesBase=${finishedUrl == url} guardAccepts=$accepted alreadySettling=${settlingGeneration == generation} guardBranch=${if (finishedUrl != url || !accepted || settlingGeneration == generation) "return" else "visual-callback"} retired=$retired current=${web === view} attached=${view.isAttachedToWindow}")
                }
                if (retired || web !== view || finishedUrl != url || !guard.accepts(id, generation) || settlingGeneration == generation) return
                settlingGeneration = generation
                view.postVisualStateCallback(generation, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (retired || web !== view || !guard.accepts(id, generation)) return
                        if (!(view as ReaderWebView).documentCommitted(this@ReaderPage, document)) return
                        documentReady = true
                        // Publish the committed host subtree, without moving input or AX
                        // focus. A real input-focus gain did not repair replacement publication.
                        val accessibility = view.context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
                            as android.view.accessibility.AccessibilityManager
                        if (accessibility.isEnabled && accessibility.isTouchExplorationEnabled &&
                            view.isAttachedToWindow && view.isShown && view.hasWindowFocus()) {
                            val event = android.view.accessibility.AccessibilityEvent.obtain(
                                android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                            event.contentChangeTypes = android.view.accessibility.AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
                            event.setSource(view)
                            if (com.fluxa.app.BuildConfig.DEBUG) android.util.Log.d("ReaderNativeLifecycle",
                                "manual-content-event view=${System.identityHashCode(view)} document=$document generation=$generation uptime=${android.os.SystemClock.uptimeMillis()} eventType=${event.eventType} contentChangeTypes=${event.contentChangeTypes}")
                            view.sendAccessibilityEventUnchecked(event)
                        }
                        settle(view, generation, -1, 0, 0)
                    }
                })
            }
        }
        (view as ReaderWebView).loadAfterAttachment {
            if (!retired && web === view && guard.accepts(id, generation)) {
                if (com.fluxa.app.BuildConfig.DEBUG) android.util.Log.d("ReaderNativeLifecycle",
                    "view=${System.identityHashCode(view)} document=$document generation=$generation load attached=${view.isAttachedToWindow}")
                // Base URL identifies resource resolution, not the history entry. A null
                // historyUrl makes every replacement about:blank. Give the native
                // navigation the same non-sensitive per-load identity as its callbacks.
                // This is still an inline offline load, not a request to this URL.
                view.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
                // Bound providers that never deliver page/visual completion, starting
                // only once the attached document actually loads (not while queued).
                post(view, 5000) {
                    if (guard.accepts(id, generation) && !guard.canWrite(id, generation))
                        finishRestore(view, generation)
                }
            }
        }
    }
    private fun settle(view: WebView, generation: Long, previousRange: Int, stable: Int, attempt: Int) {
        post(view, 80) {
            if (!guard.accepts(id, generation)) return@post
            val current = range(view)
            val count = if (current == previousRange) stable + 1 else 0
            if ((view.height > 0 && view.contentHeight > 0 && count >= 2) || attempt >= 20) {
                refresh(view, generation, 0)
            } else settle(view, generation, current, count, attempt + 1)
        }
    }
    private fun finishRestore(view: WebView, generation: Long) {
        // A reused host can still expose the outgoing document's geometry until commit.
        // A watchdog must never save that geometry under the incoming article's key.
        if (web !== view || !documentReady || !guard.accepts(id, generation)) return
        if (assistBlocked()) invalidateSnapshot()
        // The restore watchdog is not a visual commit; it must not reveal old pixels.
        if (!userInteracted) {
            scrollOwnership?.clear()
            programmaticScroll = true
            try { view.scrollTo(0, NativeAnchors.resolve(target, blocks, range(view))) }
            finally { programmaticScroll = false }
        }
        guard.rendered(id, generation)
        capture()
    }
    private fun refresh(view: WebView, generation: Long, pass: Int) {
        if (retired || web !== view || !guard.accepts(id, generation)) return
        // Assist snapshots are optional layout sampling, not the native AX tree.
        // Avoid concurrent assist/native screen-reader engines on affected WebViews.
        // Use public state only; no provider flags, service identity or focus changes.
        if (assistBlocked()) {
            if (com.fluxa.app.BuildConfig.DEBUG) android.util.Log.d("ReaderNativeLifecycle",
                "assist-layout skipped view=${System.identityHashCode(view)} generation=$generation pass=$pass touchExploration=true")
            invalidateSnapshot()
            finishRestore(view, generation)
            return
        }
        val serial = ++snapshotSerial
        snapshot?.cancel()
        var completed = false
        fun complete(result: NativeDocumentSnapshot?) {
            if (completed || web !== view || !guard.accepts(id, generation) || serial != snapshotSerial) return
            completed = true
            if (assistBlocked()) {
                invalidateSnapshot()
                finishRestore(view, generation)
                return
            }
            snapshot?.cancel(); snapshot = null
            // A timed-out sample must not leave stale geometry active after reflow.
            blocks = result?.blocks().orEmpty()
            // Refresh geometry after late image reflow, but never fight a real gesture.
            finishRestore(view, generation)
            if (pass < 3) post(view, 1000) {
                if (serial == snapshotSerial) refresh(view, generation, pass + 1)
            }
        }
        val sink = NativeDocumentSnapshot { result -> post(view) { complete(result) } }
        snapshot = sink
        post(view, 700) { complete(null) }
        runCatching { view.onProvideVirtualStructure(sink) }.onFailure { complete(null) }
    }
    /** Capture once before navigation; old composition may survive several input frames. */
    fun retire(): Boolean {
        if (retired) return false
        capture()
        retired = true
        accessibility?.removeAccessibilityStateChangeListener(accessibilityListener)
        accessibility?.removeTouchExplorationStateChangeListener(explorationListener)
        accessibility = null
        cancelGesture()
        guard.begin("")
        work.removeCallbacksAndMessages(null)
        snapshotSerial++; snapshot?.cancel(); snapshot = null
        (web as? ReaderWebView)?.takeIf { it.owns(this) }?.let {
            it.coverRetiredDocument(this)
            it.cancelPendingDocument()
            it.finishSelection()
            it.stopLoading()
            it.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
        }
        return true
    }
    fun release() {
        retire()
        (web as? ReaderWebView)?.takeIf { it.owns(this) }?.let {
            it.setOnScrollChangeListener(null); it.setOnTouchListener(null); it.setOnLongClickListener(null)
            it.forget(this)
        }
        web = null
        cancelTaps = null
        onImage = {}; onTurn = { false }; onCue = {}; onChrome = {}
        blocks = emptyList()
    }
}
