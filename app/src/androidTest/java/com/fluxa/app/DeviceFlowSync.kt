package com.fluxa.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityWindowInfo
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import java.util.concurrent.atomic.AtomicBoolean

/** Keep the acquisition mode consistent throughout the opt-in reader flow. */
internal fun readerUiAutomation(): android.app.UiAutomation {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    return if (InstrumentationRegistry.getArguments().getString("readerKeepServices") == "true")
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    else instrumentation.uiAutomation
}

/** Provider-native DOM text only; host labels and image alt descriptions are not prose. */
internal fun nativeWebText(
    node: android.view.accessibility.AccessibilityNodeInfo,
    webBounds: Rect?,
    visibleOnly: Boolean
): String? {
    if (webBounds == null) return null
    val className = node.className?.toString()
    if (className == "android.webkit.WebView" || className?.contains("Image") == true ||
        className?.startsWith("androidx.compose.") == true) return null
    if (visibleOnly) {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (!node.isVisibleToUser || bounds.isEmpty || webBounds.isEmpty ||
            !Rect.intersects(webBounds, bounds)) return null
    }
    node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
    // WebView 58 exposes paragraph leaves as View descriptions, not node.text.
    return if (node.childCount == 0 && className in listOf("android.view.View", "android.widget.TextView"))
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() } else null
}

