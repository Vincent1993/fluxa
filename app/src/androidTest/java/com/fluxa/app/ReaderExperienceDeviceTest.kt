package com.fluxa.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Looper
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxa.app.data.local.*
import com.fluxa.app.ui.article.NativeDocumentSnapshot
import com.fluxa.app.ui.article.NativeAnchors
import com.fluxa.app.ui.article.NativeBlock
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Run seed / force-stop / verify / force-stop / cleanup as SEPARATE instrumentation processes.
 * No phase: skipped, so ordinary connected tests do not leave fixtures or alter reader preferences.
 * Original prose/image below are test-only, never compiled into the production APK.
 */
class ReaderExperienceDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val backup get() = File(context.filesDir, "reader-experience-original.json")
    private val evidence get() = File(context.filesDir, "reader-experience-progress.json")
    private val prefs get() = context.getSharedPreferences("reader_private", Context.MODE_PRIVATE)
    private val marker = "FLUXA-READER-RESERVED"
    private val count = 54
    private fun id(i: Int) = "999981:reader-experience-${i.toString().padStart(2, '0')}"
    private fun feed(i: Int) = "${999981 + i % 3}"
    private fun title(i: Int) = "$marker ${i.toString().padStart(2, '0')} 中文与 English：在离线旅途中慢慢阅读，保存每一个段落 & <notes> — a deliberately long original title about rivers, libraries and everyday observations"
    private fun paragraph(i: Int, p: Int) = "READER-$i-P${p.toString().padStart(3, '0')} 河边的图书馆今天依然安静，读者沿着文字寻找昨天的足迹。 The quiet library beside the river keeps a place for every reader, even when the network is unavailable."
    private val inlineImage by lazy {
        val bitmap = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(android.graphics.Color.rgb(222, 237, 228))
        paint.color = android.graphics.Color.rgb(35, 112, 150)
        canvas.drawRect(0f, 210f, 640f, 320f, paint)
        paint.color = android.graphics.Color.rgb(190, 90, 45)
        canvas.drawRect(60f, 85f, 580f, 210f, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 30f
        canvas.drawText("OFFLINE RIVER LIBRARY", 105f, 145f, paint)
        java.io.ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            bitmap.recycle()
            android.util.Base64.encodeToString(it.toByteArray(), android.util.Base64.NO_WRAP)
        }
    }
    private fun html(i: Int) = buildString {
        append("<p>${paragraph(i, 0)}</p>")
        // Original Canvas artwork; no remote image or licensed asset.
        append("<img alt='Original river library illustration' width='640' height='320' src='data:image/png;base64,$inlineImage'>")
        append("<blockquote>慢读不是停下脚步。 Reading slowly is still moving forward.</blockquote>")
        append("<pre><code>val message = &quot;offline reader&quot;\nrepeat(3) { println(message) }</code></pre>")
        append("<table><tr><th>地点 / Place</th><th>观察 / Observation</th></tr><tr><td>河岸</td><td>A long table cell with original bilingual observations.</td></tr></table>")
        for (p in 1..100) append("<p>${paragraph(i, p)}</p>")
    }

    /** Diagnostic completion is NOT reader acceptance; absent DOM paragraphs are logged. */
    @Test fun standaloneProviderProbe() {
        assumeTrue("Explicit standaloneProviderProbe=true required",
            InstrumentationRegistry.getArguments().getString("standaloneProviderProbe") == "true")
        // Nothing above this guard accesses the application, preferences or database.
        check(!backup.exists() && !evidence.exists()) { "Active fixture ownership; cleanup separately first" }
        // Require an already initialized signed-out installation; do not initialize auth here.
        check(File(context.applicationInfo.dataDir, "shared_prefs/fluxa_auth.xml").isFile) {
            "Initialize and sign out this dedicated emulator before running the probe"
        }
        val tokens = SecureTokenStore(context)
        check(tokens.getNewsBlurSession() == null && tokens.getAccessToken().isNullOrBlank()) {
            "Dedicated signed-out emulator required"
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val keepServices = InstrumentationRegistry.getArguments().getString("standaloneKeepServices") == "true"
        val requestedFlags = if (keepServices) android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES else 0
        val automation = if (keepServices) instrumentation.getUiAutomation(requestedFlags) else instrumentation.uiAutomation
        Log.i("FluxaReaderProvider", "standalone keepServices=$keepServices requestedFlags=$requestedFlags")
        val originalInfo = checkNotNull(automation.serviceInfo)
        val flags = originalInfo.flags
        val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        Log.i("FluxaReaderProvider", "original flags=$flags feedbackType=${originalInfo.feedbackType} eventTypes=${originalInfo.eventTypes} capabilities=${originalInfo.capabilities}")
        val owned = mutableListOf<WebView>()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val activityScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activityScenario
            // Verify login without clicking, scrolling, or entering any data source.
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("登录 NewsBlur · 支持免费账号").fetchSemanticsNodes().size == 1
            }
            fun replace(): WebView {
                var result: WebView? = null
                activityScenario.onActivity { activity ->
                    val previous = owned.lastOrNull()
                    val web = WebView(activity)
                    owned.add(web)
                    web.settings.apply {
                        javaScriptEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        domStorageEnabled = false
                        blockNetworkLoads = true
                        textZoom = 100
                    }
                    web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    activity.setContentView(web)
                    result = web
                    previous?.let { it.destroy(); owned.remove(it) }
                }
                return checkNotNull(result)
            }
            fun load(web: WebView, fixture: Int) {
                require(fixture in 0..2)
                val article = com.fluxa.app.domain.model.Article(
                    id(fixture), title(fixture), "$marker feed ${fixture % 3} 中英",
                    java.time.Instant.ofEpochSecond(1800000000L - fixture),
                    false, false, html(fixture))
                val document = com.fluxa.app.ui.article.readerHtml(article, ReaderSettings(), false)
                val visual = java.util.concurrent.atomic.AtomicBoolean(false)
                val deadline = android.os.SystemClock.uptimeMillis() + 10_000
                activityScenario.onActivity {
                    web.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.postVisualStateCallback(fixture.toLong(), object : WebView.VisualStateCallback() {
                                override fun onComplete(requestId: Long) { visual.set(true) }
                            })
                        }
                    }
                    web.loadDataWithBaseURL("https://www.newsblur.com/fluxa-reader/${fixture + 1}/",
                        document, "text/html", "UTF-8", null)
                }
                var previous: List<Int>? = null
                var stableSince = android.os.SystemClock.uptimeMillis()
                var ready = false
                while (android.os.SystemClock.uptimeMillis() < deadline) {
                    var geometry: List<Int>? = null
                    activityScenario.onActivity {
                        if (visual.get() && web.title == title(fixture) && web.progress == 100 &&
                            web.contentHeight > 0 && web.width > 0 && web.height > 0 && web.isShown) {
                            geometry = listOf(web.width, web.height, web.contentHeight, web.scrollY)
                        }
                    }
                    val now = android.os.SystemClock.uptimeMillis()
                    if (geometry == null || geometry != previous) stableSince = now
                    previous = geometry
                    if (geometry != null && now - stableSince >= 500) { ready = true; break }
                    Thread.sleep(50)
                }
                assertTrue("Stable native load/visual callback timed out fixture=$fixture", ready)
            }
            var lastRawExact = false
            @Suppress("DEPRECATION")
            fun sample(label: String, web: WebView, fixture: Int): Boolean {
                var nodes = 0
                var texts = 0
                var exact = 0
                var rawExact = 0
                val paragraphs = mutableSetOf<String>()
                val visible = mutableSetOf<Int>()
                fun visit(node: android.view.accessibility.AccessibilityNodeInfo, viewport: android.graphics.Rect?) {
                    if (!node.refresh()) return
                    val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                    val branch = viewport ?: bounds.takeIf { node.className?.toString() == "android.webkit.WebView" }
                    if (branch != null) nodes++
                    nativeWebText(node, viewport, false)?.let { text ->
                        texts++
                        if (text == paragraph(fixture, 0)) {
                            rawExact++
                            if (nativeWebText(node, viewport, true) == text) exact++
                        }
                        for (p in 0..100) if (text == paragraph(fixture, p)) {
                            paragraphs.add(text)
                            if (nativeWebText(node, viewport, true) == text) visible.add(p)
                        }
                    }
                    for (i in 0 until node.childCount) node.getChild(i)?.let {
                        try { visit(it, branch) } finally { it.recycle() }
                    }
                }
                // Every available current window/branch; active fallback if window retrieval
                // is unavailable under the ORIGINAL service flags. Never mutate those flags.
                val windows = automation.windows
                var roots = 0
                try {
                    for (window in windows) window.root?.let { root ->
                        roots++
                        try { visit(root, null) } finally { root.recycle() }
                    }
                    if (roots == 0) automation.rootInActiveWindow?.let { root ->
                        roots++
                        try { visit(root, null) } finally { root.recycle() }
                    }
                } finally { windows.forEach { it.recycle() } }
                activityScenario.onActivity { activity ->
                    check(views(activity.window.decorView).filterIsInstance<WebView>().single() === web)
                    check(web.title == title(fixture))
                    val pkg = WebView.getCurrentWebViewPackage()
                    Log.i("FluxaReaderProvider", "standalone $label uptime=${android.os.SystemClock.uptimeMillis()} fixture=$fixture expectedTitle=${title(fixture)} roots=$roots nodes=$nodes texts=$texts realParagraphs=${paragraphs.size}/101 visibleParagraphs=$visible exactP000=$rawExact visibleExactP000=$exact identity=${System.identityHashCode(web)} progress=${web.progress} geometry=${web.width}x${web.height} contentHeight=${web.contentHeight} scrollY=${web.scrollY} enabled=${accessibility.isEnabled} touchExploration=${accessibility.isTouchExplorationEnabled} services=${accessibility.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).size} flags=${automation.serviceInfo.flags} package=${pkg?.packageName} version=${pkg?.versionName}")
                }
                assertEquals("Probe must not mutate accessibility flags", flags, automation.serviceInfo.flags)
                lastRawExact = rawExact > 0
                return exact > 0
            }
            fun png(label: String) {
                val file = File(context.filesDir, "reader-provider-${android.os.Process.myPid()}-$label.png")
                check(file.createNewFile()) { "Refusing to overwrite $file" }
                Log.i("FluxaReaderProvider", "ownedScreenshot=${file.absolutePath}")
                val bitmap = checkNotNull(automation.takeScreenshot())
                try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
            }
            fun controlled(label: String, web: WebView) {
                check(Looper.myLooper() != Looper.getMainLooper())
                compose.waitForIdle()
                val started = android.os.SystemClock.uptimeMillis()
                val baseline = sample("$label-baseline", web, 2)
                var firstRaw: Long? = if (lastRawExact) 0L else null
                png("$label-baseline")
                // No getter, snapshot, event, action, cache clear or scroll in this stage.
                var firstExact: Long? = if (baseline) 0L else null
                while (android.os.SystemClock.uptimeMillis() - started < 10_000) {
                    Thread.sleep(50)
                    if (sample("$label-passive", web, 2) && firstExact == null)
                        firstExact = android.os.SystemClock.uptimeMillis() - started
                    if (lastRawExact && firstRaw == null)
                        firstRaw = android.os.SystemClock.uptimeMillis() - started
                }
                sample("$label-passive-end", web, 2)
                Log.i("FluxaReaderProvider", "$label passive firstExactMs=$firstRaw firstVisibleExactMs=$firstExact elapsed=${android.os.SystemClock.uptimeMillis() - started}")
                png("$label-passive-end")
                val providerStart = android.os.SystemClock.uptimeMillis()
                var nonnull = false
                var initialNull = false
                var polls = 0
                do {
                    activityScenario.onActivity {
                        check(web.title == title(2))
                        nonnull = web.accessibilityNodeProvider != null
                        if (polls == 0) initialNull = !nonnull
                    }
                    polls++
                    if (nonnull) break
                    Thread.sleep(50)
                } while (android.os.SystemClock.uptimeMillis() - providerStart < 10_000)
                Log.i("FluxaReaderProvider", "$label provider identity=${System.identityHashCode(web)} initialNull=$initialNull polls=$polls nonnull=$nonnull elapsed=${android.os.SystemClock.uptimeMillis() - providerStart}")
                val treeStart = android.os.SystemClock.uptimeMillis()
                var exact = false
                do {
                    exact = sample("$label-provider-tree", web, 2)
                    if (exact) break
                    Thread.sleep(50)
                } while (android.os.SystemClock.uptimeMillis() - treeStart < 10_000)
                Log.i("FluxaReaderProvider", "$label provider-tree visibleExact=$exact elapsed=${android.os.SystemClock.uptimeMillis() - treeStart}; nonnull alone is not PASS")
                png("$label-before-gesture")
                val viewport = android.graphics.Rect()
                var beforeY = 0
                activityScenario.onActivity {
                    check(web.getGlobalVisibleRect(viewport) && viewport.width() > 0 && viewport.height() > 0)
                    beforeY = web.scrollY
                }
                val down = android.os.SystemClock.uptimeMillis()
                fun inject(action: Int, fraction: Float) {
                    val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                        viewport.exactCenterX(), viewport.top + viewport.height() * fraction, 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                inject(android.view.MotionEvent.ACTION_DOWN, .65f)
                try {
                    for (i in 1..20) {
                        Thread.sleep(20)
                        inject(android.view.MotionEvent.ACTION_MOVE, .65f - .3f * i / 20)
                    }
                } finally { inject(android.view.MotionEvent.ACTION_UP, .35f) }
                val scrollStart = android.os.SystemClock.uptimeMillis()
                var afterY = beforeY
                do {
                    activityScenario.onActivity { afterY = web.scrollY }
                    if (afterY > beforeY) break
                    Thread.sleep(50)
                } while (android.os.SystemClock.uptimeMillis() - scrollStart < 10_000)
                Log.i("FluxaReaderProvider", "$label nativeSwipe viewport=$viewport beforeY=$beforeY afterY=$afterY increased=${afterY > beforeY} elapsed=${android.os.SystemClock.uptimeMillis() - scrollStart}")
                sample("$label-after-gesture", web, 2)
                png("$label-after-gesture")
                check(afterY > beforeY) { "Injected native swipe did not advance owned WebView" }
                // Existing public test sink, strictly AFTER all actual UiAutomation evidence.
                val snapshot = NativeDocumentSnapshotProbe()
                activityScenario.onActivity {
                    check(!web.settings.javaScriptEnabled && !web.settings.allowFileAccess && !web.settings.allowContentAccess)
                    web.onProvideVirtualStructure(snapshot.sink)
                }
                check(snapshot.awaitCompletion()) { "Native snapshot timed out" }
                val entries = snapshot.flatten()
                val supplied = (0..100).count { p -> entries.any {
                    it.text == paragraph(2, p) && it.bounds.width() > 0 && it.bounds.height() > 0
                } }
                Log.i("FluxaReaderProvider", "$label snapshot suppliedPositiveParagraphs=$supplied/101; separate channel, NOT accessibility PASS")
                check(supplied == 101)
            }
            val first = replace()
            load(first, 2)
            controlled("fresh-fixture2", first)
            val second = replace()
            load(second, 0)
            activityScenario.onActivity { second.scrollTo(0, 0) } // Only this owned diagnostic view.
            load(second, 2)
            controlled("reload-fixture0-to2", second)
            Log.i("FluxaReaderProvider", "Standalone diagnostic phases complete; NOT a reader regression PASS")
        } finally {
            try {
                instrumentation.runOnMainSync {
                    owned.forEach { web ->
                        (web.parent as? ViewGroup)?.removeView(web)
                        web.destroy()
                    }
                    owned.clear()
                }
            } finally { scenario?.close() }
        }
    }

    @Test fun nativeDocumentSnapshotWorksWithScriptsDisabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as
            android.view.accessibility.AccessibilityManager
        // UiAutomation in ordinary reader tests is itself an enabled service. Record the
        // environment without making test order a condition of this public API probe.
        Log.i("FluxaReaderExperience", "Native probe enabled services=" + accessibility
            .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).size)
        val paragraphs = (0 until 100).map {
            "NATIVE-P${it.toString().padStart(3, '0')} Unique paragraph $it beside the quiet river library."
        }
        val body = buildString {
            append("<!doctype html><html><head>")
            append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
            append("<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'\">")
            append("</head><body><h1>Native document snapshot</h1>")
            paragraphs.forEach { append("<p>$it</p>") }
            append("<pre>native snapshot\nno script execution</pre>")
            append("<table><tr><th>Place</th><th>Observation</th></tr>")
            append("<tr><td>River</td><td>Quiet library</td></tr></table></body></html>")
        }
        var webView: WebView? = null
        try {
            instrumentation.runOnMainSync {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.blockNetworkLoads = true
                    measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                    layout(0, 0, 360, 640)
                }
            }
            fun snapshot(zoom: Int): Pair<List<NativeDocumentSnapshot.Entry>, Int> {
                val finished = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    checkNotNull(webView).apply {
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                finished.countDown()
                            }
                        }
                        settings.textZoom = zoom
                        loadDataWithBaseURL(null, body, "text/html", "UTF-8", null)
                    }
                }
                assertTrue("Page load timed out at zoom=$zoom", finished.await(10, TimeUnit.SECONDS))
                // onPageFinished can precede native layout. Poll from the test thread;
                // an unattached WebView cannot rely on a visual-state callback.
                val layoutDeadline = android.os.SystemClock.uptimeMillis() + 8_000L
                var layoutReady = false
                while (android.os.SystemClock.uptimeMillis() < layoutDeadline) {
                    instrumentation.runOnMainSync {
                        val web = checkNotNull(webView)
                        layoutReady = web.contentHeight > 0 &&
                            web.measuredWidth > 0 && web.measuredHeight > 0 &&
                            web.width > 0 && web.height > 0
                    }
                    if (layoutReady) break
                    val remaining = layoutDeadline - android.os.SystemClock.uptimeMillis()
                    if (remaining > 0) Thread.sleep(minOf(50L, remaining))
                }
                assertTrue("Native layout timed out at zoom=$zoom", layoutReady)
                val probe = NativeDocumentSnapshotProbe()
                instrumentation.runOnMainSync {
                    val web = checkNotNull(webView)
                    assertFalse(web.settings.javaScriptEnabled)
                    web.onProvideVirtualStructure(probe.sink)
                }
                assertTrue("Native document asyncCommit timed out at zoom=$zoom", probe.awaitCompletion())
                var contentHeight = 0
                instrumentation.runOnMainSync { contentHeight = checkNotNull(webView).contentHeight }
                val entries = probe.flatten()
                val textEntries = entries.filter { !it.text.isNullOrBlank() }
                Log.i("FluxaReaderExperience", "Native zoom=$zoom nodes=${entries.size} text=${textEntries.size} contentHeight=$contentHeight")
                textEntries.take(8).forEach {
                    Log.i("FluxaReaderExperience", "Native text=${it.text} bounds=${it.bounds}")
                }
                assertTrue("Rendered document must have positive contentHeight", contentHeight > 0)
                return entries to contentHeight
            }
            val normal = snapshot(100)
            val enlarged = snapshot(200)
            fun locate(entries: List<NativeDocumentSnapshot.Entry>): NativeDocumentSnapshot.Entry {
                val entry = entries.firstOrNull { it.text == paragraphs[0] }
                assertNotNull("Snapshot must contain exact actual paragraph text", entry)
                return checkNotNull(entry).also {
                    assertTrue("Paragraph must have positive bounds: ${it.bounds}",
                        it.bounds.width() > 0 && it.bounds.height() > 0)
                }
            }
            val before = locate(normal.first)
            val after = locate(enlarged.first)
            assertNotEquals("Text zoom must change the same paragraph's native bounds height",
                before.bounds.height(), after.bounds.height())
            instrumentation.runOnMainSync { assertFalse(checkNotNull(webView).settings.javaScriptEnabled) }
        } finally {
            instrumentation.runOnMainSync { webView?.destroy() }
        }
    }

    @Test fun evaluateJavascriptProbeWithPublisherScriptsDisabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pageFinished = CountDownLatch(1)
        val resultReady = CountDownLatch(1)
        var webView: WebView? = null
        var pageCallbackOnMain = false
        var resultCallbackOnMain = false
        var result: String? = null
        // App-owned literal only: never interpolate publisher content into this query.
        val trustedQuery = """
            (function () {
                var anchor = document.getElementById('anchor');
                var rect = anchor.getBoundingClientRect();
                return { text: anchor.textContent,
                    rect: { x: rect.left, y: rect.top, width: rect.width, height: rect.height },
                    canary: typeof window.publisherCanary };
            })()
        """.trimIndent()
        val html = """
            <!doctype html><html><head>
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
            <style>p { width: 200px; height: 40px; }</style>
            </head><body onload="window.publisherCanary = 'onload'">
            <p id="anchor">Trusted DOM probe text.</p>
            <script>window.publisherCanary = 'inline';</script>
            <a href="javascript:window.publisherCanary='javascript-url'">Untrusted link</a>
            </body></html>
        """.trimIndent()
        try {
            instrumentation.runOnMainSync {
                val web = WebView(context)
                webView = web
                web.settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    blockNetworkLoads = true
                }
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        pageCallbackOnMain = Looper.myLooper() == Looper.getMainLooper()
                        pageFinished.countDown()
                    }
                }
                web.measure(
                    View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY)
                )
                web.layout(0, 0, 320, 240)
                web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
            assertTrue("onPageFinished timed out", pageFinished.await(10, TimeUnit.SECONDS))
            assertTrue("onPageFinished must run on UI thread", pageCallbackOnMain)
            instrumentation.runOnMainSync {
                val web = checkNotNull(webView)
                assertFalse(web.settings.javaScriptEnabled)
                web.evaluateJavascript(trustedQuery) { value ->
                    resultCallbackOnMain = Looper.myLooper() == Looper.getMainLooper()
                    result = value
                    resultReady.countDown()
                }
            }
            assertTrue("evaluateJavascript timed out", resultReady.await(10, TimeUnit.SECONDS))
            assertTrue("evaluateJavascript callback must run on UI thread", resultCallbackOnMain)
            // Observed on API 26 / WebView 58: even app-owned queries do not execute
            // with JavaScript disabled. Literal "null" is not JSON geometry or proof
            // of a canary value; retain the publisher fixture without claiming either.
            Log.i("FluxaReaderExperience", "Local disabled-JS evaluateJavascript result=$result (API26/WebView58 observed literal null)")
            instrumentation.runOnMainSync {
                assertFalse("Publisher JavaScript must remain disabled", checkNotNull(webView).settings.javaScriptEnabled)
            }
        } finally {
            instrumentation.runOnMainSync { webView?.destroy() }
        }
    }

    @Suppress("DEPRECATION")
    @Test fun nativeDomDescriptionIsNotAnArbitraryHostLabel() {
        val node = android.view.accessibility.AccessibilityNodeInfo.obtain()
        val viewport = android.graphics.Rect(0, 0, 100, 100)
        try {
            node.className = "android.view.View"
            node.contentDescription = paragraph(0, 0)
            node.isVisibleToUser = true
            node.setBoundsInScreen(android.graphics.Rect(10, 10, 90, 90))
            assertNull("Even exact prose on a host outside WebView is not DOM text",
                nativeWebText(node, null, false))
            assertEquals(paragraph(0, 0), nativeWebText(node, viewport, true))
            for (host in listOf("android.webkit.WebView", "androidx.compose.ui.platform.AndroidComposeView",
                "androidx.compose.ui.viewinterop.AndroidViewHolder", "android.widget.Image")) {
                node.className = host
                assertNull("Host/image description must not count: $host", nativeWebText(node, viewport, true))
            }
            node.className = "android.view.View"
            node.setBoundsInScreen(android.graphics.Rect(10, 110, 90, 100))
            assertNull("Provider's inverted offscreen bounds are not visible", nativeWebText(node, viewport, true))
            node.setBoundsInScreen(android.graphics.Rect(10, 110, 90, 150))
            assertNull("Offscreen prose is not visible", nativeWebText(node, viewport, true))
            node.setBoundsInScreen(android.graphics.Rect(10, 10, 90, 90))
            node.contentDescription = null
            node.text = paragraph(0, 0)
            assertEquals(paragraph(0, 0), nativeWebText(node, viewport, true))
        } finally { node.recycle() }
    }

    @Test fun phasedReaderExperience(): Unit = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("readerPhase")
        assumeTrue("Explicit readerPhase=seed|verify|cleanup required", phase != null)
        require(phase in listOf("seed", "verify", "cleanup"))
        // Never modify authentication or let fixtures reach an authenticated server.
        assumeTrue("Use a dedicated signed-out emulator", SecureTokenStore(context).getNewsBlurSession() == null)
        // Enable native accessibility before Chromium is constructed, not after load.
        checkNotNull(readerUiAutomation().serviceInfo)
        awaitExistingReaderMenuService()
        val db = Room.databaseBuilder(context, FluxaDatabase::class.java, "newsblur.db")
            .addMigrations(DatabaseMigrations.FROM_1, DatabaseMigrations.FROM_2).build()
        try {
            when (phase) {
                "seed" -> {
                    check(!backup.exists()) { "Unfinished fixture run: use cleanup in a fresh process first" }
                    for (i in 0 until count) check(db.articleDao().getById(id(i)) == null) { "Reserved article collision" }
                    check(db.subscriptionDao().getAll().none { it.id in (0..2).map(::feed) }) { "Reserved feed collision" }
                    check(db.pendingActionDao().getAllOrdered().none { it.articleId in (0 until count).map(::id) }) {
                        "Reserved pending-action collision"
                    }
                    // Save before any Activity/Hilt reader store can initialize. No clear-app-data.
                    backup.writeText(JSONObject().put("present", prefs.contains("state"))
                        .put("state", prefs.getString("state", null) ?: JSONObject.NULL).toString())
                    db.subscriptionDao().upsertAll((0..2).map {
                        SubscriptionEntity(feed(it), "$marker feed $it 中英", "https://example.invalid/reader-$it.xml")
                    })
                    db.articleDao().upsertAll((0 until count).map {
                        ArticleEntity(id(it), title(it), "$marker feed ${it % 3} 中英", 1800000000L - it,
                            false, false, html(it), feed(it))
                    })
                    seed(db)
                }
                "verify" -> { check(backup.exists() && evidence.exists()); verify(db) }
                "cleanup" -> {
                    check(backup.exists()) { "No ownership record; refusing cleanup" }
                    val original = JSONObject(backup.readText())
                    // Only these exact reserved IDs, never pending actions or articles belonging to users.
                    db.pendingActionDao().getAllOrdered().filter { it.articleId in (0 until count).map(::id) }
                        .forEach { db.pendingActionDao().deleteById(it.id) }
                    for (i in 0 until count) db.openHelper.writableDatabase.execSQL(
                        "DELETE FROM articles WHERE id = ?", arrayOf(id(i)))
                    for (i in 0..2) db.openHelper.writableDatabase.execSQL(
                        "DELETE FROM subscriptions WHERE id = ?", arrayOf(feed(i)))
                    val editor = prefs.edit()
                    if (original.getBoolean("present")) editor.putString("state", original.getString("state"))
                    else editor.remove("state")
                    check(editor.commit())
                    check(backup.delete())
                    if (evidence.exists()) check(evidence.delete())
                    Log.i("FluxaReaderExperience", "Own fixtures removed; original reader state restored")
                }
            }
        } finally { db.close() }
    }

    /** Root owns enabling/restoring the existing service; never change settings or masks. */
    private fun awaitExistingReaderMenuService() {
        if (InstrumentationRegistry.getArguments().getString("readerKeepServices") != "true") return
        check(Looper.myLooper() != Looper.getMainLooper()) { "Service wait must be off-main" }
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as
            android.view.accessibility.AccessibilityManager
        val expected = "com.android.systemui.accessibility.accessibilitymenu/.AccessibilityMenuService"
        val started = android.os.SystemClock.uptimeMillis()
        val deadline = started + 10_000L
        var lastState = "not sampled"
        do {
            val enabled = manager.isEnabled
            val services = manager.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val menu = services.firstOrNull {
                android.content.ComponentName.unflattenFromString(it.id)?.flattenToShortString() == expected
            }
            lastState = "enabled=$enabled services=${services.size} existingMenuConnected=${menu != null} " +
                "ids=${services.map { it.id }} elapsed=${android.os.SystemClock.uptimeMillis() - started}"
            if (enabled && menu != null) {
                val tool = if (Build.VERSION.SDK_INT >= 31) menu.isAccessibilityTool else null
                Log.i("FluxaReaderExperience", "readerKeepServices=true requestedFlags=${android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES} " +
                    "$lastState component=$expected flags=${menu.flags} eventTypes=${menu.eventTypes} " +
                    "feedbackType=${menu.feedbackType} capabilities=${menu.capabilities} isAccessibilityTool=$tool; " +
                    "existing Accessibility Menu, NOT TalkBack or spoken acceptance")
                if (Build.VERSION.SDK_INT >= 31) check(tool == true) {
                    "Existing Accessibility Menu must report isAccessibilityTool=true"
                }
                return
            }
            val remaining = deadline - android.os.SystemClock.uptimeMillis()
            if (remaining <= 0) break
            Thread.sleep(minOf(50L, remaining))
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        error("Existing system Accessibility Menu not connected within 10s: $lastState")
    }

    private fun enter(sync: DeviceFlowSync) {
        sync.login()
        sync.scrollAndClick("直接阅读")
        sync.list()
        compose.onNodeWithContentDescription("搜索").performTouchInput { click() }
        val search = compose.onNodeWithText("搜索文章")
        search.performScrollTo().performTextInput(marker)
        // Close the keyboard without navigating away.
        pressBack()
        sync.await { compose.onAllNodesWithText(title(0)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun showReaderControls(sync: DeviceFlowSync) {
        if (compose.onAllNodesWithContentDescription("显示阅读工具").fetchSemanticsNodes().isNotEmpty()) {
            val show = compose.onNodeWithContentDescription("显示阅读工具")
            sync.settled(show)
            show.performTouchInput { click() }
        }
    }

    private fun touch(sync: DeviceFlowSync, text: String) {
        showReaderControls(sync)
        val node = compose.onNodeWithContentDescription(text, useUnmergedTree = true)
        sync.settled(node)
        node.performTouchInput { click() }
    }

    private fun open(sync: DeviceFlowSync, i: Int, body: Boolean = true) {
        sync.article(title(i), click = true)
        sync.reader(title(i))
        if (body) sync.body(paragraph(i, 0))
    }

    // reader-content retains full-height WebView bounds beneath the chrome overlays.
    // Default swipeUp starts at the bottom toolbar, not HTML; use the unoccluded
    // central body for genuine touch input, whether reader controls are shown or hidden.
    private fun swipeReaderBodyUp() {
        compose.onNodeWithTag("reader-content").performTouchInput {
            swipe(Offset(width / 2f, height * .7f), Offset(width / 2f, height * .3f), 600)
        }
    }

    private fun seed(db: FluxaDatabase) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val sync = DeviceFlowSync(compose, scenario)
            sync.step("seed UI acceptance") {
            enter(sync)
            val originalBars = barFlags(scenario)
            val originalNavigationColor = navigationBarColor(scenario)
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("未读"))
            touch(sync, "未读")
            open(sync, 0)
            compose.onNodeWithContentDescription("已是首篇").assertIsNotEnabled()
            touch(sync, "下一篇"); sync.reader(title(1)); sync.body(paragraph(1, 0))
            touch(sync, "上一篇"); sync.reader(title(0)); sync.body(paragraph(0, 0))
            // Frozen unread order must retain both removed rows, without skipping or wrapping.
            for (i in 1 until count) {
                if (i != 27) touch(sync, "下一篇")
                sync.reader(title(i)); sync.body(paragraph(i, 0))
                compose.onNodeWithText("${i + 1} / $count").assertIsDisplayed()
                if (i == 26) {
                    pressBack(); sync.list()
                    // Establish a genuine mid-list entry with unread neighbors remaining.
                    open(sync, 40)
                    // Consume every original neighbor before the entry anchor, including
                    // any earlier first-visible row. Returning must resolve a removed ID.
                    for (neighbor in 39 downTo 27) {
                        touch(sync, "上一篇"); sync.reader(title(neighbor)); sync.body(paragraph(neighbor, 0))
                    }
                    for (neighbor in 28..41) {
                        touch(sync, "下一篇"); sync.reader(title(neighbor)); sync.body(paragraph(neighbor, 0))
                    }
                    pressBack(); sync.list()
                    // No scroll/search after back: prove the surviving unread anchor.
                    val survivor = compose.onNodeWithText(title(42), useUnmergedTree = true)
                    sync.settled(survivor)
                    survivor.assertIsDisplayed()
                    compose.onNodeWithText(title(0)).assertDoesNotExist()
                    screenshot("mid-list-unread-anchor")
                    compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("全部"))
                    touch(sync, "全部")
                    open(sync, 27)
                }
                Log.i("FluxaReaderExperience", "Reader traversal ${i + 1}/$count: actual WebView title/body matched ${id(i)}")
            }
            compose.onNodeWithContentDescription("已是末篇").assertIsNotEnabled()
            touch(sync, "上一篇"); sync.reader(title(count - 2)); sync.body(paragraph(count - 2, 0))
            screenshot("frozen-boundary")
            pressBack(); sync.list()
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("未读"))
            touch(sync, "未读")
            sync.await { compose.onAllNodesWithText("没有匹配的文章").fetchSemanticsNodes().size == 1 }
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("全部"))
            touch(sync, "全部")
            open(sync, 0)
            // Normalize through real UI regardless of the user's preserved original preferences.
            settings(sync, "浅色", fontMax = false)
            assertEquals(true to true, barFlags(scenario))
            assertNavigationBarColor(scenario, 0xfffafbf8.toInt())
            sync.reader(title(0)); sync.body(paragraph(0, 0))
            captureLayout(sync, scenario)
            repeat(6) { swipeReaderBodyUp() }
            val scrolled = paragraphCheckpoint(sync, scenario, "long-scroll")
            assertTrue("Real touch must advance actual paragraphs: $scrolled",
                scrolled.y > 200 && scrolled.ratio > .01 && scrolled.paragraph > 0)
            screenshot("long-scroll")
            touch(sync, "下一篇"); sync.reader(title(1))
            repeat(3) { swipeReaderBodyUp() }
            val second = viewport(sync, scenario, "article01-independent", 1)
            assertTrue(second.contentKey.isNotBlank() && second.ratio > .01)
            touch(sync, "上一篇"); sync.reader(title(0))
            val firstReturn = viewport(sync, scenario, "article00-prev-return")
            assertNear(scrolled, firstReturn, .03)
            assertContent(scrolled, firstReturn)
            touch(sync, "下一篇"); sync.reader(title(1))
            val secondReturn = viewport(sync, scenario, "article01-next-return", 1)
            assertNear(second, secondReturn, .03)
            assertContent(second, secondReturn)
            touch(sync, "上一篇"); sync.reader(title(0))
            val beforeStar = viewport(sync, scenario, "before-star")
            touch(sync, "收藏")
            sync.settled(compose.onNodeWithContentDescription("取消收藏"))
            val starred = viewport(sync, scenario, "star-no-reload")
            assertNear(beforeStar, starred, .01)
            assertEquals("Star must not reload HTML", beforeStar.url, starred.url)
            assertEquals(beforeStar.paragraph, starred.paragraph)
            assertContent(beforeStar, starred)
            pressBack(); sync.list()
            assertEquals("Reader must restore Activity bar flags after Back", originalBars, barFlags(scenario))
            assertEquals("Reader must restore Activity nav color after Back",
                originalNavigationColor, navigationBarColor(scenario))
            open(sync, 0, body = false)
            val reopened = viewport(sync, scenario, "back-reopen")
            assertNear(scrolled, reopened, .03)
            assertContent(scrolled, reopened)

            var orientation = Configuration.ORIENTATION_UNDEFINED
            var requested = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            scenario.onActivity { orientation = it.resources.configuration.orientation; requested = it.requestedOrientation }
            val rotated = if (orientation == Configuration.ORIENTATION_PORTRAIT) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            scenario.onActivity { it.requestedOrientation = if (rotated == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            sync.await {
                var matches = false
                scenario.onActivity { matches = it.resources.configuration.orientation == rotated }
                matches
            }
            sync.reader(title(0))
            assertContent(reopened, viewport(sync, scenario, "rotation"))
            screenshot("rotated")
            scenario.onActivity { it.requestedOrientation = requested }
            sync.await {
                var matches = false
                scenario.onActivity { matches = it.resources.configuration.orientation == orientation }
                matches
            }
            sync.configuration()
            sync.reader(title(0))
            val rotatedBack = viewport(sync, scenario, "rotation-return")
            assertContent(reopened, rotatedBack)
            val beforeFont = paragraphCheckpoint(sync, scenario, "before-reflow")
            settings(sync, "纸色", fontMax = true)
            sync.reader(title(0))
            val afterFont = viewport(sync, scenario, "font-theme-reflow")
            assertContent(beforeFont, afterFont)
            assertTrue("Font reflow paragraph drift must be <=1", beforeFont.paragraph >= 0 &&
                afterFont.paragraph >= 0 && abs(beforeFont.paragraph - afterFont.paragraph) <= 1)
            assertEquals(true to true, barFlags(scenario))
            assertNavigationBarColor(scenario, 0xfff4ecd9.toInt())
            assertTrue("Font/spacing must change actual rendered range", afterFont.range > beforeFont.range)
            screenshot("sepia-reflow")
            settings(sync, "深色", fontMax = true)
            sync.reader(title(0))
            val darkViewport = viewport(sync, scenario, "dark-theme")
            assertNear(afterFont, darkViewport, .03)
            assertContent(afterFont, darkViewport)
            assertEquals(false to false, barFlags(scenario))
            assertNavigationBarColor(scenario, 0xff171b19.toInt())
            screenshot("dark-theme")
            assertOffline(sync, db)
            // Await the real IO writer, not an arbitrary sleep before root force-stops the process.
            sync.await {
                val json = persisted()
                val p = json.optJSONObject("positions")?.optJSONObject(id(0))
                json.optInt("font") == 30 && json.optString("theme") == "Dark" &&
                    json.optDouble("spacing") > 2.1 && p != null &&
                    abs(p.optDouble("fraction") - afterFont.ratio) < .01 &&
                    p.optJSONObject("anchor")?.optString("key") == afterFont.contentKey
            }
            val saved = persisted()
            evidence.writeText(JSONObject().put("ratio", afterFont.ratio).put("paragraph", afterFont.paragraph)
                .put("contentKey", afterFont.contentKey).put("occurrence", afterFont.occurrence)
                .put("anchor", saved.getJSONObject("positions").getJSONObject(id(0)).getJSONObject("anchor"))
                .put("font", saved.getInt("font")).put("spacing", saved.getDouble("spacing"))
                .put("theme", saved.getString("theme")).put("pid", android.os.Process.myPid()).toString())
            Log.i("FluxaReaderExperience", "SEED COMPLETE pid=${android.os.Process.myPid()}; force-stop before verify")
            }
        }
    }

    private fun verify(db: FluxaDatabase) {
        val expected = JSONObject(evidence.readText())
        assertNotEquals("Must be a separate process, not Activity recreation", expected.getInt("pid"), android.os.Process.myPid())
        val saved = persisted()
        assertEquals(expected.getInt("font"), saved.getInt("font"))
        assertEquals(expected.getDouble("spacing"), saved.getDouble("spacing"), .001)
        assertEquals(expected.getString("theme"), saved.getString("theme"))
        assertEquals(count, saved.getJSONArray("session").length())
        assertEquals(expected.getJSONObject("anchor").toString(),
            saved.getJSONObject("positions").getJSONObject(id(0)).getJSONObject("anchor").toString())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val sync = DeviceFlowSync(compose, scenario)
            sync.step("verify UI acceptance") {
            enter(sync)
            val originalBars = barFlags(scenario)
            val originalNavigationColor = navigationBarColor(scenario)
            open(sync, 0, body = false)
            val restored = viewport(sync, scenario, "true-process-restart")
            assertEquals(expected.getDouble("ratio"), restored.ratio, .05)
            assertEquals(expected.getString("contentKey"), restored.contentKey)
            assertEquals(expected.getInt("occurrence"), restored.occurrence)
            assertTrue("Process restart retains paragraph", abs(expected.getInt("paragraph") - restored.paragraph) <= 1)
            assertEquals(false to false, barFlags(scenario))
            assertNavigationBarColor(scenario, 0xff171b19.toInt())
            compose.onNodeWithContentDescription("取消收藏").assertIsDisplayed()
            compose.onNodeWithContentDescription("阅读设置").performTouchInput { click() }
            compose.onNodeWithText("字号 30").assertExists()
            compose.onNodeWithContentDescription("深色").performScrollTo().assertIsSelected()
            touch(sync, "完成")
            assertOffline(sync, db)
            screenshot("process-verified")
            pressBack(); sync.list()
            assertEquals("Dark reader must restore flags after Back", originalBars, barFlags(scenario))
            assertEquals("Dark reader must restore Activity nav color after Back",
                originalNavigationColor, navigationBarColor(scenario))
            screenshot("dark-back-restored-bars")
            Log.i("FluxaReaderExperience", "VERIFY COMPLETE pid=${android.os.Process.myPid()}")
            }
        }
    }

    private fun captureLayout(sync: DeviceFlowSync, scenario: ActivityScenario<MainActivity>) =
        sync.step("inline image quote code table restoration") {
        for (category in listOf("image", "quote", "code", "table")) {
            var checkpoint: Viewport? = null
            // At most 30 real gestures, aimed at an interior rather than sampling past short quotes.
            for (move in 0..30) {
                val current = viewport(sync, scenario, "inline-$category-approach-$move")
                val native = layoutSnapshot(scenario)
                assertEquals(current.y, native.y)
                val chosen = native.chosen(current)
                if (current.intersects && native.visible(chosen) && native.category(chosen) == category &&
                    current.y > chosen.top && current.y < chosen.top + chosen.height) {
                    checkpoint = current
                    break
                }
                val target = native.blocks.firstOrNull { native.category(it) == category }
                assertNotNull("No actual native $category block in fixture", target)
                val block = checkNotNull(target)
                val desired = block.top + block.height * .35f
                val delta = (desired - native.y).coerceIn(-native.height * .55f, native.height * .55f)
                assertTrue("$category not reached after 30 targeted gestures: $current", move < 30)
                compose.onNodeWithTag("reader-content").performTouchInput {
                    val distance = delta.coerceIn(-height * .55f, height * .55f)
                    val start = if (distance >= 0) height * .8f else height * .2f
                    swipe(Offset(width / 2f, start), Offset(width / 2f, start - distance), 600)
                }
            }
            val before = checkNotNull(checkpoint)
            val baseline = layoutSnapshot(scenario)
            val stableBlock = baseline.chosen(before)
            assertEquals(before.y, baseline.y)
            assertTrue(baseline.visible(stableBlock))
            Log.i("FluxaReaderExperience", "inline-$category y=${before.y} key=${before.contentKey} " +
                "occurrence=${before.occurrence} kind=${before.kind} category=$category text=${baseline.text(stableBlock)}")
            val interior = (before.y - stableBlock.top).toDouble() / stableBlock.height
            screenshot("inline-$category")
            touch(sync, "下一篇"); sync.reader(title(1))
            touch(sync, "上一篇"); sync.reader(title(0))
            val restored = viewport(sync, scenario, "inline-$category-reopened")
            assertContent(before, restored)
            assertTrue("Returned chosen content must actually intersect", restored.intersects)
            assertEquals("Unchanged native layout must retain exact pixels", before.y, restored.y)
            screenshot("inline-$category-restored")
            for (large in listOf(true, false)) {
                settings(sync, "浅色", fontMax = large)
                sync.reader(title(0))
                val label = "inline-$category-${if (large) "reflow" else "reflow-return"}"
                val reflow = viewport(sync, scenario, label)
                val native = layoutSnapshot(scenario)
                assertEquals(reflow.y, native.y)
                // Overlapping table columns can change capture's shortest-block tie winner.
                // Still track the ORIGINAL actual chosen cell, never an unrelated label.
                if (category != "table") assertContent(before, reflow)
                val same = native.blocks.singleOrNull { it.key == stableBlock.key &&
                    it.occurrence == stableBlock.occurrence && it.kind == stableBlock.kind }
                assertNotNull("$label lost actual content ${before.contentKey}", same)
                val block = checkNotNull(same)
                assertEquals(category, native.category(block))
                assertTrue("$label original content must remain actually visible",
                    native.visible(block))
                assertEquals("$label same interior point must remain at viewport top",
                    0.0, block.top + interior * block.height - native.y, 2.0)
                assertTrue("$label chosen anchor must intersect actual native content", reflow.intersects)
                Log.i("FluxaReaderExperience", "$label y=${native.y} key=${block.key} " +
                    "occurrence=${block.occurrence} kind=${block.kind} category=$category text=${native.text(block)}")
                screenshot(label)
            }
        }
    }

    private data class LayoutSnapshot(val y: Int, val height: Int, val width: Int,
        val blocks: List<NativeBlock>, val entries: List<NativeDocumentSnapshot.Entry>) {
        fun entry(block: NativeBlock) = entries.first { it.path == block.path }
        fun text(block: NativeBlock) = entry(block).let { it.text ?: it.description.orEmpty() }
        fun chosen(viewport: Viewport) = blocks.single { it.key == viewport.contentKey &&
            it.occurrence == viewport.occurrence && it.kind == viewport.kind }
        fun visible(block: NativeBlock): Boolean {
            val rect = entry(block).bounds
            return rect.left < width && rect.right > 0 && rect.top < y + height && rect.bottom > y
        }
        fun category(block: NativeBlock): String? {
            val text = text(block)
            val tags = entries.filter { it.path == block.path || block.path.startsWith(it.path + "/") }
                .mapNotNull { it.html?.tag?.lowercase() }
            return when {
                block.kind == "image" || "img" in tags || text.contains("Original river library illustration") -> "image"
                "blockquote" in tags || text.contains("Reading slowly") || text.contains("慢读不是停下脚步") -> "quote"
                "pre" in tags || "code" in tags || text.contains("println(message)") -> "code"
                "table" in tags || text.trim() in listOf("地点 / Place", "观察 / Observation", "河岸",
                    "A long table cell with original bilingual observations.") -> "table"
                else -> null
            }
        }
    }

    private fun layoutSnapshot(scenario: ActivityScenario<MainActivity>): LayoutSnapshot {
        val probe = NativeDocumentSnapshotProbe()
        var y = 0
        var height = 0
        var width = 0
        scenario.onActivity { activity ->
            val web = views(activity.window.decorView).filterIsInstance<WebView>().single()
            assertFalse(web.settings.javaScriptEnabled)
            y = web.scrollY; height = web.height; width = web.width
            web.onProvideVirtualStructure(probe.sink)
        }
        assertTrue("Full native layout callback timed out", probe.awaitCompletion())
        var result: LayoutSnapshot? = null
        scenario.onActivity {
            result = LayoutSnapshot(y, height, width, probe.sink.blocks(), probe.flatten())
        }
        return checkNotNull(result)
    }

    private fun settings(sync: DeviceFlowSync, theme: String, fontMax: Boolean) {
        showReaderControls(sync)
        compose.onNodeWithContentDescription("阅读设置").performTouchInput { click() }
        val font = compose.onNodeWithTag("reader-font")
        font.performScrollTo(); sync.settled(font)
        font.performTouchInput { click(Offset(if (fontMax) width - 1f else 1f, height / 2f)) }
        compose.onNodeWithText("字号 ${if (fontMax) 30 else 14}").assertExists()
        val spacing = compose.onNodeWithTag("reader-spacing")
        spacing.performScrollTo(); sync.settled(spacing)
        spacing.performTouchInput { click(Offset(if (fontMax) width - 1f else 1f, height / 2f)) }
        compose.onNodeWithText("行距 %.1f".format(if (fontMax) 2.4f else 1.3f)).assertExists()
        val chip = compose.onNodeWithContentDescription(theme)
        chip.performScrollTo(); sync.settled(chip)
        chip.performTouchInput { click() }; chip.assertIsSelected()
        touch(sync, "完成")
    }

    private data class Viewport(val y: Int, val range: Int, val ratio: Double, val paragraph: Int, val url: String?,
        val contentKey: String, val occurrence: Int, val kind: String, val contentText: String, val intersects: Boolean)
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
    private fun viewport(sync: DeviceFlowSync, scenario: ActivityScenario<MainActivity>, label: String, article: Int = 0): Viewport {
        var previous: Viewport? = null
        var stableSince = android.os.SystemClock.uptimeMillis()
        sync.await {
            var measured: Viewport? = null
            var geometry: Triple<Int, Int, String?>? = null
            var height = 0
            val probe = NativeDocumentSnapshotProbe()
            scenario.onActivity { activity ->
                val web = views(activity.window.decorView).filterIsInstance<WebView>().singleOrNull()
                if (web != null && web.title == title(article) && web.progress == 100 && web.height > 0) {
                    assertFalse(web.settings.javaScriptEnabled)
                    assertFalse(web.settings.allowFileAccess)
                    assertFalse(web.settings.allowContentAccess)
                    val range = (web.contentHeight * web.resources.displayMetrics.density - web.height).toInt().coerceAtLeast(0)
                    geometry = Triple(web.scrollY, range, web.url)
                    height = web.height
                    web.onProvideVirtualStructure(probe.sink)
                }
            }
            geometry?.let { (y, range, url) ->
                assertTrue("Native viewport snapshot timed out", probe.awaitCompletion())
                val blocks = probe.sink.blocks()
                val anchor = checkNotNull(NativeAnchors.capture(blocks, y)) { "No identifiable native content" }
                val block = blocks.first { it.key == anchor.key && it.occurrence == anchor.occurrence && it.kind == anchor.kind }
                val entry = probe.flatten().first { it.path == block.path }
                val text = entry.text ?: entry.description.orEmpty()
                val paragraph = Regex("READER-$article-P(\\d{3})").find(text)?.groupValues?.get(1)?.toInt() ?: -1
                measured = Viewport(y, range, if (range == 0) 0.0 else y.toDouble() / range, paragraph, url,
                    anchor.key, anchor.occurrence, anchor.kind, text,
                    block.top < y + height && block.top + block.height > y)
            }
            if (measured == null || measured != previous) stableSince = android.os.SystemClock.uptimeMillis()
            previous = measured
            measured != null && android.os.SystemClock.uptimeMillis() - stableSince >= 700
        }
        return checkNotNull(previous).also { Log.i("FluxaReaderExperience", "$label: $it") }
    }
    /** Only for establishing a paragraph-specific checkpoint, never after failed restoration. */
    private fun paragraphCheckpoint(sync: DeviceFlowSync, scenario: ActivityScenario<MainActivity>, label: String): Viewport {
        for (move in 0..12) {
            val current = viewport(sync, scenario, "$label-paragraph-$move")
            if (current.paragraph >= 0 && current.intersects) return current
            if (move < 12) compose.onNodeWithTag("reader-content").performTouchInput {
                swipe(Offset(width / 2f, height * .7f), Offset(width / 2f, height * .5f), 400)
            }
        }
        error("No native paragraph reached by 12 genuine gestures at $label")
    }
    @Suppress("DEPRECATION")
    private fun navigationBarColor(scenario: ActivityScenario<MainActivity>): Int? {
        if (Build.VERSION.SDK_INT !in 26..34) return null
        var color: Int? = null
        scenario.onActivity { color = it.window.navigationBarColor }
        return checkNotNull(color)
    }
    private fun assertNavigationBarColor(scenario: ActivityScenario<MainActivity>, expected: Int) {
        if (Build.VERSION.SDK_INT in 26..34) {
            assertEquals("Activity navigation bar must match reader palette", expected,
                checkNotNull(navigationBarColor(scenario)))
        }
    }
    private fun barFlags(scenario: ActivityScenario<MainActivity>): Pair<Boolean, Boolean> {
        var flags: Pair<Boolean, Boolean>? = null
        scenario.onActivity {
            val bars = WindowInsetsControllerCompat(it.window, it.window.decorView)
            flags = bars.isAppearanceLightStatusBars to bars.isAppearanceLightNavigationBars
        }
        return checkNotNull(flags)
    }
    private fun assertContent(before: Viewport, after: Viewport) {
        assertEquals("Native content identity: before=$before after=$after", before.contentKey, after.contentKey)
        assertEquals("Repeated content occurrence", before.occurrence, after.occurrence)
        assertEquals(before.kind, after.kind)
    }
    private fun assertNear(before: Viewport, after: Viewport, tolerance: Double) {
        assertEquals("Viewport ratio: before=$before after=$after", before.ratio, after.ratio, tolerance)
    }
    private fun persisted() = JSONObject(prefs.getString("state", "{}") ?: "{}")
    private fun assertOffline(sync: DeviceFlowSync, db: FluxaDatabase) {
        sync.await {
            runBlocking {
                (0 until count).all { db.articleDao().getById(id(it))?.isRead == true } &&
                    db.articleDao().getById(id(0))?.isStarred == true &&
                    db.pendingActionDao().getAllOrdered().count { it.articleId in (0 until count).map(::id) && it.actionType != "LocalRevision" } == count + 1
            }
        }
        runBlocking {
            val actions = db.pendingActionDao().getAllOrdered().filter { it.articleId in (0 until count).map(::id) }
            assertEquals(count, actions.count { it.actionType == "MarkRead" })
            assertEquals(1, actions.count { it.actionType == "ToggleStar" && it.payload == "true" && it.articleId == id(0) })
            assertTrue(db.articleDao().getById(id(0))!!.contentHtml.contains("data:image/png;base64,"))
            assertEquals(3, db.subscriptionDao().getAll().count { it.id in (0..2).map(::feed) })
            Log.i("FluxaReaderExperience", "Room read=$count star=1 pending=${actions.size}")
        }
    }
    private fun screenshot(label: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(readerUiAutomation().takeScreenshot())
        File(context.filesDir, "reader-experience-$label.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
