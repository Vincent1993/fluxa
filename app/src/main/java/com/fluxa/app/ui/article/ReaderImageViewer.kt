package com.fluxa.app.ui.article

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fluxa.app.ui.components.IconAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** No viewer network path. Only exact body-embedded raster resources may be decoded. */
internal object ReaderImageResource {
    const val MAX_BYTES = 1024 * 1024
    const val MAX_PIXELS = 32_000_000L
    fun bytes(source: String): ByteArray? {
        if (source.length > MAX_BYTES * 4 / 3 + 128) return null
        val prefix = source.substringBefore(',')
        if (prefix !in setOf("data:image/png;base64", "data:image/jpeg;base64", "data:image/gif;base64", "data:image/webp;base64")) return null
        return runCatching { java.util.Base64.getDecoder().decode(source.substringAfter(',')) }
            .getOrNull()?.takeIf { it.size <= MAX_BYTES }
    }
    fun decode(source: String, diagnostic: ((String) -> Unit)? = null): Bitmap? {
        val bytes = bytes(source) ?: run { diagnostic?.invoke("bytes=rejected"); return null }
        diagnostic?.invoke("bytes=${bytes.size}")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = sampleSize(bounds.outWidth, bounds.outHeight)
        diagnostic?.invoke("bounds=${bounds.outWidth}x${bounds.outHeight} sample=$sample")
        if (sample == null) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            .also { diagnostic?.invoke("decoded=${it != null} dimensions=${it?.width}x${it?.height}") }
    }
    fun sampleSize(width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return null
        var sample = 1
        while (((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample) > 8_000_000 ||
            (width.toLong() + sample - 1) / sample > 16_384 || (height.toLong() + sample - 1) / sample > 16_384) sample *= 2
        return sample
    }
    fun embedded(body: String): Set<String> = org.jsoup.Jsoup.parseBodyFragment(body).select("img[src]")
        .map { it.attr("src") }.filter { it.startsWith("data:image/") }.toSet()
}

private sealed interface ImageLoad {
    data object Loading : ImageLoad
    data object Failed : ImageLoad
    data class Ready(val bitmap: Bitmap) : ImageLoad
}

/** One bounded trace per viewer generation; never log the source/alt/body or exception message. */
private class ImageLoadTrace(source: String) {
    private val id = next.incrementAndGet()
    private val identity = if (com.fluxa.app.BuildConfig.DEBUG)
        "length=${source.length} hash=${com.fluxa.app.data.directrss.stableHash(source)}" else ""
    fun log(event: String) {
        if (com.fluxa.app.BuildConfig.DEBUG) android.util.Log.d("ReaderImageLoad",
            "generation=$id $identity main=${android.os.Looper.myLooper() == android.os.Looper.getMainLooper()} thread=${Thread.currentThread().name} $event")
    }
    companion object { private val next = java.util.concurrent.atomic.AtomicLong() }
}

@Composable
internal fun ReaderImageViewer(source: String, alt: String? = null, onClose: () -> Unit) {
    val loadState = remember(source) { mutableStateOf<ImageLoad>(ImageLoad.Loading) }
    var load by loadState
    val trace = remember(source) { ImageLoadTrace(source) }
    var native by remember(source) { mutableStateOf<ZoomImageView?>(null) }
    var state by remember(source) { mutableStateOf(ImageTransformState()) }
    DisposableEffect(trace) {
        trace.log("composed state=loading")
        // Observe application, not just the decoder's write. This does not force a
        // snapshot notification/frame and is removed with this viewer generation.
        val applied = java.util.concurrent.atomic.AtomicBoolean()
        val observer = if (com.fluxa.app.BuildConfig.DEBUG)
            androidx.compose.runtime.snapshots.Snapshot.registerApplyObserver { changes, _ ->
                if (loadState in changes && loadState.value != ImageLoad.Loading && applied.compareAndSet(false, true))
                    trace.log("load-snapshot-applied state=${if (loadState.value is ImageLoad.Ready) "ready" else "failed"}")
            } else null
        onDispose { observer?.dispose(); trace.log("disposed") }
    }
    LaunchedEffect(source) {
        var decoded: Bitmap? = null
        var delivered = false
        trace.log("effect-start state=loading dispatcher=${coroutineContext[kotlin.coroutines.ContinuationInterceptor]?.javaClass?.name}")
        try {
            // Retain the local result even if withContext's prompt cancellation discards
            // its return value. Only an undelivered bitmap may be explicitly recycled.
            withContext(Dispatchers.Default) { decoded = ReaderImageResource.decode(source, trace::log) }
            // A test-installed effect dispatcher can resume this continuation on
            // the decoder thread. UI delivery must not depend on that dispatcher.
            withContext(Dispatchers.Main.immediate) {
                ensureActive()
                load = decoded?.let { ImageLoad.Ready(it) } ?: ImageLoad.Failed
                delivered = true
                trace.log("delivery state=${if (decoded == null) "failed" else "ready"}")
            }
        } catch (cancelled: CancellationException) {
            trace.log("cancelled delivered=$delivered")
            throw cancelled
        } catch (failure: Exception) {
            ensureActive() // Cancellation must not be presented as an unavailable image.
            withContext(Dispatchers.Main.immediate) {
                ensureActive()
                load = ImageLoad.Failed
                trace.log("delivery state=failed exceptionType=${failure.javaClass.simpleName}")
            }
        } finally {
            if (!delivered) decoded?.recycle()
        }
    }
    // Source-keyed state/effect prevents stale delivery into a replacement generation.
    // A delivered bitmap can still be drawn after disposal; leave its lifetime to GC.
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Surface {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconAction("关闭图片", Icons.Outlined.Close, onClose)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val current = load
                    val phase = when (current) {
                        ImageLoad.Loading -> "loading"
                        ImageLoad.Failed -> "failed"
                        is ImageLoad.Ready -> "ready"
                    }
                    val lastAppliedPhase = remember(trace) { arrayOfNulls<String>(1) }
                    SideEffect {
                        if (lastAppliedPhase[0] != phase) {
                            lastAppliedPhase[0] = phase
                            trace.log("dialog-side-effect state=$phase")
                        }
                    }
                    when (current) {
                        ImageLoad.Loading -> androidx.compose.material3.Text("正在加载图片…",
                            Modifier.align(Alignment.Center).testTag("reader-image-loading"))
                        ImageLoad.Failed -> androidx.compose.material3.Text("图片不可用或超过显示上限",
                            Modifier.align(Alignment.Center).testTag("reader-image-failed"))
                        is ImageLoad.Ready -> key(source) {
                            AndroidView(factory = { context ->
                                ZoomImageView(context, current.bitmap, alt) { state = it }.also {
                                    native = it
                                    trace.log("view-created view=${System.identityHashCode(it)} dimensions=${current.bitmap.width}x${current.bitmap.height}")
                                }
                            }, onRelease = { if (native === it) native = null },
                                modifier = Modifier.fillMaxSize().testTag("reader-image-viewer"))
                        }
                    }
                }
                Surface {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row {
                            IconAction("缩小图片", Icons.Outlined.ZoomOut, { native?.zoomBy(.5f) },
                                enabled = state.zoom > 1f, stateLabel = state.description)
                            IconAction("重置图片为适合宽度", Icons.Outlined.RestartAlt, { native?.reset() },
                                enabled = state.canReset, stateLabel = state.description)
                            IconAction("放大图片", Icons.Outlined.ZoomIn, { native?.zoomBy(2f) },
                                enabled = state.ready && state.zoom < 8f, stateLabel = state.description)
                        }
                        Row {
                            IconAction("向左查看图片", Icons.Outlined.ArrowBack, { native?.panBy(1, 0) }, enabled = state.left)
                            IconAction("向上查看图片", Icons.Outlined.ArrowUpward, { native?.panBy(0, 1) }, enabled = state.up)
                            IconAction("向下查看图片", Icons.Outlined.ArrowDownward, { native?.panBy(0, -1) }, enabled = state.down)
                            IconAction("向右查看图片", Icons.Outlined.ArrowForward, { native?.panBy(-1, 0) }, enabled = state.right)
                        }
                    }
                }
            }
        }
    }
}