/** Synchronization only: never repeats a click or retries a flow. */
internal class DeviceFlowSync(
    private val compose: ComposeTestRule,
    private val scenario: ActivityScenario<MainActivity>
) {
    private var selectedTitle: String? = null
    private var providerProbeDone = false
    private var phase = "launch"
    private var lastWindow = "Activity not sampled"

    fun <T> step(name: String, block: () -> T): T {
        phase = name
        try {
            return block()
        } catch (failure: Throwable) {
            val capture = runCatching {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val file = java.io.File(instrumentation.targetContext.filesDir, "reader-experience-failure.png")
                val bitmap = checkNotNull(readerUiAutomation().takeScreenshot())
                try { file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
                file.absolutePath
            }.getOrElse { "Screenshot failed: $it" }
            val details = diagnostics() + "\nfailureScreenshot=$capture"
            details.chunked(3000).forEach { Log.e("FluxaDeviceFlow", it) }
            Log.e("FluxaDeviceFlow", "Failed: $name", failure)
            throw AssertionError("$name failed\n$details", failure)
        }
    }

    // Optional runner arguments: -e expectedOrientation landscape -e expectedFontScale 1.5
    // Inspect the Activity, not application resources or the requested device settings.
    fun configuration() = step("verify Activity configuration") {
        val args = InstrumentationRegistry.getArguments()
        val orientation = args.getString("expectedOrientation")?.let {
            when (it) {
                "landscape" -> Configuration.ORIENTATION_LANDSCAPE
                "portrait" -> Configuration.ORIENTATION_PORTRAIT
                else -> error("expectedOrientation must be landscape or portrait: $it")
            }
        }
        val font = args.getString("expectedFontScale")?.let {
            it.toFloatOrNull()?.takeIf { value -> value.isFinite() && value > 0 }
                ?: error("expectedFontScale must be a positive scale (150 percent = 1.5): $it")
        }
        await {
            var matches = false
            scenario.onActivity {
                val config = it.resources.configuration
                matches = (orientation == null || config.orientation == orientation) &&
                    (font == null || abs(config.fontScale - font) < 0.01f)
            }
            matches
        }
        Log.i("FluxaDeviceFlow", window())
    }

    fun await(predicate: () -> Boolean) {
        compose.waitUntil(10_000) { predicate() }
        compose.waitForIdle()
    }

    fun absent(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()

    fun displayed(node: SemanticsNodeInteraction): Boolean =
        try { node.assertIsDisplayed(); true } catch (_: AssertionError) { false }

    /** Idle Compose transitions, then require displayed, unchanged bounds on successive samples. */
    fun settled(node: SemanticsNodeInteraction) {
        var previous: androidx.compose.ui.geometry.Rect? = null
        var previousWindow: Int? = null
        await {
            compose.waitForIdle()
            if (!displayed(node)) {
                previous = null
                previousWindow = null
                return@await false
            }
            val target = node.fetchSemanticsNode()
            val dialog = generateSequence(target) { it.parent }.any { isDialog().matches(it) }
            var mainFocused = false
            var mainReady = false
            scenario.onActivity {
                val decor = it.window.decorView
                mainFocused = decor.hasWindowFocus()
                mainReady = !decor.isLayoutRequested && decor.width > 0 && decor.height > 0
            }
            val bounds = if (dialog) androidx.compose.ui.geometry.Rect(
                target.positionOnScreen, target.boundsInWindow.size
            ) else target.boundsInWindow
            val focusedWindow = if (dialog && !mainFocused) focusedDialogWindow(target) else null
            val windowReady = mainReady && if (dialog) focusedWindow != null else mainFocused
            val stable = windowReady && previous == bounds && previousWindow == focusedWindow &&
                bounds.width > 0 && bounds.height > 0
            previous = bounds.takeIf { windowReady }
            previousWindow = focusedWindow
            stable
        }
        node.assertIsDisplayed()
    }

    /** Dialog semantics plus a focused app window covering the actual on-screen target. */
    private fun focusedDialogWindow(target: androidx.compose.ui.semantics.SemanticsNode): Int? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = readerUiAutomation()
        val info = automation.serviceInfo
        if (info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
        }
        val position = target.positionOnScreen
        val size = target.boundsInWindow.size
        if (!position.x.isFinite() || !position.y.isFinite() || size.width <= 0 || size.height <= 0)
            return null
        val windows = automation.windows
        return try {
            windows.firstOrNull { window ->
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isFocused)
                    return@firstOrNull false
                val bounds = Rect().also(window::getBoundsInScreen)
                val root = window.root ?: return@firstOrNull false
                try {
                    root.packageName?.toString() == instrumentation.targetContext.packageName &&
                        !bounds.isEmpty && position.x >= bounds.left && position.y >= bounds.top &&
                        position.x + size.width <= bounds.right && position.y + size.height <= bounds.bottom
                } finally {
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            }?.id
        } finally {
            windows.forEach { @Suppress("DEPRECATION") it.recycle() }
        }
    }

    fun login() = step("login ready") {
        configuration()
        // Local data now cold-starts at FeedList. Explicitly navigate to Login for
        // callers that exercise the login/offline/GitHub entry contracts; never clear data.
        await { !absent(hasContentDescription("选择订阅")) ||
            !absent(hasText("登录 NewsBlur · 支持免费账号")) }
        if (!absent(hasContentDescription("选择订阅"))) {
            listLoginControl()
        }
        await { absent(hasContentDescription("选择订阅")) &&
            absent(hasContentDescription("返回文章列表")) &&
            compose.onAllNodesWithText("登录 NewsBlur · 支持免费账号").fetchSemanticsNodes().size == 1 }
        settled(compose.onNode(hasScrollAction() and
            hasAnyDescendant(hasText("登录 NewsBlur · 支持免费账号"))))
        val heading = compose.onNodeWithText("登录 NewsBlur · 支持免费账号")
        heading.performScrollTo()
        settled(heading)
    }

    private fun listLoginControl() {
        for (label in listOf("更多", "同步详情", "登录 NewsBlur")) {
            val control = compose.onNodeWithContentDescription(label)
            // DropdownMenu is a Popup window, not the main Activity or an AlertDialog.
            // Do not require main-window focus while its actual menu item is displayed.
            if (label == "同步详情") await { displayed(control) } else settled(control)
            control.performTouchInput { click() }
        }
    }

    fun list() = step("article list ready (outgoing screen removed)") {
        await { absent(hasContentDescription("直接阅读")) && absent(hasContentDescription("返回文章列表")) }
        settled(compose.onNodeWithContentDescription("选择订阅"))
        settled(compose.onNode(hasScrollToNodeAction()))
        // Room's initial empty state may outlive the navigation animation. The fixture
        // is inserted before launch; do not attempt a lazy-list search until it loads.
        await { absent(hasText("暂无文章。添加订阅后刷新即可阅读。")) }
    }

    private fun showReaderControls() {
        if (!absent(hasContentDescription("显示阅读工具"))) {
            val show = compose.onNodeWithContentDescription("显示阅读工具")
            settled(show)
            show.performTouchInput { click() }
        }
    }

    fun reader(title: String) = step("reader ready (outgoing list removed)") {
        await { absent(hasContentDescription("选择订阅")) && absent(hasContentDescription("直接阅读")) }
        showReaderControls()
        settled(compose.onNodeWithContentDescription("返回文章列表"))
        selectedTitle = title
        await {
            var matches = false
            scenario.onActivity { activity ->
                val web = views(activity.window.decorView).filterIsInstance<WebView>().singleOrNull()
                matches = web != null && web.title == title && web.progress == 100 && web.contentHeight > 0
            }
            matches
        }
        settled(compose.onNodeWithTag("reader-content"))
    }

    fun scrollAndClick(text: String) = step("scroll and touch $text") {
        val node = compose.onNodeWithContentDescription(text)
        scrollControlIntoView(compose, scenario, node)
        settled(node)
        node.performTouchInput { click() }
    }

    fun article(title: String, click: Boolean) = step("scroll to fixture${if (click) " and touch" else " after back"}") {
        list()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(title))
        val node = compose.onNodeWithText(title, useUnmergedTree = true)
        settled(node)
        if (click) node.performTouchInput { click() }
    }

    /** WebView has its own rendering lifecycle, outside Compose's idling. */
    fun body(bodyText: String) = step("cached WebView body ready") {
        val expectedTitle = selectedTitle
        var expectedWeb: WebView? = null
        val fixtureProse = "河边的图书馆今天依然安静，读者沿着文字寻找昨天的足迹。 The quiet library beside the river keeps a place for every reader, even when the network is unavailable."
        val fixtureIndex = (0..53).firstOrNull { index ->
            bodyText == "READER-$index-P000 $fixtureProse" &&
                expectedTitle?.startsWith("FLUXA-READER-RESERVED ${index.toString().padStart(2, '0')} ") == true
        }
        val paragraphPattern = fixtureIndex?.let {
            Regex("READER-$it-P\\d{3} ${Regex.escape(fixtureProse)}")
        }
        fun guardSelectedWeb(stage: String, requireForward: Boolean = false) {
            scenario.onActivity { activity ->
                val web = views(activity.window.decorView).filterIsInstance<WebView>().singleOrNull()
                val identity = System.identityHashCode(web)
                val details = "stage=$stage namespace=${fixtureIndex?.let { "READER-$it" } ?: "other"} " +
                    "expectedIdentity=${System.identityHashCode(expectedWeb)} actualIdentity=$identity " +
                    "expectedTitle=$expectedTitle actualTitle=${web?.title} progress=${web?.progress} scrollY=${web?.scrollY}"
                Log.i("FluxaDeviceFlow", "Heading guard $details")
                check(web != null && web === expectedWeb && selectedTitle == expectedTitle &&
                    web.title == expectedTitle && web.progress == 100) {
                    "Selected native WebView identity/title/progress mismatch: $details"
                }
                if (requireForward) check(web.canScrollVertically(1)) {
                    "Heading swipe blocked: selected native WebView cannot scroll forward; refusing boundary next-article navigation: $details"
                }
            }
        }
        await {
            var ready = false
            scenario.onActivity { activity ->
                val web = views(activity.window.decorView).filterIsInstance<WebView>().singleOrNull()
                val visible = Rect()
                ready = web != null && web.isShown && web.getGlobalVisibleRect(visible) &&
                    visible.width() > 0 && visible.height() > 0 && web.progress == 100 &&
                    web.contentHeight > 0 && web.title == expectedTitle
                if (ready) expectedWeb = web
            }
            ready
        }
        // Wait for native visual readiness before inspecting Chromium's text subtree.
        // Do not enable JavaScript or change the WebView client.
        val drawn = AtomicBoolean(false)
        scenario.onActivity { activity ->
            views(activity.window.decorView).filterIsInstance<WebView>().single()
                .postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { drawn.set(true) }
                })
        }
        await { drawn.get() }
        val probeArgs = InstrumentationRegistry.getArguments()
        val probeEnabled = probeArgs.getString("readerProviderProbe") == "true"
        val probeAt = probeArgs.getString("readerProviderProbeAt") ?: "0"
        if (probeEnabled) require(probeAt in listOf("0", "1", "2")) { "readerProviderProbeAt must be 0, 1 or 2" }
        if (!providerProbeDone && probeEnabled && bodyText.startsWith("READER-$probeAt-P000 ")) {
            providerProbeDone = true
            // Diagnostic failures must not replace the original body assertion/failure screenshot.
            try { readerProviderProbe(bodyText) } catch (failure: Exception) {
                Log.i("FluxaReaderProvider", "probe failed class=${failure.javaClass.name}")
            }
        }
        // Demand a real Chromium text subtree before attempting to reveal an offscreen
        // paragraph. Scrolling must not hide a missing accessibility provider/subtree.
        if (probeArgs.getString("readerProviderReadySync") == "true") {
            val started = android.os.SystemClock.uptimeMillis()
            var ready = false
            var identity = 0
            var expected: WebView? = null
            var polls = 0
            var initialNull = false
            do {
                scenario.onActivity { activity ->
                    val web = views(activity.window.decorView).filterIsInstance<WebView>().single()
                    check(web.title == selectedTitle)
                    if (expected == null) expected = web else check(web === expected)
                    identity = System.identityHashCode(web)
                    ready = web.accessibilityNodeProvider != null
                    if (polls == 0) initialNull = !ready
                }
                polls++
                if (ready) break
                Thread.sleep(50)
            } while (android.os.SystemClock.uptimeMillis() - started < 10_000)
            Log.i("FluxaReaderProvider", "readerProviderReadySync identity=$identity expectedTitle=$selectedTitle initialNull=$initialNull polls=$polls nonnull=$ready elapsed=${android.os.SystemClock.uptimeMillis() - started}")
            check(ready) { "Selected native WebView provider readiness timed out" }
        }
        if (paragraphPattern != null) {
            // Header/metadata TextViews are not body readiness. Require exact known
            // prose in this article's namespace from the native accessibility subtree.
            await {
                guardSelectedWeb("same-fixture paragraph readiness")
                val ready = accessibilityText().any { paragraphPattern.matches(it) }
                guardSelectedWeb("after same-fixture paragraph sample")
                ready
            }
        } else {
            await { accessibilityText().isNotEmpty() }
        }
        var moves = 0
        while (accessibilityText().none { it.contains(bodyText) } && moves < 12) {
            // A short article can finish drawing before Chromium publishes its
            // accessible paragraphs. Await the exact body below instead of issuing
            // a boundary swipe (which could navigate to another article).
            var canScrollForward = false
            scenario.onActivity { canScrollForward = expectedWeb?.canScrollVertically(1) == true }
            if (!canScrollForward) break
            guardSelectedWeb("before heading swipe ${moves + 1}", requireForward = true)
            compose.onNodeWithTag("reader-content").performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(width / 2f, height * .7f),
                    androidx.compose.ui.geometry.Offset(width / 2f, height * .45f), 400)
            }
            moves++
            compose.waitForIdle()
        }
        guardSelectedWeb("before final paragraph await")
        await {
            guardSelectedWeb("final paragraph await")
            val matched = accessibilityText().any { it.contains(bodyText) }
            guardSelectedWeb("after final paragraph sample")
            matched
        }
        Log.i("FluxaDeviceFlow", "Actual body text matched after $moves bounded heading swipes")
        var previous: Triple<Int, Int, Int>? = null
        var stableSince = android.os.SystemClock.uptimeMillis()
        await {
            var sample: Triple<Int, Int, Int>? = null
            scenario.onActivity { activity ->
                views(activity.window.decorView).filterIsInstance<WebView>().singleOrNull()?.let {
                    sample = Triple(it.height, it.contentHeight, it.scrollY)
                }
            }
            if (sample == null || sample != previous) stableSince = android.os.SystemClock.uptimeMillis()
            previous = sample
            sample != null && android.os.SystemClock.uptimeMillis() - stableSince >= 500
        }
        showReaderControls()
    }

    /** Opt-in cache comparison; payload restricted to known fixture 0/1/2 paragraph zero. */
    private fun readerProviderProbe(bodyText: String) {
        val article = InstrumentationRegistry.getArguments().getString("readerProviderProbeAt") ?: "0"
        require(article in listOf("0", "1", "2"))
        val marker = "READER-$article-P000"
        val paragraph = "$marker 河边的图书馆今天依然安静，读者沿着文字寻找昨天的足迹。 The quiet library beside the river keeps a place for every reader, even when the network is unavailable."
        if (bodyText != paragraph || selectedTitle?.startsWith("FLUXA-READER-RESERVED ${article.padStart(2, '0')} ") != true) {
            Log.i("FluxaReaderProvider", "probe skipped: not selected known fixture")
            return
        }
        val automation = readerUiAutomation()
        val originalFlags = automation.serviceInfo.flags
        val expectedTitle = selectedTitle
        var expectedWeb: WebView? = null
        scenario.onActivity { activity ->
            expectedWeb = views(activity.window.decorView).filterIsInstance<WebView>().single()
            check(expectedWeb!!.title == expectedTitle)
            Log.i("FluxaReaderProvider", "baseline native identity=${System.identityHashCode(expectedWeb)} fixture=$article selectedTitleMatched=true")
        }
        data class Counts(val nodes: Int, val texts: Int, val exact: Int, val realParagraphs: Int)
        fun sample(label: String, refresh: Boolean): Counts {
            var nodes = 0
            var texts = 0
            var exact = 0
            val realParagraphs = mutableSetOf<String>()
            var refreshed = 0
            var refreshFailed = 0
            val root = automation.rootInActiveWindow
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo, viewport: Rect?) {
                if (refresh) { if (node.refresh()) refreshed++ else refreshFailed++ }
                val bounds = Rect().also(node::getBoundsInScreen)
                val web = viewport ?: bounds.takeIf { node.className?.toString() == "android.webkit.WebView" }
                if (web != null) nodes++
                nativeWebText(node, viewport, false)?.let { text ->
                    texts++
                    if ((0..100).any { p -> text == paragraph.replace(marker,
                            "READER-$article-P${p.toString().padStart(3, '0')}") }) realParagraphs.add(text)
                    if (text == paragraph) {
                        exact++
                        Log.i("FluxaReaderProvider", "$label exactMarker=$marker bounds=$bounds paragraph=$text")
                    }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                    try { visit(child, web) } finally { @Suppress("DEPRECATION") child.recycle() }
                }
            }
            try { root?.let { visit(it, null) } } finally { @Suppress("DEPRECATION") root?.recycle() }
            val counts = Counts(nodes, texts, exact, realParagraphs.size)
            Log.i("FluxaReaderProvider", "$label uptime=${android.os.SystemClock.uptimeMillis()} flags=${automation.serviceInfo.flags} root=${root != null} $counts refreshed=$refreshed refreshFailed=$refreshFailed")
            return counts
        }
        Log.i("FluxaReaderProvider", "originalFlags=$originalFlags includeNotImportant=${originalFlags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS != 0}")
        val baseline = sample("baseline", false)
        val refreshed = sample("after-node-refresh", true)
        // Public API 34+: accessibility cache only, never application data.
        // https://developer.android.com/reference/android/app/UiAutomation#clearCache()
        // Keep flags unchanged: do not confound the cache comparison with the old flag experiment.
        if (Build.VERSION.SDK_INT >= 34) {
            val cleared = automation.clearCache()
            Log.i("FluxaReaderProvider", "clearCache result=$cleared api=${Build.VERSION.SDK_INT}")
            val afterClear = sample("after-clear-cache-refreshed", true)
            Log.i("FluxaReaderProvider", "cacheObservation marker=$marker baseline=$baseline refreshed=$refreshed afterClear=$afterClear; temporal comparison only, not proof of cause")
        } else {
            Log.i("FluxaReaderProvider", "clearCache skipped api=${Build.VERSION.SDK_INT} requires=34 baseline=$baseline refreshed=$refreshed")
        }
        // One diagnostic action on the known virtual document, never a body retry.
        val beforeFocus = sample("before-document-focus", true)
        var beforeY = 0
        scenario.onActivity { beforeY = checkNotNull(expectedWeb).scrollY }
        var actionResult: Boolean? = null
        val focusRoot = automation.rootInActiveWindow
        fun focusDocument(node: android.view.accessibility.AccessibilityNodeInfo, inWeb: Boolean) {
            node.refresh()
            val web = node.className?.toString() == "android.webkit.WebView"
            if (actionResult == null && inWeb && web && node.text?.toString() == expectedTitle) {
                actionResult = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                try { focusDocument(child, inWeb || web) }
                finally { @Suppress("DEPRECATION") child.recycle() }
            }
        }
        try { focusRoot?.let { focusDocument(it, false) } }
        finally { @Suppress("DEPRECATION") focusRoot?.recycle() }
        val afterFocus = sample("after-document-focus", true)
        scenario.onActivity {
            val web = checkNotNull(expectedWeb)
            Log.i("FluxaReaderProvider", "documentFocus action=$actionResult (null=not found) before=$beforeFocus after=$afterFocus scrollYBefore=$beforeY scrollYAfter=${web.scrollY}")
            check(web.title == expectedTitle)
            web.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        sample("after-single-content-changed-event", true)
        scenario.onActivity { Log.i("FluxaReaderProvider", "after-event scrollY=${checkNotNull(expectedWeb).scrollY}") }
        Log.i("FluxaReaderProvider", "unchangedFlags=${automation.serviceInfo.flags} expected=$originalFlags")
        val probe = NativeDocumentSnapshotProbe()
        scenario.onActivity { activity ->
            val web = views(activity.window.decorView).filterIsInstance<WebView>().single()
            check(web === expectedWeb && web.title == expectedTitle)
            val screen = IntArray(2).also(web::getLocationOnScreen)
            Log.i("FluxaReaderProvider", "native sameWeb=true identity=${System.identityHashCode(web)} fixture=$article selectedTitleMatched=true viewport=${web.width}x${web.height}@${screen.toList()} scrollY=${web.scrollY} contentHeight=${web.contentHeight} progress=${web.progress}")
            web.onProvideVirtualStructure(probe.sink)
        }
        val complete = probe.awaitCompletion()
        if (complete) {
            val entries = probe.flatten()
            val matches = entries.filter { it.text == paragraph }
            Log.i("FluxaReaderProvider", "virtualStructure complete=true nodes=${entries.size} texts=${entries.count { !it.text.isNullOrBlank() }} exactMarker=$marker count=${matches.size} bounds=${matches.map { it.bounds }}")
            matches.take(1).forEach { Log.i("FluxaReaderProvider", "virtualStructure paragraph=${it.text}") }
        } else Log.i("FluxaReaderProvider", "virtualStructure complete=false")
    }

    /** Actual WebView DOM descendants: text or provider-native leaf descriptions, never host labels. */
    fun accessibilityText(visibleOnly: Boolean = false): List<String> {
        val root = readerUiAutomation().rootInActiveWindow ?: return emptyList()
        fun collect(node: android.view.accessibility.AccessibilityNodeInfo, webBounds: Rect?): List<String> {
            // UiAutomation can return a cached, incomplete provider tree. Refresh even
            // the root before reading any fields; failed nodes wait for the next sample.
            if (!node.refresh()) return emptyList()
            val bounds = Rect().also(node::getBoundsInScreen)
            // Keep the outer native WebView viewport, not a nested document's bounds.
            val viewport = webBounds ?: bounds.takeIf { node.className?.toString() == "android.webkit.WebView" }
            val result = mutableListOf<String>()
            nativeWebText(node, webBounds, visibleOnly)?.let(result::add)
            for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                try { result += collect(child, viewport) }
                finally { @Suppress("DEPRECATION") child.recycle() }
            }
            return result
        }
        return try { collect(root, null) } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }

    private fun window(): String {
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val location = IntArray(2).also { decor.getLocationOnScreen(it) }
            val visible = Rect().also { decor.getWindowVisibleDisplayFrame(it) }
            val metrics = if (Build.VERSION.SDK_INT >= 30)
                activity.windowManager.currentWindowMetrics.bounds.toString() else "unavailable before API 30"
            lastWindow = "API=${Build.VERSION.SDK_INT}, configuration=${activity.resources.configuration}, " +
                "orientation=${activity.resources.configuration.orientation}, fontScale=${activity.resources.configuration.fontScale}, " +
                "window=$metrics, decor=${decor.width}x${decor.height}@${location.toList()}, " +
                "visibleFrame=$visible, focus=${decor.hasWindowFocus()}, rotation=${decor.display?.rotation}, " +
                "webViews=" + views(decor).filterIsInstance<WebView>().map {
                    "${it.width}x${it.height}, shown=${it.isShown}, title=${it.title}, progress=${it.progress}, contentHeight=${it.contentHeight}, scrollY=${it.scrollY}, importance=${it.importantForAccessibility}, description=${it.contentDescription}, provider=${WebView.getCurrentWebViewPackage()}"
                }.toList()
        }
        return lastWindow
    }

    private fun accessibilityDiagnostics(): String {
        val automation = readerUiAutomation()
        val root = automation.rootInActiveWindow ?: return "accessibility root=null; service=${automation.serviceInfo}"
        val lines = mutableListOf<String>()
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int, inWeb: Boolean) {
            val web = inWeb || node.className?.toString() == "android.webkit.WebView"
            if (lines.size >= 160) return
            val bounds = Rect().also(node::getBoundsInScreen)
            // Only this app; include ancestors and actual WebView descendants, not other apps.
            if (node.packageName?.toString() == InstrumentationRegistry.getInstrumentation().targetContext.packageName &&
                (web || depth < 8)) {
                lines += "depth=$depth class=${node.className} children=${node.childCount} visible=${node.isVisibleToUser} " +
                    "bounds=$bounds text=${node.text?.take(240)} description=${node.contentDescription?.take(160)}"
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let {
                try { visit(it, depth + 1, web) } finally { @Suppress("DEPRECATION") it.recycle() }
            }
        }
        try { visit(root, 0, false) } finally { @Suppress("DEPRECATION") root.recycle() }
        return "actualAccessibility (limit 160):\n${lines.joinToString("\n")}"
    }

    private fun diagnostics(): String {
        val bounds = runCatching { window() }.getOrElse { "$lastWindow; window capture failed: $it" }
        val semantics = runCatching { compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString() }
            .getOrElse { "semantics capture failed: $it" }
        val args = InstrumentationRegistry.getArguments()
        return "phase=$phase, requestedOrientation=${args.getString("expectedOrientation")}, " +
            "requestedFontScale=${args.getString("expectedFontScale")}\n$bounds\n$semantics\n" +
            runCatching { accessibilityDiagnostics() }.getOrElse { "accessibility capture failed: $it" }
    }
}
