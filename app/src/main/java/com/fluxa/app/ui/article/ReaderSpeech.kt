package com.fluxa.app.ui.article

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fluxa.app.ui.components.IconAction
import org.jsoup.Jsoup

/** Bounded cached prose only, never HTML attributes, script, or a network request. */
internal fun speechChunks(html: String, maximum: Int): List<String> {
    require(maximum >= 2)
    require(html.length <= 2_000_000) { "正文超出朗读上限" }
    val document = Jsoup.parseBodyFragment(html)
    document.select("script,style,noscript,template").remove()
    val text = document.body().text()
    require(text.length <= 400_000) { "正文超出朗读上限" }
    val result = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + maximum - 1, text.length)
        if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
        require(end > start)
        result += text.substring(start, end)
        start = end
    }
    return result
}

/** Completion owns a generation AND chunk. Pausing invalidates already queued callbacks. */
internal class SpeechCursor(val count: Int) {
    var generation = 0L; private set
    var index = 0; private set
    var playing = false; private set
    fun start(): String? {
        generation++
        if (index >= count) index = 0
        playing = count > 0
        return ticket()
    }
    fun pause() { generation++; playing = false }
    fun stop() { pause(); index = 0 }
    fun ticket(): String? = if (playing) "$generation:$index" else null
    fun complete(callbackId: String): String? {
        if (callbackId != ticket()) return null
        index++
        if (index >= count) playing = false
        return ticket()
    }
}

/** Only constructed after an explicit reader action; all state callbacks are main-thread. */
internal class ReaderSpeech(context: Context, html: String, private val changed: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var closed = false
    private val chunks = speechChunks(html, TextToSpeech.getMaxSpeechInputLength())
    val cursor = SpeechCursor(chunks.size)
    var voices: List<Voice> = emptyList(); private set
    var voice: Voice? = null; private set
    var status = "正在检查已安装的朗读引擎"; private set
    private val initTimeout = Runnable {
        if (!closed && voice == null) { status = "朗读引擎未就绪，请关闭后重试"; shutdown(); changed() }
    }
    init {
        handler.postDelayed(initTimeout, 8_000)
        try {
            engine = TextToSpeech(context.applicationContext) { result -> handler.post {
                if (closed) return@post
                handler.removeCallbacks(initTimeout)
                val tts = engine
                if (result != TextToSpeech.SUCCESS || tts == null) {
                    status = "此设备的朗读引擎不可用"; changed(); return@post
                }
                voices = runCatching { tts.voices }.getOrNull().orEmpty().filter { !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                    .sortedBy { it.locale.toLanguageTag() + it.name }.take(100)
                voice = voices.firstOrNull { it.locale.language == java.util.Locale.getDefault().language } ?: voices.firstOrNull()
                status = if (chunks.isEmpty()) "正文没有可朗读的文字" else if (voice == null) "没有可用的已安装端侧声音" else "请选择正文语言对应的声音；不会自动播放"
                changed()
            } }
        } catch (_: Exception) { handler.removeCallbacks(initTimeout); status = "此设备的朗读引擎不可用" }
    }
    fun select(value: Voice) { if (value in voices) { pause(); voice = value; changed() } }
    fun play() {
        if (closed) return
        val tts = engine ?: return
        val chosen = voice ?: return
        if (tts.setVoice(chosen) != TextToSpeech.SUCCESS) { status = "该声音不可用"; changed(); return }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { handler.post {
                if (closed || id != cursor.ticket()) return@post
                val next = cursor.complete(checkNotNull(id))
                if (next != null) speak(next) else { status = "朗读结束"; changed() }
            } }
            @Deprecated("Platform callback") override fun onError(id: String?) { handler.post {
                if (!closed && id == cursor.ticket()) { cursor.pause(); status = "朗读失败，声音或语言不可用"; changed() }
            } }
        })
        cursor.start()?.let(::speak)
    }
    private fun speak(ticket: String) {
        if (closed || ticket != cursor.ticket()) return
        status = "端侧朗读 ${cursor.index + 1} / ${chunks.size}"
        if (engine?.speak(chunks[cursor.index], TextToSpeech.QUEUE_FLUSH, null, ticket) != TextToSpeech.SUCCESS) {
            cursor.pause(); status = "朗读请求失败"
        }
        changed()
    }
    fun pause() { cursor.pause(); engine?.stop(); status = "已暂停；继续将从当前段重新朗读"; changed() }
    fun stop() { cursor.stop(); engine?.stop(); status = "已停止"; changed() }
    fun shutdown() {
        if (closed) return
        closed = true; cursor.stop(); handler.removeCallbacks(initTimeout)
        engine?.stop(); engine?.shutdown(); engine = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSpeechSheet(html: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    var speech by remember { mutableStateOf<ReaderSpeech?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    DisposableEffect(html, owner) {
        val controller = runCatching { ReaderSpeech(context, html) { revision++ } }
            .onFailure { failure = it.message ?: "朗读不可用" }.getOrNull()
        speech = controller
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { controller?.shutdown(); onClose() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); controller?.shutdown() }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text("端侧朗读", style = MaterialTheme.typography.titleLarge)
            @Suppress("UNUSED_VARIABLE") val currentRevision = revision
            Text(failure ?: speech?.status ?: "正在检查朗读引擎")
            speech?.let { controller ->
                controller.voices.forEach { voice ->
                    Row { Text("${voice.locale.getDisplayName()} · ${voice.name}", Modifier.weight(1f))
                        IconAction("选择声音 ${voice.name}", Icons.Outlined.Check, { controller.select(voice) }, selected = controller.voice == voice) }
                }
                Row {
                    IconAction("开始或继续朗读", Icons.Outlined.PlayArrow, controller::play, enabled = controller.voice != null && controller.cursor.count > 0 && !controller.cursor.playing)
                    IconAction("暂停朗读", Icons.Outlined.Pause, controller::pause, enabled = controller.cursor.playing)
                    IconAction("停止朗读", Icons.Outlined.Stop, controller::stop)
                }
            }
            IconAction("打开系统朗读设置", Icons.Outlined.Settings, {
                runCatching { context.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS")) }
                    .onFailure { failure = "设备未提供朗读设置入口" }
            })
            IconAction("关闭朗读", Icons.Outlined.Close, onClose)
        }
    }
}