internal data class ImageTransformState(
    val ready: Boolean = false, val zoom: Float = 1f, val canReset: Boolean = false,
    val left: Boolean = false, val up: Boolean = false, val down: Boolean = false, val right: Boolean = false
) {
    val description: String get() = "相对适合宽度 ${(zoom * 100).toInt()}%"
}

/** Width-fit initial long image, bounded pinch scale and native one-finger pan. */
internal class ZoomImageView(
    context: Context, private val bitmap: Bitmap, alt: String? = null,
    private val onTransform: (ImageTransformState) -> Unit = {}
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var firstDraw = true
    private var scale = 1f
    private var minimum = 1f
    internal val zoomFactor: Float get() = scale / minimum
    internal val panOffsetY: Float get() = y
    internal val panOffsetX: Float get() = x
    private var x = 0f
    private var y = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var resumePan = false
    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (scale * detector.scaleFactor).coerceIn(minimum, minimum * 8f)
            val ratio = next / scale
            x = detector.focusX - (detector.focusX - x) * ratio
            y = detector.focusY - (detector.focusY - y) * ratio
            scale = next
            constrain(); invalidate()
            return true
        }
    })
    init {
        contentDescription = alt?.trim()?.takeIf { it.isNotEmpty() }?.take(1000) ?: "图片"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setBackgroundColor(Color.BLACK)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        minimum = w.toFloat() / bitmap.width
        reset()
    }
    internal fun reset() {
        scale = minimum; x = 0f; y = 0f; constrain(); invalidate()
    }
    internal fun zoomBy(factor: Float) {
        if (width <= 0 || height <= 0) return
        val next = (scale * factor).coerceIn(minimum, minimum * 8f)
        val ratio = next / scale
        x = width / 2f - (width / 2f - x) * ratio
        y = height / 2f - (height / 2f - y) * ratio
        scale = next; constrain(); invalidate()
    }
    /** Directions move the viewport through the image, in 80% overlapping-page steps. */
    internal fun panBy(horizontal: Int, vertical: Int) {
        x += horizontal * width * .8f; y += vertical * height * .8f
        constrain(); invalidate()
    }
    private fun constrain() {
        fun clamp(value: Float, size: Float, viewport: Int) =
            if (size < viewport) (viewport - size) / 2 else value.coerceIn(viewport - size, 0f)
        x = clamp(x, bitmap.width * scale, width); y = clamp(y, bitmap.height * scale, height)
        val state = ImageTransformState(
            ready = width > 0 && height > 0, zoom = zoomFactor,
            canReset = kotlin.math.abs(zoomFactor - 1f) > .001f ||
                kotlin.math.abs(y - maxOf(0f, (height - bitmap.height * minimum) / 2f)) > .5f,
            left = x < -.5f, up = y < -.5f,
            down = y > height - bitmap.height * scale + .5f,
            right = x > width - bitmap.width * scale + .5f
        )
        if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = state.description
        onTransform(state)
    }
    override fun onDraw(canvas: Canvas) {
        canvas.save(); canvas.translate(x, y); canvas.scale(scale, scale)
        canvas.drawBitmap(bitmap, 0f, 0f, paint); canvas.restore()
        if (firstDraw) {
            firstDraw = false
            if (com.fluxa.app.BuildConfig.DEBUG) android.util.Log.d("ReaderImageLoad",
                "first-draw view=${System.identityHashCode(this)} dimensions=${bitmap.width}x${bitmap.height} viewport=${width}x${height}")
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount == 1 && !detector.isInProgress && !resumePan) {
            x += event.x - lastX; y += event.y - lastY; constrain(); invalidate()
        }
        resumePan = event.actionMasked == MotionEvent.ACTION_POINTER_UP
        lastX = event.x; lastY = event.y
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
