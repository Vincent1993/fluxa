package com.fluxa.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxa.app.data.directrss.stableHash
import com.fluxa.app.data.local.ReaderPreferences
import com.fluxa.app.data.local.ReaderSettings
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.article.ArticleContent
import com.fluxa.app.ui.article.ReaderPage
import com.fluxa.app.ui.article.ReaderWebView
import com.fluxa.app.ui.article.readerHtml
import com.fluxa.app.ui.components.IconAction
import com.fluxa.app.ui.theme.FluxaTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Diagnostic matrix ONLY. No ComposeTestRule, virtual clock, provider manipulation or AX warmup.
 * A case PASS never substitutes for ReaderTalkBackStage1cTest or establishes a unique root cause.
 */
@Suppress("DEPRECATION")
class ReaderAxCauseProbeTest {
    private val owner = "fluxa-stage1c-owned-api36"
    private val tag = "ReaderAxCause"
    private val paragraph = "STAGE1C-BODY-0 Owned riverside paragraph for native accessibility focus, not a heading or image alternative."
    private enum class Case { HTML_NATIVE, SUBCLASS_NATIVE, HTML_COMPOSE, ARTICLE_CONTENT }

    @Test fun case1ReaderHtmlOnOrdinaryNativeWebView() = runCase(Case.HTML_NATIVE)
    @Test fun case2ReaderHtmlOnNativeReaderWebViewSubclass() = runCase(Case.SUBCLASS_NATIVE)
    @Test fun case3ReaderHtmlThroughRecomposingAndroidView() = runCase(Case.HTML_COMPOSE)
    @Test fun case4ProductionArticleContentOnRealChoreographer() = runCase(Case.ARTICLE_CONTENT)

    private fun body(index: Int) = paragraph.replace("BODY-0", "BODY-$index")
    private fun article(index: Int) = Article(
        "owned-$index", "Owned AX heading $index", "Owned source", Instant.EPOCH, false, false,
        "<p>${body(index)}</p>" + (1..30).joinToString("") { "<p>Owned paragraph $it reading beyond the header.</p>" },
        readLater = true
    )

