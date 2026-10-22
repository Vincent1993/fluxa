package com.fluxa.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxa.app.data.local.ReaderPreferences
import com.fluxa.app.data.local.ReaderSettings
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.article.ArticleContent
import com.fluxa.app.ui.article.ReaderWebView
import com.fluxa.app.ui.article.ReaderPage
import com.fluxa.app.ui.components.IconAction
import com.fluxa.app.ui.theme.FluxaTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Native production ArticleContent, not a DOM/assist proxy. Never enables services or touches real preferences. */
class ReaderTalkBackStage1cTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val owner = "fluxa-stage1c-owned-api36"
    private val paragraph = "STAGE1C-BODY-0 Owned riverside paragraph for native accessibility focus, not a heading or image alternative."

    @Test fun boundTalkBackFocusesRealParagraphAndEquivalentQueueControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit fresh API36 owner required", args.getString("stage1cOwner") == owner)
        check(Build.VERSION.SDK_INT == 36) { "Only the owned fresh Android16 device is in scope" }
        val context = instrumentation.targetContext
        check(File(context.filesDir, "$owner.marker").readText().trim() == owner) { "Root must provision the explicit synthetic ownership marker" }
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        requireBoundTalkBack(context, automation)
        val preferenceName = "$owner-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owned = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(preferenceName, mode)
        }
        val reader = ReaderPreferences(owned, scope)
        runBlocking { reader.ready.first { it }; check(reader.freeze(listOf("owned-0", "owned-1"), "owned-0")) }
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.awaitStableHost()
                var selected by mutableIntStateOf(0)
                var page: ReaderPage? = null
                fun select(target: Int): Boolean {
                    if (target !in 0..1 || target == selected || page?.retire() == false) return false
                    selected = target
                    return true
                }
                scenario.onActivity { activity -> activity.setContent {
                    FluxaTheme {
                        Column(Modifier.fillMaxSize()) {
                            // Production ArticleRoute now selects inside its existing Nav entry.
                            // Keep the same ArticleContent call site/owner and rebind its document.
                            val prose = paragraph.replace("BODY-0", "BODY-$selected")
                            val article = Article("owned-$selected", "Owned AX heading $selected", "Owned source", Instant.EPOCH,
                                false, false, "<p>$prose</p>" + (1..30).joinToString("") { "<p>Owned paragraph $it reading beyond the header.</p>" }, readLater = true)
                            ArticleContent(article, ReaderSettings(), reader, Modifier.weight(1f), selected > 0, selected < 1,
                                {}, {}, { direction -> select(selected + direction) }, { page = it })
                            Row {
                                IconAction("上一篇", Icons.Outlined.ArrowUpward, { select(0) }, enabled = selected > 0)
                                IconAction("下一篇", Icons.Outlined.ArrowDownward, { select(1) }, enabled = selected < 1)
                            }
                        }
                    }
                } }
                ready(scenario, "Owned AX heading 0")
                val body = awaitNode(automation) { node, inWeb -> inWeb && node.text?.toString() == paragraph &&
                    node.className?.toString() != "android.webkit.WebView" && node.isVisibleToUser }
                try {
                    assertTrue("Native paragraph must accept AX focus", body.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                } finally { body.recycle() }
                val focused = awaitNode(automation) { node, inWeb -> inWeb && node.text?.toString() == paragraph && node.isAccessibilityFocused }
                focused.recycle()
                val frame = checkNotNull(automation.takeScreenshot()) { "Native focused-body frame unavailable" }
                val screenshot = File(context.filesDir, "$owner-focused-body-${UUID.randomUUID()}.png")
                try { screenshot.outputStream().use { check(frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { frame.recycle() }
                android.util.Log.i("Stage1cAX", "Raw focused-body frame=${screenshot.name}; root must inspect pixels; no audio listening claim")
                // Equal controls are native service-visible actions, not Compose performClick or adb swipe.
                for (label in listOf("下一篇", "上一篇")) {
                    var outgoingHost: AccessibilityNodeInfo? = null
                    var outgoingDocument = 0L
                    scenario.onActivity { activity ->
                        val current = views(activity.window.decorView).filterIsInstance<ReaderWebView>().single()
                        outgoingHost = current.createAccessibilityNodeInfo()
                        outgoingDocument = current.committedDocument
                    }
                    val nativeEvents = NativeTransitionEvents(automation, context.packageName, label, checkNotNull(outgoingHost))
                    try {
                    val control = awaitNode(automation) { node, _ -> node.contentDescription?.toString() == label && node.isEnabled }
                    var expectedHost: AccessibilityNodeInfo? = null
                    var committedAt = Long.MAX_VALUE
                    try {
                        assertTrue(control.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                        compose.waitUntil(5_000) { control.refresh() && control.isAccessibilityFocused }
                        android.util.Log.i("Stage1cAX", "transition=$label awaiting service-visible window/content event")
                        val event = automation.executeAndWaitForEvent({
                            assertTrue(control.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                            // Advance document-change Compose frames while UiAutomation is already
                            // listening, not after its synchronous event wait has timed out.
                            compose.waitForIdle()
                            ready(scenario, "Owned AX heading ${if (label == "下一篇") 1 else 0}", outgoingDocument)
                            var nativeViewIdentity = 0
                            var document = 0L
                            scenario.onActivity { activity ->
                                val current = views(activity.window.decorView).filterIsInstance<ReaderWebView>().single()
                                expectedHost = current.createAccessibilityNodeInfo()
                                nativeViewIdentity = System.identityHashCode(current)
                                document = current.committedDocument
                                committedAt = current.committedAt
                                assertTrue("A new document must commit", document > outgoingDocument)
                                assertEquals("Production queue retains the actual native host", outgoingHost, expectedHost)
                            }
                            // Bind off main: listener parent queries may need the app UI thread.
                            nativeEvents.bind(checkNotNull(expectedHost), nativeViewIdentity, document, committedAt)
                        }, { event ->
                            val source = event.source
                            try {
                                event.packageName?.toString() == context.packageName &&
                                    event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                                    source != null && source == expectedHost && event.eventTime >= committedAt
                            } finally { source?.recycle() }
                        }, 5_000)
                        android.util.Log.i("Stage1cAX", "transition=$label eventType=${event.eventType} window=${event.windowId}")
                        event.recycle()
                    } catch (failure: Throwable) {
                        scenario.onActivity { activity -> views(activity.window.decorView).filterIsInstance<WebView>().forEach { web ->
                            android.util.Log.e("Stage1cAX", "early-event-failure view=${System.identityHashCode(web)} title=${web.title} shown=${web.isShown} progress=${web.progress} height=${web.contentHeight} focus=${web.hasFocus()} provider=${web.accessibilityNodeProvider != null}")
                        } }
                        var sampled = 0
                        fun dump(node: AccessibilityNodeInfo, depth: Int) {
                            if (++sampled > 200 || depth > 25) return
                            android.util.Log.e("Stage1cAX", "early-node class=${node.className} children=${node.childCount} visible=${node.isVisibleToUser} textHash=${com.fluxa.app.data.directrss.stableHash(node.text?.toString().orEmpty())}")
                            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> try { dump(child, depth + 1) } finally { child.recycle() } }
                        }
                        automation.rootInActiveWindow?.let { root -> try { dump(root, 0) } finally { root.recycle() } }
                        throw failure
                    } finally { expectedHost?.recycle(); control.recycle() }
                    ready(scenario, "Owned AX heading ${if (label == "下一篇") 1 else 0}")
                    val expected = if (label == "下一篇") paragraph.replace("BODY-0", "BODY-1") else paragraph
                    android.util.Log.i("Stage1cAX", "transition=$label exactNewBodyHash=${com.fluxa.app.data.directrss.stableHash(expected)}")
                    // One public observer-cache control per qualified replacement event,
                    // not inside awaitNode/polling and not a production provider repair.
                    // https://developer.android.com/reference/android/app/UiAutomation#clearCache()
                    val beforeClear = exactBodySnapshot(automation, expected)
                    val cleared = automation.clearCache() // Public API34; this owned test requires API36.
                    val afterClear = exactBodySnapshot(automation, expected)
                    android.util.Log.i("Stage1cAX", "transition=$label observerCacheCleared=$cleared exactBodyBefore=$beforeClear exactBodyAfter=$afterClear")
                    scenario.onActivity { activity ->
                        val current = views(activity.window.decorView).filterIsInstance<WebView>().single()
                        // History identity is diagnostic only (null historyUrl previously made
                        // every load about:blank). Even distinct history URLs cannot establish
                        // native document/body publication. Keep the exact body/focus gate.
                        // https://developer.android.com/reference/android/webkit/WebView#loadDataWithBaseURL(java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String)
                        val provider = current.accessibilityNodeProvider
                        val focus = provider?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                        try {
                            android.util.Log.i("Stage1cAX", "transition=$label nativeView=${System.identityHashCode(current)} attached=${current.isAttachedToWindow} shown=${current.isShown} windowFocus=${current.hasWindowFocus()} inputFocus=${current.hasFocus()} provider=${provider != null} axFocusPresent=${focus != null} axFocusWindow=${focus?.windowId} axFocusTextHash=${com.fluxa.app.data.directrss.stableHash(focus?.text?.toString().orEmpty())} historyUrlHash=${com.fluxa.app.data.directrss.stableHash(current.url.orEmpty())}")
                        } finally { focus?.recycle() }
                    }
                    // Neither clearCache's result nor provider/focus presence is acceptance.
                    val nextBody = awaitNode(automation, "after-$label", onFailure = {
                        replacementMetrics(scenario, label)
                    }) { node, inWeb ->
                        inWeb && node.text?.toString() == expected && node.isVisibleToUser }
                    try {
                        assertTrue("Replacement exact body accepts real AX focus", nextBody.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                    } finally { nextBody.recycle() }
                    awaitNode(automation, "focused-after-$label") { node, inWeb ->
                        inWeb && node.text?.toString() == expected && node.isVisibleToUser && node.isAccessibilityFocused }.recycle()
                    } finally { nativeEvents.close() }
                }
                assertEquals(listOf("owned-0", "owned-1"), reader.session.value.ids)
                requireBoundTalkBack(context, automation)
            }
        } finally {
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            context.deleteSharedPreferences(preferenceName)
        }
    }

    /** Diagnostic only. No event is an acceptance proxy, cache control or focus action.
     * UiAutomation's listener also sees events while executeAndWaitForEvent is waiting.
     * Buffer at most ten recent WebView events until ready() identifies the current
     * host and committed document. Reject different hosts and pre-commit events.
     * Same-host events cannot prove document origin; exact body/focus gates still must.
     * Never guess virtual IDs.
     */
    private class NativeTransitionEvents(
        private val automation: UiAutomation,
        private val packageName: String,
        private val label: String,
        private val outgoing: AccessibilityNodeInfo
    ) : AutoCloseable {
        private data class Sample(val host: AccessibilityNodeInfo, val fromHost: Boolean, val time: Long, val detail: String)
        private val pending = ArrayList<Sample>()
        private var current: AccessibilityNodeInfo? = null
        private var viewIdentity = 0
        private var document = 0L
        private var committedAt = Long.MAX_VALUE
        private var sampled = 0
        private var closed = false
        init { automation.setOnAccessibilityEventListener { event -> observe(event) } }

        @Synchronized private fun observe(event: android.view.accessibility.AccessibilityEvent) {
            if (closed || sampled >= 10 || event.packageName?.toString() != packageName) return
            val source = event.source ?: return
            var host: AccessibilityNodeInfo? = null
            var cursor: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(source)
            try {
                // Only actual public parent links, bounded even for a malformed provider.
                var depth = 0
                while (cursor != null && depth++ < 40) {
                    val node = checkNotNull(cursor)
                    if (node.className?.toString() == "android.webkit.WebView") {
                        host?.recycle()
                        host = AccessibilityNodeInfo.obtain(node)
                    }
                    cursor = node.parent
                    node.recycle()
                }
                val ancestor = host ?: return
                if (current != null && (ancestor != current || event.eventTime < committedAt)) return
                val sample = Sample(AccessibilityNodeInfo.obtain(ancestor), source == ancestor, event.eventTime,
                    "eventTime=${event.eventTime} observedUptime=${android.os.SystemClock.uptimeMillis()} eventType=${event.eventType} contentChangeTypes=${event.contentChangeTypes} window=${event.windowId} sourceClass=${source.className} textHash=${com.fluxa.app.data.directrss.stableHash(event.text.joinToString("\n"))}")
                if (current == null) {
                    if (pending.size == 10) pending.removeAt(0).host.recycle()
                    pending += sample
                } else {
                    try { log(sample) } finally { sample.host.recycle() }
                }
            } finally { cursor?.recycle(); host?.recycle(); source.recycle() }
        }
        @Synchronized fun bind(host: AccessibilityNodeInfo, nativeViewIdentity: Int, currentDocument: Long, commitTime: Long) {
            if (closed) return
            current?.recycle()
            current = AccessibilityNodeInfo.obtain(host)
            viewIdentity = nativeViewIdentity
            document = currentDocument
            committedAt = commitTime
            android.util.Log.i("Stage1cAX", "transition=$label sameHost=${outgoing == current} document=$document commitUptime=$committedAt; pre-commit events rejected, post-commit origin not inferred")
            pending.forEach { sample ->
                try { if (sample.host == current && sample.time >= committedAt) log(sample) } finally { sample.host.recycle() }
            }
            pending.clear()
        }
        private fun log(sample: Sample) {
            if (sampled++ >= 10) return
            android.util.Log.i("Stage1cAX", "native-event transition=$label view=$viewIdentity document=$document sample=$sampled relation=${if (sample.fromHost) "host" else "real-descendant"} ${sample.detail}")
        }
        @Synchronized override fun close() {
            closed = true
            automation.setOnAccessibilityEventListener(null)
            pending.forEach { it.host.recycle() }; pending.clear()
            current?.recycle(); current = null
            outgoing.recycle()
        }
    }

    /** Single bounded native observer sample, no refresh/cache loop, no DOM/assist. */
    private fun exactBodySnapshot(automation: UiAutomation, expected: String): Boolean {
        var count = 0
        fun visit(node: AccessibilityNodeInfo, inWeb: Boolean, depth: Int): Boolean {
            if (++count > 2000 || depth > 40) return false
            if (inWeb && node.text?.toString() == expected && node.isVisibleToUser) return true
            val web = inWeb || node.className?.toString() == "android.webkit.WebView"
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { if (visit(child, web, depth + 1)) return true } finally { child.recycle() }
            }
            return false
        }
        val root = automation.rootInActiveWindow ?: return false
        return try { visit(root, false, 0) } finally { root.recycle() }
    }

    private fun requireBoundTalkBack(context: Context, automation: UiAutomation) {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val service = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .singleOrNull { it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback" &&
                it.resolveInfo.serviceInfo.name.endsWith(".TalkBackService") }
        check(manager.isEnabled && service != null) { "Actually enabled TalkBackService required, not AccessibilityMenu" }
        val component = "com.google.android.marvin.talkback/${service.resolveInfo.serviceInfo.name}"
        // AMS connection evidence in addition to enabled configuration. Read-only, bounded, never settings writes.
        val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("dumpsys activity services $component")).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= 512 * 1024) { "AMS diagnostic exceeded bound" }
                output.write(buffer, 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        }
        check(dump.contains("TalkBackService") && dump.contains("ConnectionRecord{") &&
            dump.contains("system") && dump.contains("app=ProcessRecord{")) {
            "TalkBack enabled but bound/running system connection not proven by AMS; root must inspect device service state"
        }
    }

    private fun ready(scenario: ActivityScenario<ComponentActivity>, expectedTitle: String, afterDocument: Long = 0) {
        var web: WebView? = null
        compose.waitUntil(10_000) {
            var ready = false
            scenario.onActivity { activity ->
                web = views(activity.window.decorView).filterIsInstance<WebView>().singleOrNull()
                ready = web?.let { it.title == expectedTitle && it.isShown && it.progress == 100 && it.contentHeight > 0 && it.width > 0 && it.height > 0 && (it as ReaderWebView).committedDocument > afterDocument } == true
            }; ready
        }
        val visual = AtomicBoolean()
        scenario.onActivity { checkNotNull(web).postVisualStateCallback(36L, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) { visual.set(true) }
        }) }
        compose.waitUntil(5_000) { visual.get() }
        scenario.onActivity {
            val current = views(it.window.decorView).filterIsInstance<WebView>().single()
            assertSame(web, current); assertEquals(expectedTitle, current.title)
            assertEquals(100, current.progress); assertTrue(current.contentHeight > 0)
            assertFalse(current.settings.javaScriptEnabled)
            android.util.Log.i("Stage1cAX", "Native metrics title=$expectedTitle identity=${System.identityHashCode(current)} progress=${current.progress} contentHeight=${current.contentHeight} viewport=${current.width}x${current.height} scrollY=${current.scrollY} visualCallback=true provider=${current.accessibilityNodeProvider != null}")
        }
        // This is only metric readiness. The caller must additionally prove its exact body node.
    }
    private fun replacementMetrics(scenario: ActivityScenario<ComponentActivity>, label: String) {
        scenario.onActivity { activity ->
            val web = views(activity.window.decorView).filterIsInstance<WebView>().single()
            val rect = android.graphics.Rect()
            val visible = web.getGlobalVisibleRect(rect)
            android.util.Log.e("Stage1cAX", "failure=$label currentView=${System.identityHashCode(web)} uptime=${android.os.SystemClock.uptimeMillis()} historyUrlHash=${com.fluxa.app.data.directrss.stableHash(web.url.orEmpty())} progress=${web.progress} contentHeight=${web.contentHeight} scroll=${web.scrollX},${web.scrollY} viewport=${web.width}x${web.height} globalVisible=$visible rect=$rect")
            var host: View? = web
            repeat(6) { depth ->
                val current = host ?: return@repeat
                android.util.Log.e("Stage1cAX", "failure=$label parentDepth=$depth class=${current.javaClass.name} attached=${current.isAttachedToWindow} shown=${current.isShown} visibility=${current.visibility} alpha=${current.alpha} importantAX=${current.importantForAccessibility} windowFocus=${current.hasWindowFocus()} inputFocus=${current.hasFocus()}")
                host = current.parent as? View
            }
        }
    }
    private fun awaitNode(automation: UiAutomation, label: String = "initial", onFailure: () -> Unit = {}, predicate: (AccessibilityNodeInfo, Boolean) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        var sampled = 0
        val diagnostics = ArrayList<String>()
        fun visit(node: AccessibilityNodeInfo, web: Boolean): AccessibilityNodeInfo? {
            if (++sampled > 2000) return null
            val refreshed = node.refresh()
            if (diagnostics.size < 80) {
                val text = node.text?.toString().orEmpty()
                diagnostics += "n=$sampled class=${node.className} visible=${node.isVisibleToUser} refresh=$refreshed web=$web children=${node.childCount} textLength=${text.length} textHash=${com.fluxa.app.data.directrss.stableHash(text)} owned=${text.startsWith("STAGE1C-BODY-")}"
                if (node.className?.toString() == "android.webkit.WebView" && diagnostics.size < 80) {
                    // Failure-only log buffer from the already traversed public host/document
                    // nodes. No provider-ID probes, extra refreshes or cache operations.
                    val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                    diagnostics += "nativeRoot n=$sampled document=$web window=${node.windowId} bounds=$bounds enabled=${node.isEnabled} focusable=${node.isFocusable} focused=${node.isFocused} axFocused=${node.isAccessibilityFocused} actions=${node.actionList.map { it.id }} extrasKeys=${node.extras.keySet().sorted()}"
                }
            }
            if (!refreshed) return null
            if (predicate(node, web)) return AccessibilityNodeInfo.obtain(node)
            val inWeb = web || node.className?.toString() == "android.webkit.WebView"
            for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                try { visit(child, inWeb)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        try {
            compose.waitUntil(5_000) {
                sampled = 0
                diagnostics.clear()
                val root = automation.rootInActiveWindow
                try { found = root?.let { visit(it, false) } } finally { root?.recycle() }
                found != null
            }
        } catch (failure: Throwable) {
            android.util.Log.e("Stage1cAX", "Native provider assertion failed label=$label; nodesLastSample=$sampled; no DOM/assist fallback; no speech listening claimed")
            diagnostics.forEach { android.util.Log.e("Stage1cAX", "$label $it") }
            onFailure()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            automation.takeScreenshot()?.let { frame ->
                val file = File(context.filesDir, "$owner-failure-${UUID.randomUUID()}.png")
                try { file.outputStream().use {
                    check(frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
                    android.util.Log.e("Stage1cAX", "failure=$label current-frame=${file.name}; inspect actual replacement pixels")
                } finally { frame.recycle() }
            }
            throw failure
        }
        return checkNotNull(found).also {
            val bounds = android.graphics.Rect().also(it::getBoundsInScreen)
            android.util.Log.i("Stage1cAX", "Owned native match class=${it.className} visible=${it.isVisibleToUser} focused=${it.isAccessibilityFocused} bounds=$bounds sampled=$sampled")
        }
    }
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
}
