package com.fluxa.app.ui.article

import android.os.Build
import android.os.CancellationSignal
import android.view.translation.*
import androidx.annotation.RequiresApi
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
import kotlinx.coroutines.*
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Late providers are destroyed, never installed into a canceled reader. */
@RequiresApi(31)
private suspend fun createTranslator(manager: TranslationManager, capability: TranslationCapability): Translator? =
    suspendCancellableCoroutine { continuation ->
        manager.createOnDeviceTranslator(TranslationContext.Builder(capability.sourceSpec, capability.targetSpec).build(),
            Executor { it.run() }) { translator ->
            if (continuation.isActive) continuation.resume(translator) { _, value, _ -> value?.destroy() }
            else translator?.destroy()
        }
    }

@RequiresApi(31)
private suspend fun translateChunk(translator: Translator, text: String): String = suspendCancellableCoroutine { continuation ->
    val signal = CancellationSignal()
    val gate = TranslationCallbackGate()
    continuation.invokeOnCancellation { gate.cancel(); signal.cancel() }
    val request = TranslationRequest.Builder().setTranslationRequestValues(listOf(TranslationRequestValue.forText(text))).build()
    translator.translate(request, signal, Executor { it.run() }) { response ->
        if (!gate.claim() || !continuation.isActive) return@translate
        val value = response.translationResponseValues[0]
        if (response.translationStatus == TranslationResponse.TRANSLATION_STATUS_SUCCESS &&
            value?.statusCode == TranslationResponseValue.STATUS_SUCCESS && value.text != null && value.text!!.length <= 16_000) {
            continuation.resume(value.text.toString())
        } else continuation.resumeWith(Result.failure(IllegalStateException("该段翻译失败；原文未改变")))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderTranslationSheet(html: String, onClose: () -> Unit) {
    if (Build.VERSION.SDK_INT < 31) {
        AlertDialog(onDismissRequest = onClose, title = { Text("端侧翻译不可用") },
            text = { Text("需要 Android 12 或更高版本及已安装的翻译服务与模型。") },
            confirmButton = { IconAction("关闭翻译", Icons.Outlined.Close, onClose) })
    } else NativeTranslationSheet(html, onClose)
}

@RequiresApi(31)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeTranslationSheet(html: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(TranslationManager::class.java) }
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    var pairs by remember { mutableStateOf(emptyList<TranslationCapability>()) }
    var selected by remember { mutableStateOf<TranslationCapability?>(null) }
    var status by remember { mutableStateOf("正在检查已安装的端侧翻译模型") }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableLongStateOf(0L) }
    val progress = remember(html) { TranslationProgress() }
    var translator by remember { mutableStateOf<Translator?>(null) }
    fun cancel() { generation++; progress.cancel(); job?.cancel(); runCatching { translator?.destroy() }; translator = null; busy = false }
    DisposableEffect(owner, html) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) { cancel(); onClose() } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); cancel() }
    }
    LaunchedEffect(manager) {
        try {
            // Platform discovery can block for seconds. This is never done on main.
            pairs = withTimeout(10_000) { withContext(Dispatchers.IO) {
                manager?.getOnDeviceTranslationCapabilities(TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT)
                    .orEmpty().filter { it.state == TranslationCapability.STATE_ON_DEVICE }.take(100)
            } }
            selected = pairs.firstOrNull()
            status = if (pairs.isEmpty()) "设备没有报告已安装且可用的端侧翻译语言对；不会自动下载模型" else "选择设备支持的源语言 → 目标语言"
        } catch (_: TimeoutCancellationException) { status = "设备翻译能力查询超时" }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { status = "设备翻译服务不可用" }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text("端侧翻译", style = MaterialTheme.typography.titleLarge)
            Text(status)
            pairs.forEach { pair ->
                val label = "${pair.sourceSpec.locale.displayName} → ${pair.targetSpec.locale.displayName}"
                Row { Text(label, Modifier.weight(1f))
                    IconAction("选择 $label", Icons.Outlined.Check, { cancel(); selected = pair; output = "" }, selected = selected == pair) }
            }
            IconAction("翻译已保存正文", Icons.Outlined.Translate, {
                val pair = selected
                val service = manager
                if (pair != null && service != null) {
                    cancel(); val ticket = generation; val progressTicket = progress.begin(); busy = true; output = ""; status = "正在翻译"
                    job = scope.launch {
                        var owned: Translator? = null
                        try {
                            owned = withTimeout(10_000) { createTranslator(service, pair) }
                            val active = owned ?: error("服务无法创建翻译器")
                            if (ticket != generation) return@launch
                            translator = active
                            val chunks = withContext(Dispatchers.Default) { speechChunks(html, 2000) }
                            require(chunks.isNotEmpty()) { "没有可翻译的正文" }
                            for ((index, text) in chunks.withIndex()) {
                                val result = withTimeout(15_000) { translateChunk(active, text) }
                                ensureActive()
                                if (ticket != generation) return@launch
                                if (!progress.append(progressTicket, result)) return@launch
                                output = progress.output
                                status = "已翻译 ${index + 1} / ${chunks.size} 段（原文未改变）"
                            }
                            if (ticket == generation && progress.finish(progressTicket)) status = "翻译完成（原文未改变）"
                        } catch (_: TimeoutCancellationException) {
                            if (ticket == generation) status = "翻译服务超时；已完成部分保留，原文未改变"
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { if (ticket == generation) status = "翻译不可用或部分失败；下方仅为成功部分，原文未改变" }
                        finally {
                            progress.finish(progressTicket)
                            runCatching { owned?.destroy() }
                            if (ticket == generation) { translator = null; busy = false }
                        }
                    }
                }
            }, enabled = selected != null && !busy)
            if (busy) IconAction("取消翻译", Icons.Outlined.Stop, { cancel(); status = "已取消；原文未改变" })
            if (output.isNotBlank()) androidx.compose.foundation.text.selection.SelectionContainer { Text(output) }
            IconAction("打开设备翻译设置", Icons.Outlined.Settings, {
                runCatching {
                    val intent = manager?.onDeviceTranslationSettingsActivityIntent ?: error("无入口")
                    intent.send()
                }.onFailure { status = "设备未提供翻译设置入口" }
            })
            IconAction("关闭翻译", Icons.Outlined.Close, onClose)
        }
    }
}