    private fun runCase(case: Case) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("stage1cOwner") == owner)
        check(Build.VERSION.SDK_INT == 36)
        val context = instrumentation.targetContext
        check(File(context.filesDir, "$owner.marker").readText().trim() == owner)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        requireBoundTalkBack(context, automation, case)
        val run = UUID.randomUUID().toString()
        val preferenceName = "$owner-cause-$run"
        var scope: CoroutineScope? = null
        var reader: ReaderPreferences? = null
        try {
            // Only the full production case needs persistence; never instantiate app/DB/auth stores.
            if (case == Case.ARTICLE_CONTENT) {
                val ownedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                scope = ownedScope
                val owned = object : ContextWrapper(context) {
                    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                        super.getSharedPreferences(preferenceName, mode)
                }
                reader = ReaderPreferences(owned, ownedScope).also { store ->
                    runBlocking {
                        withTimeout(5_000) {
                            store.ready.first { it }
                            check(store.freeze(listOf("owned-0", "owned-1"), "owned-0"))
                        }
                    }
                }
            }
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.awaitStableHost() // Same existing native host stabilization; no new warmup.
                val fixture = Fixture(case, run, scenario, reader)
                val nodes = NativeNodes(automation, case)
                try {
                    fixture.install()
                    fixture.ready(0, 0)
                    focusBody(nodes, fixture, 0)
                    for ((label, index) in listOf("下一篇" to 1, "上一篇" to 0)) {
                        val before = fixture.snapshot()
                        var control: AccessibilityNodeInfo? = null
                        try {
                            val button = nodes.awaitNode { node, _ ->
                                node.contentDescription?.toString() == label && node.isVisibleToUser &&
                                    node.isEnabled && node.isClickable
                            }
                            control = button
                            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                            nodes.awaitNode { node, _ ->
                                node == button && node.isVisibleToUser && node.isAccessibilityFocused
                            }.recycle()
                            var committed: Commit? = null
                            try {
                                val event = automation.executeAndWaitForEvent({
                                    assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                                    fixture.ready(index, before.document)
                                    committed = fixture.snapshot()
                                    val current = checkNotNull(committed)
                                    assertSame("Same actual View object", before.view, current.view)
                                    assertEquals("Same public native host", before.host, current.host)
                                    assertTrue(current.document > before.document)
                                    assertNotEquals("Distinct native history per document", before.url, current.url)
                                }, { event ->
                                    val current = committed
                                    val source = event.source
                                    try {
                                        current != null && current.document > before.document &&
                                            event.packageName?.toString() == context.packageName &&
                                            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                                            source == current.host && event.eventTime >= current.at
                                    } finally { source?.recycle() }
                                }, 5_000)
                                try {
                                    Log.i(tag, "$case $label postCommitEvent=${event.eventType} time=${event.eventTime} commitEpoch=${checkNotNull(committed).document}")
                                } finally { event.recycle() }
                                // Exactly once per qualified transition, outside every poll.
                                Log.i(tag, "$case $label oneObserverCacheClear=${automation.clearCache()}")
                                focusBody(nodes, fixture, index)
                            } finally { committed?.host?.recycle() }
                        } finally { control?.recycle(); before.host.recycle() }
                    }
                    reader?.let { assertEquals(listOf("owned-0", "owned-1"), it.session.value.ids) }
                    requireBoundTalkBack(context, automation, case)
                    Log.i(tag, "$case PASS both native turns; diagnostic ONLY, strict original remains independent")
                } catch (failure: Throwable) {
                    fixture.logState("failure")
                    nodes.dumpFailure()
                    throw failure
                } finally { fixture.dispose() }
            }
        } finally {
            try {
                scope?.let { ownedScope -> runBlocking { ownedScope.coroutineContext[Job]?.cancelAndJoin() } }
            } finally {
                if (scope != null) context.deleteSharedPreferences(preferenceName)
            }
        }
    }

    private data class Commit(val view: WebView, val host: AccessibilityNodeInfo, val document: Long, val at: Long, val url: String)

    private inner class Fixture(
        val case: Case,
        val run: String,
        val scenario: ActivityScenario<ComponentActivity>,
        val reader: ReaderPreferences?
    ) {
        private var selected by mutableIntStateOf(0)
        private var page: ReaderPage? = null
        private var web: WebView? = null
        private var composeHost: ComposeView? = null
        private var previous: ImageButton? = null
        private var next: ImageButton? = null
        private var loaded = -1
        private var generation = 0L
        private var committed = 0L
        private var committedAt = Long.MAX_VALUE
        private var expectedUrl: String? = null
        private var disposed = false
        private val production get() = case == Case.ARTICLE_CONTENT

        private fun select(target: Int): Boolean {
            if (target !in 0..1 || target == selected || disposed) return false
            if (production) {
                if (page?.retire() == false) return false
            } else if (committed != generation) return false
            selected = target
            previous?.isEnabled = target > 0
            next?.isEnabled = target < 1
            // CASE3 loads only from AndroidView.update after the selected state recomposes.
            if (!production && case != Case.HTML_COMPOSE) load(target)
            return true
        }

        fun install() = scenario.onActivity { activity ->
            if (production) {
                activity.setContent {
                    FluxaTheme {
                        Column(Modifier.fillMaxSize()) {
                            ArticleContent(article(selected), ReaderSettings(), checkNotNull(reader), Modifier.weight(1f),
                                selected > 0, selected < 1, {}, {}, { direction -> select(selected + direction) }, { page = it })
                            Row {
                                IconAction("上一篇", Icons.Outlined.ArrowUpward, { select(0) }, enabled = selected > 0)
                                IconAction("下一篇", Icons.Outlined.ArrowDownward, { select(1) }, enabled = selected < 1)
                            }
                        }
                    }
                }
            } else {
                val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                if (case == Case.HTML_COMPOSE) {
                    val host = ComposeView(activity)
                    composeHost = host
                    host.setContent {
                        val index = selected // Observable read in composition, not an external raw load.
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = { createWeb(it) },
                            update = { view -> assertSame(web, view); load(index) })
                    }
                    root.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
                } else root.addView(createWeb(activity), LinearLayout.LayoutParams(-1, 0, 1f))
                val controls = LinearLayout(activity)
                val size = (48 * activity.resources.displayMetrics.density).toInt()
                previous = ImageButton(activity).apply {
                    setImageResource(android.R.drawable.ic_media_previous)
                    contentDescription = "上一篇"; isEnabled = false
                    setOnClickListener { select(0) }
                }
                next = ImageButton(activity).apply {
                    setImageResource(android.R.drawable.ic_media_next)
                    contentDescription = "下一篇"
                    setOnClickListener { select(1) }
                }
                controls.addView(previous, LinearLayout.LayoutParams(size, size))
                controls.addView(next, LinearLayout.LayoutParams(size, size))
                root.addView(controls)
                activity.setContentView(root)
                if (case != Case.HTML_COMPOSE) load(0)
            }
        }

        private fun createWeb(context: Context): WebView {
            check(web == null) { "Unexpected host recreation" }
            return (if (case == Case.SUBCLASS_NATIVE) ReaderWebView(context) else WebView(context)).apply {
                web = this
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                settings.blockNetworkLoads = true
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.textZoom = (context.resources.configuration.fontScale * 100).toInt()
                // No alpha assignment, foreground, snapshots, ReaderPage or render in cases 1..3.
                setOnTouchListener { _, _ -> committed != generation }
            }
        }

        private fun load(index: Int) {
            if (disposed || loaded == index) return
            loaded = index
            val view = checkNotNull(web)
            val document = ++generation
            val url = "https://www.newsblur.com/fluxa-cause/$run/$document/"
            expectedUrl = url
            val dark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val html = readerHtml(article(index), ReaderSettings(), dark)
            view.stopLoading()
            var requested = false
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldOverrideUrlLoading(view: WebView, url: String) = true
                override fun onPageFinished(current: WebView, finishedUrl: String?) {
                    if (disposed || current !== view || generation != document || finishedUrl != url || requested) return
                    requested = true
                    current.postVisualStateCallback(document, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if (disposed || generation != document || requestId != document || current.url != url) return
                            committed = document
                            committedAt = SystemClock.uptimeMillis()
                            // Public real post-visual notification only; NEVER body publication proof.
                            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                            event.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
                            event.setSource(current)
                            current.sendAccessibilityEventUnchecked(event)
                            Log.i(tag, "$case host=${System.identityHashCode(current)} probeCommit=$document commitAt=$committedAt")
                        }
                    })
                }
            }
            view.post {
                if (!disposed && generation == document) {
                    check(view.isAttachedToWindow)
                    view.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
                }
            }
        }

        private fun current(activity: ComponentActivity): WebView {
            val actual = views(activity.window.decorView).filterIsInstance<WebView>().single()
            if (web == null) web = actual else assertSame("Stable native host", web, actual)
            return actual
        }
        private fun document(view: WebView) = if (production) (view as ReaderWebView).committedDocument else committed
        private fun commitTime(view: WebView) = if (production) (view as ReaderWebView).committedAt else committedAt

        fun ready(index: Int, after: Long) {
            var captured: WebView? = null
            var epoch = 0L
            await("$case visual commit $index") {
                var ready = false
                scenario.onActivity { activity ->
                    val candidate = views(activity.window.decorView).filterIsInstance<WebView>().singleOrNull()
                    if (candidate != null) {
                        val view = current(activity)
                        assertEquals(1f, view.alpha, 0f)
                        val doc = document(view)
                        val urlMatches = if (production) view.url == "https://www.newsblur.com/fluxa-reader/$doc/" else view.url == expectedUrl
                        ready = selected == index && doc > after && urlMatches &&
                            (if (production) !(view as ReaderWebView).documentCovered else committed == generation) &&
                            view.title == article(index).title && view.progress == 100 && view.isAttachedToWindow &&
                            view.isShown && view.width > 0 && view.height > 0 && view.contentHeight > 0
                        if (ready) { captured = view; epoch = doc }
                    }
                }
                ready
            }
            // Same additional actual native barrier as original ready(); no synthetic commit counter in CASE4.
            val visual = AtomicBoolean()
            scenario.onActivity {
                checkNotNull(captured).postVisualStateCallback(epoch, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (!disposed && requestId == epoch && document(checkNotNull(captured)) == epoch) visual.set(true)
                    }
                })
            }
            await("$case extra postVisualStateCallback") { visual.get() }
            scenario.onActivity { activity ->
                val view = current(activity)
                assertSame(captured, view)
                assertEquals(epoch, document(view))
                assertEquals(article(index).title, view.title)
                assertEquals(1f, view.alpha, 0f)
                assertEquals((view.resources.configuration.fontScale * 100).toInt(), view.settings.textZoom)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, view.importantForAccessibility)
                assertTrue(view.settings.blockNetworkLoads)
                assertFalse(view.settings.javaScriptEnabled)
                assertFalse(view.settings.allowFileAccess)
                assertFalse(view.settings.allowContentAccess)
                assertFalse(view.settings.domStorageEnabled)
                if (production) assertFalse((view as ReaderWebView).documentCovered)
            }
            logState("ready bodyHash=${stableHash(body(index))}")
        }

        fun snapshot(): Commit {
            var result: Commit? = null
            scenario.onActivity { activity ->
                val view = current(activity)
                result = Commit(view, view.createAccessibilityNodeInfo(), document(view), commitTime(view), checkNotNull(view.url))
            }
            return checkNotNull(result)
        }

        fun logState(label: String) {
            scenario.onActivity { activity ->
                val view = views(activity.window.decorView).filterIsInstance<WebView>().singleOrNull()
                Log.i(tag, "$case $label host=${view?.let { System.identityHashCode(it) }} commitEpoch=${view?.let { document(it) }} " +
                    "realCommit=${(view as? ReaderWebView)?.committedDocument} commitAt=${view?.let { commitTime(it) }} " +
                    "covered=${(view as? ReaderWebView)?.documentCovered} alpha=${view?.alpha} progress=${view?.progress}")
            }
        }

        fun dispose() = scenario.onActivity { activity ->
            disposed = true
            page?.release()
            // Dispose the production composition (including its controller/lifecycle observer) before destroying the host.
            if (production) views(activity.window.decorView).filterIsInstance<ComposeView>().toList().forEach { it.disposeComposition() }
            composeHost?.disposeComposition()
            web?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                if (production) view.foreground = null
                if (view is ReaderWebView) view.disposeReader() else { view.stopLoading(); view.destroy() }
            }
            page = null
            activity.setContentView(LinearLayout(activity))
        }
    }

    private fun focusBody(nodes: NativeNodes, fixture: Fixture, index: Int) {
        val exact = body(index)
        val node = nodes.awaitNode { node, inside ->
            inside && node.className?.toString() != "android.webkit.WebView" && node.text?.toString() == exact && node.isVisibleToUser
        }
        try { assertTrue("Exact native paragraph accepts AX focus", node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) }
        finally { node.recycle() }
        val focused = nodes.awaitNode { node, inside ->
            inside && node.text?.toString() == exact && node.isVisibleToUser && node.isAccessibilityFocused
        }
        try {
            // Retention of the SAME node across actual frames; no second focus action or warmup.
            val frame = AtomicBoolean()
            fixture.scenario.onActivity { activity ->
                activity.window.decorView.postOnAnimation { activity.window.decorView.postOnAnimation { frame.set(true) } }
            }
            await("retained native focus frame") { frame.get() }
            nodes.awaitNode { node, inside ->
                inside && node == focused && node.text?.toString() == exact && node.isVisibleToUser && node.isAccessibilityFocused
            }.recycle()
        } finally { focused.recycle() }
        fixture.logState("bodyFocusedAndRetained hash=${stableHash(exact)} nativeTreeCount=${nodes.lastCount}")
    }

    private inner class NativeNodes(val automation: UiAutomation, private val case: Case) {
        var lastCount = 0; private set
        private var diagnostics = listOf<String>()
        fun awaitNode(predicate: (AccessibilityNodeInfo, Boolean) -> Boolean): AccessibilityNodeInfo {
            var found: AccessibilityNodeInfo? = null
            await("$case exact public native node") {
                lastCount = 0
                val sample = mutableListOf<String>()
                fun visit(node: AccessibilityNodeInfo, inside: Boolean, depth: Int) {
                    if (lastCount >= 2000 || depth > 40) return
                    lastCount++
                    val refreshed = node.refresh()
                    if (sample.size < 80) {
                        val text = node.text?.toString().orEmpty()
                        val bounds = Rect().also { node.getBoundsInScreen(it) }
                        val role = node.extras.getCharSequence("AccessibilityNodeInfo.roleDescription")?.toString().orEmpty().take(120)
                        sample += "class=${node.className} childCount=${node.childCount} visible=${node.isVisibleToUser} " +
                            "refresh=$refreshed textHash=${stableHash(text)} length=${text.length} bounds=$bounds role=$role"
                    }
                    if (!refreshed) return
                    if (found == null && predicate(node, inside)) found = AccessibilityNodeInfo.obtain(node)
                    val inWeb = inside || node.className?.toString() == "android.webkit.WebView"
                    for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                        try { visit(child, inWeb, depth + 1) } finally { child.recycle() }
                    }
                }
                val root = automation.rootInActiveWindow
                try { root?.let { visit(it, false, 0) } }
                finally { root?.recycle(); diagnostics = sample }
                found != null
            }
            return checkNotNull(found)
        }
        fun dumpFailure() {
            Log.e(tag, "$case nativeTreeCount=$lastCount; last observed public AX sample only, max80")
            diagnostics.take(80).forEach { Log.e(tag, "$case $it") }
        }
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            if (predicate()) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("5-second native deadline: $label")
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }

    private fun requireBoundTalkBack(context: Context, automation: UiAutomation, case: Case) {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val service = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .singleOrNull { it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback" &&
                it.resolveInfo.serviceInfo.name.endsWith(".TalkBackService") }
        check(manager.isEnabled && manager.isTouchExplorationEnabled && service != null)
        val component = "com.google.android.marvin.talkback/${service.resolveInfo.serviceInfo.name}"
        val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("dumpsys activity services $component")).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= 512 * 1024)
                output.write(buffer, 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        }
        check(dump.contains("TalkBackService") && dump.contains("ConnectionRecord{") &&
            dump.contains("system") && dump.contains("app=ProcessRecord{")) {
            "Enabled setting alone does not prove bound/running TalkBack"
        }
        Log.i(tag, "$case genuine TalkBack enabled+AMS-bound verified; no service/settings writes")
    }
}
