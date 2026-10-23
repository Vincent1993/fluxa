package com.fluxa.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxa.app.data.local.*
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.article.ArticleContent
import com.fluxa.app.ui.article.ReaderImageResource
import com.fluxa.app.ui.article.NativeDocumentSnapshot
import com.fluxa.app.ui.article.ZoomImageView
import com.fluxa.app.ui.theme.FluxaTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Actual production native reader and decoder, synthetic resource only. Root owns screenshot/pixel acceptance. */
class ReaderMediaStage1cTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun embeddedImageTapLongPressPinchPanCloseAndNestedHorizontalStreamsKeepReaderPosition() {
        val owner = "fluxa-stage1c-owned-api36"
        assumeTrue(InstrumentationRegistry.getArguments().getString("stage1cOwner") == owner)
        check(Build.VERSION.SDK_INT == 36)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(File(context.filesDir, "$owner.marker").readText().trim() == owner)
        val name = "$owner-media-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owned = object : ContextWrapper(context) {
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences = super.getSharedPreferences(name, mode)
        }
        val reader = ReaderPreferences(owned, scope)
        runBlocking { reader.ready.first { it } }
        val bitmap = Bitmap.createBitmap(120, 2400, Bitmap.Config.ARGB_8888)
        for (y in 0 until 2400) for (x in 0 until 120) bitmap.setPixel(x, y,
            android.graphics.Color.rgb(x * 2, y % 256, (y / 10) % 256))
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val source = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
        val html = "<p>Owned media body paragraph.</p><img alt='Owned gradient long image' src='$source'>" +
            "<pre><code>" + "wide_code_column ".repeat(60) + "</code></pre>" +
            "<table><tr>" + (0..30).joinToString("") { "<td>Owned column $it</td>" } + "</tr></table>" +
            (0..20).joinToString("") { "<p>Owned tail paragraph $it.</p>" }
        val article = Article("direct-rss:stage1c-media", "Owned media", "Owned source", Instant.EPOCH, false, false, html)
        var turns = 0
        try {
            // FIRST discriminator: run the production bytes/bounds/decoder on the exact
            // Android-generated long PNG before any tap. Never feed this result to UI.
            val extracted = checkNotNull(ReaderImageResource.bytes(source))
            assertArrayEquals("Exact embedded PNG bytes, not a mock", bytes, extracted)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(extracted, 0, extracted.size, bounds)
            android.util.Log.i("Stage1cMedia", "pre-tap sourceLength=${source.length} sourceHash=${com.fluxa.app.data.directrss.stableHash(source)} bytes=${extracted.size} bounds=${bounds.outWidth}x${bounds.outHeight}")
            assertEquals(120, bounds.outWidth); assertEquals(2400, bounds.outHeight)
            val diagnosticBitmap = checkNotNull(ReaderImageResource.decode(source) {
                android.util.Log.i("Stage1cMedia", "pre-tap decode $it")
            }) { "Exact 120x2400 fixture must decode on actual Android before viewer acceptance" }
            try {
                assertEquals(120, diagnosticBitmap.width); assertEquals(2400, diagnosticBitmap.height)
                assertFalse(diagnosticBitmap.isRecycled)
            } finally { diagnosticBitmap.recycle() } // Only our diagnostic, never the viewer's bitmap.
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.awaitStableHost()
                lateinit var web: WebView
                scenario.onActivity { activity -> activity.setContent { FluxaTheme {
                    ArticleContent(article, ReaderSettings(), reader, Modifier.fillMaxSize(), true, true, {}, {}, { turns++; true }, {})
                } } }
                compose.waitUntil(10_000) {
                    var ready = false
                    scenario.onActivity { activity ->
                        views(activity.window.decorView).filterIsInstance<WebView>().singleOrNull()?.let {
                            web = it; ready = it.progress == 100 && it.contentHeight > 0
                        }
                    }; ready
                }
                val visual = AtomicBoolean()
                scenario.onActivity { web.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { visual.set(true) }
                }) }
                compose.waitUntil(5_000) { visual.get() }
                fun geometry(): List<NativeDocumentSnapshot.Entry> {
                    val complete = AtomicBoolean()
                    val snapshot = NativeDocumentSnapshot { complete.set(true) }
                    try {
                        scenario.onActivity { web.onProvideVirtualStructure(snapshot) }
                        // Same former 10s geometry bound, but yield/advance Compose instead
                        // of blocking external provider/draw work in the probe's latch.
                        compose.waitUntil(10_000) { complete.get() }
                        var entries = emptyList<NativeDocumentSnapshot.Entry>()
                        scenario.onActivity { entries = snapshot.flatten() }
                        return entries
                    } finally { scenario.onActivity { snapshot.cancel() } }
                }
                fun bounds(tag: String): Rect {
                    val entry = geometry().firstOrNull { it.html?.tag == tag }
                    return Rect(checkNotNull(entry) { "Real $tag provider geometry missing" }.bounds)
                }
                assertTrue("Actual owned body via assist for media geometry only; NOT AX evidence",
                    geometry().any { it.text == "Owned media body paragraph." })
                val image = bounds("img")
                assertTrue(image.height() > image.width())
                fun stableY(): Int {
                    var y = -1; var previous = -2; var since = android.os.SystemClock.uptimeMillis()
                    compose.waitUntil(5_000) {
                        scenario.onActivity { y = web.scrollY }
                        if (y != previous) since = android.os.SystemClock.uptimeMillis()
                        previous = y
                        android.os.SystemClock.uptimeMillis() - since >= 750
                    }; return y
                }
                scenario.onActivity { web.scrollTo(0, image.top) }
                val original = stableY()
                val content = compose.onNodeWithTag("reader-content")
                fun imageScreenTouch(): Offset {
                    val screen = Rect()
                    scenario.onActivity {
                        val location = IntArray(2).also(web::getLocationOnScreen)
                        screen.set(image)
                        screen.offset(location[0] - web.scrollX, location[1] - web.scrollY)
                        val viewport = Rect()
                        assertTrue(web.getGlobalVisibleRect(viewport))
                        assertTrue("Actual image intersects native viewport", screen.intersect(viewport))
                    }
                    val target = Offset(screen.exactCenterX(), screen.exactCenterY())
                    assertTrue("Touch strictly inside visible image", screen.contains(target.x.toInt(), target.y.toInt()))
                    return target
                }
                fun imageTouch(): Offset {
                    val target = imageScreenTouch()
                    val node = content.fetchSemanticsNode().boundsInRoot
                    val origin = IntArray(2)
                    scenario.onActivity { activity ->
                        views(activity.window.decorView).first { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }.getLocationOnScreen(origin)
                    }
                    return target - Offset(origin[0].toFloat(), origin[1].toFloat()) - node.topLeft
                }
                fun frame(label: String) {
                    val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                    val file = File(context.filesDir, "$name-$label.png")
                    try { file.outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                        android.util.Log.i("Stage1cMedia", "raw-frame label=$label file=${file.name}")
                    } finally { bitmap.recycle() }
                }
                frame("beforetap")
                android.util.Log.i("Stage1cMedia", "before-tap composeClock=${compose.mainClock.currentTime} autoAdvance=${compose.mainClock.autoAdvance}")
                val target = imageTouch()
                try {
                    content.performTouchInput { click(target) }
                    // Renderer hit information is asynchronous after real native UP. Wait for
                    // the exact native source, never substitute DOM/alt/coordinates for a hit.
                    var actualType = -1; var actualSource: String? = null
                    compose.waitUntil(5_000) {
                        scenario.onActivity { actualType = web.hitTestResult.type; actualSource = web.hitTestResult.extra }
                        actualType == WebView.HitTestResult.IMAGE_TYPE && actualSource == source
                    }
                    android.util.Log.i("Stage1cMedia", "actual-touch type=$actualType sourceLength=${actualSource?.length} sourceHash=${actualSource?.let { com.fluxa.app.data.directrss.stableHash(it) }}")
                    assertEquals(WebView.HitTestResult.IMAGE_TYPE, actualType)
                    assertEquals(source.length, actualSource?.length)
                    assertEquals(com.fluxa.app.data.directrss.stableHash(source), actualSource?.let { com.fluxa.app.data.directrss.stableHash(it) })
                    // External decode/draw work: waitUntil yields to Android and advances
                    // Compose frames (including Dialog); never block with a latch or inflate 5s.
                    // https://developer.android.com/develop/ui/compose/testing/synchronization
                    compose.waitUntil(5_000) { compose.onAllNodesWithTag("reader-image-viewer").fetchSemanticsNodes().isNotEmpty() }
                } catch (failure: Throwable) {
                    val loading = compose.onAllNodesWithTag("reader-image-loading").fetchSemanticsNodes().isNotEmpty()
                    val failed = compose.onAllNodesWithTag("reader-image-failed").fetchSemanticsNodes().isNotEmpty()
                    android.util.Log.e("Stage1cMedia", "viewer-not-ready loading=$loading failed=$failed composeClock=${compose.mainClock.currentTime} autoAdvance=${compose.mainClock.autoAdvance}; correlate ReaderImageLoad delivery/snapshot/dialog-side-effect lifecycle")
                    frame("onfailure"); throw failure
                }
                compose.onNodeWithContentDescription("关闭图片").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                val viewer = compose.onNodeWithTag("reader-image-viewer")
                fun transform(): Triple<Float, Float, Float> {
                    var result: Triple<Float, Float, Float>? = null
                    androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(ZoomImageView::class.java))
                        .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                        .check { view, failure ->
                            if (failure != null) throw failure
                            val imageView = view as ZoomImageView
                            assertEquals("Owned gradient long image", imageView.contentDescription.toString())
                            assertTrue(imageView.stateDescription.toString().contains("${(imageView.zoomFactor * 100).toInt()}%"))
                            result = Triple(imageView.zoomFactor, imageView.panOffsetX, imageView.panOffsetY)
                        }
                    return checkNotNull(result)
                }
                for (label in listOf("放大图片", "缩小图片", "重置图片为适合宽度", "向左查看图片", "向右查看图片", "向上查看图片", "向下查看图片")) {
                    compose.onNodeWithContentDescription(label).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                }
                compose.onNodeWithContentDescription("缩小图片").assertIsNotEnabled()
                compose.onNodeWithContentDescription("重置图片为适合宽度").assertIsNotEnabled()
                compose.onNodeWithContentDescription("向上查看图片").assertIsNotEnabled()
                compose.onNodeWithContentDescription("向左查看图片").assertIsNotEnabled()
                compose.onNodeWithContentDescription("向右查看图片").assertIsNotEnabled()
                val fitted = transform()
                assertEquals(1f, fitted.first, .001f); assertEquals(0f, fitted.third, .5f)
                frame("viewer-fit")
                compose.onNodeWithContentDescription("向下查看图片").assertIsEnabled().performClick()
                assertTrue("Down button must actually move the image", transform().third < fitted.third)
                compose.onNodeWithContentDescription("向上查看图片").assertIsEnabled().performClick()
                assertEquals(fitted.third, transform().third, .5f)
                compose.onNodeWithContentDescription("放大图片").performClick()
                assertEquals("Zoom button changes native scale", 2f, transform().first, .001f)
                frame("viewer-zoom2")
                val centered = transform()
                compose.onNodeWithContentDescription("向右查看图片").assertIsEnabled().performClick()
                val right = transform()
                assertTrue("Right button changes native X", right.second < centered.second)
                compose.onNodeWithContentDescription("向右查看图片").assertIsNotEnabled()
                compose.onNodeWithContentDescription("向左查看图片").assertIsEnabled().performClick()
                assertTrue("Left button changes native X", transform().second > right.second)
                repeat(2) { compose.onNodeWithContentDescription("放大图片").performClick() }
                assertEquals(8f, transform().first, .001f)
                compose.onNodeWithContentDescription("放大图片").assertIsNotEnabled()
                compose.onNodeWithContentDescription("缩小图片").assertIsEnabled().performClick()
                assertEquals(4f, transform().first, .001f)
                compose.onNodeWithContentDescription("重置图片为适合宽度").assertIsEnabled().performClick()
                val reset = transform()
                assertEquals(fitted.first, reset.first, .001f)
                assertEquals(fitted.second, reset.second, .5f); assertEquals(fitted.third, reset.third, .5f)
                compose.onNodeWithContentDescription("重置图片为适合宽度").assertIsNotEnabled()
                // Reach the long-image bottom with viewport-sized button steps, then verify the bound.
                for (step in 0 until 256) {
                    val down = compose.onNodeWithContentDescription("向下查看图片")
                    if (down.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)) break
                    down.performClick()
                }
                compose.onNodeWithContentDescription("向下查看图片").assertIsNotEnabled()
                assertTrue(transform().third < 0f)
                frame("viewer-bottom")
                compose.onNodeWithContentDescription("重置图片为适合宽度").performClick()
                viewer.performTouchInput {
                    down(0, Offset(width * .4f, height * .45f)); down(1, Offset(width * .6f, height * .55f))
                    moveTo(0, Offset(width * .2f, height * .3f), 400)
                    moveTo(1, Offset(width * .8f, height * .7f), 400)
                    up(1); up(0)
                }
                // The Compose AndroidView owns the real ZoomImageView, not a screenshot placeholder.
                var zoom = 0f
                viewer.performTouchInput { swipe(Offset(width / 2f, height * .7f), Offset(width / 2f, height * .3f), 400) }
                // Espresso inspects the actual native view in the dialog's separate window.
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(ZoomImageView::class.java))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check { view, failure ->
                        if (failure != null) throw failure
                        val imageView = view as ZoomImageView
                        zoom = imageView.zoomFactor
                        assertTrue("Long image must pan", imageView.panOffsetY < 0f)
                    }
                assertTrue("Native pinch must increase actual scale", zoom > 1f)
                frame("viewer-pinch-pan")
                compose.onNodeWithContentDescription("关闭图片").performClick()
                assertEquals(original, stableY())
                val longTarget = imageTouch()
                // A drag returning to its origin is not a tap; UP must not open a
                // delayed viewer from a stale/native earlier hit.
                content.performTouchInput {
                    down(longTarget)
                    moveTo(longTarget + Offset(80f, 0f), 60)
                    moveTo(longTarget, 60)
                    up()
                }
                compose.waitForIdle()
                compose.onNodeWithTag("reader-image-viewer").assertDoesNotExist()
                assertEquals(0, turns)
                try {
                    // Compose input batches can advance virtual time without holding Chromium's
                    // wall-clock timer. Discriminate with public Android input, not performLongClick.
                    // AndroidX main-branch dispatcher explains the mechanism, not exact 1.12.1 proof.
                    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                    val holdTarget = imageScreenTouch() // Recompute after the retract stream.
                    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                    val holdMillis = android.view.ViewConfiguration.getLongPressTimeout().toLong() + 100L
                    val downTime = android.os.SystemClock.uptimeMillis()
                    fun inject(action: Int): Boolean {
                        val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(),
                            action, holdTarget.x, holdTarget.y, 0).apply {
                            this.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        }
                        return try { automation.injectInputEvent(event, true) } finally { event.recycle() }
                    }
                    var actualType = -1
                    var actualSource: String? = null
                    try {
                        assertTrue("Actual touchscreen DOWN injected", inject(android.view.MotionEvent.ACTION_DOWN))
                        val acceptedAt = android.os.SystemClock.uptimeMillis()
                        // Real uptime sleep on instrumentation thread ONLY; not an acceptance wait.
                        android.os.SystemClock.sleep(holdMillis)
                        val elapsed = android.os.SystemClock.uptimeMillis() - acceptedAt
                        scenario.onActivity { actualType = web.hitTestResult.type; actualSource = web.hitTestResult.extra }
                        android.util.Log.i("Stage1cMedia", "real-hold requestedMs=$holdMillis elapsedMs=$elapsed downAgeMs=${android.os.SystemClock.uptimeMillis() - downTime} type=$actualType sameNativeImageHit=${actualType == WebView.HitTestResult.IMAGE_TYPE && actualSource == source} sourceLength=${actualSource?.length} sourceHash=${actualSource?.let { com.fluxa.app.data.directrss.stableHash(it) }}")
                        assertTrue("Actual uptime hold reached Android long-press threshold", elapsed >= holdMillis)
                        assertEquals(WebView.HitTestResult.IMAGE_TYPE, actualType)
                        assertTrue("Held native hit must equal exact embedded source", source == actualSource)
                        assertEquals(source.length, actualSource?.length)
                        assertEquals(com.fluxa.app.data.directrss.stableHash(source), actualSource?.let { com.fluxa.app.data.directrss.stableHash(it) })
                    } finally {
                        // Even a rejected DOWN or failed hit assertion must release native input.
                        assertTrue("Actual touchscreen UP injected", inject(android.view.MotionEvent.ACTION_UP))
                    }
                    // Same single 5s deadline; close control/loading alone is insufficient.
                    compose.waitUntil(5_000) {
                        compose.onAllNodesWithContentDescription("关闭图片").fetchSemanticsNodes().isNotEmpty() &&
                            compose.onAllNodesWithTag("reader-image-viewer").fetchSemanticsNodes().isNotEmpty()
                    }
                    val heldFit = transform()
                    assertEquals(fitted.first, heldFit.first, .001f)
                    assertEquals(fitted.second, heldFit.second, .5f)
                    assertEquals(fitted.third, heldFit.third, .5f)
                    frame("viewer-real-longpress")
                } catch (failure: Throwable) {
                    android.util.Log.e("Stage1cMedia", "real-longpress-failure cause=${failure.javaClass.name}; correlate ReaderNativeLifecycle long-click and ReaderImageLoad")
                    frame("longpress-failure")
                    throw failure
                }
                compose.onNodeWithContentDescription("关闭图片").performClick()
                assertEquals(original, stableY())
                for (tag in listOf("pre", "table")) {
                    scenario.onActivity { web.scrollTo(0, 0) }; stableY()
                    val rectangle = bounds(tag)
                    scenario.onActivity { web.scrollTo(0, rectangle.top - 150) }; stableY()
                    content.performTouchInput { swipe(Offset(width * .8f, 170f), Offset(width * .2f, 170f), 500) }
                    assertEquals("$tag horizontal stream must not turn an article", 0, turns)
                }
                scenario.onActivity {
                    assertTrue("Reader cannot bypass image policy with remote requests", web.settings.blockNetworkLoads)
                    assertFalse(web.settings.javaScriptEnabled); assertFalse(web.settings.allowFileAccess)
                    assertFalse(web.settings.allowContentAccess)
                }
                assertEquals(0, turns)
            }
        } finally {
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            context.deleteSharedPreferences(name)
        }
    }
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
}
