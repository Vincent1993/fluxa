package com.fluxa.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Causal control ONLY: no ArticleContent, ReaderPage, Compose host, assist or preferences.
 * A pass cannot replace either production strict AX or media acceptance. */
class ReaderPlainWebViewAxProbeTest {
    private val owner = "fluxa-stage1c-owned-api36"
    private val bodies = listOf(
        "PLAIN-BODY-0 Owned riverside paragraph for native accessibility focus, not a heading or image alternative.",
        "PLAIN-BODY-1 Owned hillside paragraph for native accessibility focus, not a heading or image alternative."
    )

    @Test fun boundTalkBackFocusesBothDocumentsAndReturnsOnOnePlainNativeHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("stage1cOwner") == owner)
        check(Build.VERSION.SDK_INT == 36)
        val context = instrumentation.targetContext
        check(File(context.filesDir, "$owner.marker").readText().trim() == owner)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        requireBoundTalkBack(context, automation)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.awaitStableHost()
            lateinit var web: WebView
            var webCreated = false
            var generation = 0L
            var committed = 0L
            var committedAt = Long.MAX_VALUE
            var selected = -1
            var disposed = false
            val run = UUID.randomUUID().toString()
            lateinit var previous: ImageButton
            lateinit var next: ImageButton
            fun load(index: Int) {
                if (disposed || index == selected) return
                selected = index
                val document = ++generation
                val url = "https://www.newsblur.com/fluxa-plain-probe/$run/$document/"
                previous.isEnabled = index > 0
                next.isEnabled = index < 1
                web.stopLoading()
                var requested = false
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun onPageFinished(view: WebView, finishedUrl: String?) {
                        if (disposed || view !== web || generation != document || finishedUrl != url || requested) return
                        requested = true
                        view.postVisualStateCallback(document, object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                if (disposed || generation != document || requestId != document) return
                                committed = document
                                committedAt = SystemClock.uptimeMillis()
                                // Same public post-commit subtree notification as production;
                                // this event is NOT evidence that a native body node exists.
                                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                                event.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
                                event.setSource(view)
                                view.sendAccessibilityEventUnchecked(event)
                                android.util.Log.i("PlainWebViewAX", "commit=$document host=${System.identityHashCode(view)} alpha=${view.alpha} uptime=$committedAt historyMatches=${view.url == url}")
                            }
                        })
                    }
                }
                val html = """<!doctype html><html><head><title>Owned plain heading $index</title>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; form-action 'none'; base-uri 'none'">
                    <style>body{background:#fafbf8;color:#222923;font:20px sans-serif;padding:24px}p{margin:24px 0}</style>
                    </head><body><h1>Owned plain heading $index</h1><p>${bodies[index]}</p>
                    <p>Owned second paragraph, supplied inline with no external resources.</p></body></html>"""
                web.post {
                    if (!disposed && generation == document) {
                        check(web.isAttachedToWindow)
                        web.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
                    }
                }
            }
            fun ready(index: Int, after: Long = 0) {
                await("plain visual commit $index") {
                    var ready = false
                    scenario.onActivity {
                        assertEquals(1f, web.alpha, 0f)
                        ready = selected == index && committed == generation && committed > after &&
                            web.title == "Owned plain heading $index" && web.progress == 100 &&
                            web.isAttachedToWindow && web.isShown && web.width > 0 && web.height > 0 && web.contentHeight > 0
                    }
                    ready
                }
            }
            fun focusBody(index: Int) {
                val node = awaitNode(automation) { node, inside ->
                    inside && node.text?.toString() == bodies[index] && node.isVisibleToUser &&
                        node.className?.toString() != "android.webkit.WebView"
                }
                try { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) }
                finally { node.recycle() }
                awaitNode(automation) { node, inside ->
                    inside && node.text?.toString() == bodies[index] && node.isVisibleToUser && node.isAccessibilityFocused
                }.recycle()
            }
            try {
                scenario.onActivity { activity ->
                    val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    web = WebView(activity).apply {
                        settings.blockNetworkLoads = true
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.domStorageEnabled = false
                        // Default native alpha stays 1 throughout; no AX/provider overrides.
                        setOnTouchListener { _, _ -> committed != generation }
                    }
                    webCreated = true
                    root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
                    val controls = LinearLayout(activity)
                    val size = (48 * activity.resources.displayMetrics.density).toInt()
                    previous = ImageButton(activity).apply {
                        setImageResource(android.R.drawable.ic_media_previous)
                        contentDescription = "上一篇"
                        setOnClickListener { if (committed == generation) load(0) }
                    }
                    next = ImageButton(activity).apply {
                        setImageResource(android.R.drawable.ic_media_next)
                        contentDescription = "下一篇"
                        setOnClickListener { if (committed == generation) load(1) }
                    }
                    controls.addView(previous, LinearLayout.LayoutParams(size, size))
                    controls.addView(next, LinearLayout.LayoutParams(size, size))
                    root.addView(controls)
                    activity.setContentView(root)
                    load(0)
                }
                ready(0)
                focusBody(0)
                for ((label, index) in listOf("下一篇" to 1, "上一篇" to 0)) {
                    var before = 0L
                    lateinit var host: AccessibilityNodeInfo
                    scenario.onActivity { before = committed; host = web.createAccessibilityNodeInfo() }
                    val control = awaitNode(automation) { node, _ ->
                        node.contentDescription?.toString() == label && node.isVisibleToUser && node.isEnabled && node.isClickable
                    }
                    try {
                        assertTrue(control.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                        awaitNode(automation) { node, _ -> node.contentDescription?.toString() == label && node.isAccessibilityFocused }.recycle()
                        var afterCommit = Long.MAX_VALUE
                        val event = automation.executeAndWaitForEvent({
                            assertTrue(control.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                            ready(index, before)
                            scenario.onActivity {
                                assertTrue(committed > before)
                                afterCommit = committedAt
                                val current = web.createAccessibilityNodeInfo()
                                try { assertEquals("Same physical native host", host, current) }
                                finally { current.recycle() }
                            }
                        }, { event ->
                            val source = event.source
                            try {
                                event.packageName?.toString() == context.packageName &&
                                    event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                                    source == host && event.eventTime >= afterCommit
                            } finally { source?.recycle() }
                        }, 5_000)
                        try { android.util.Log.i("PlainWebViewAX", "$label postCommitEvent=${event.eventType} time=${event.eventTime}") }
                        finally { event.recycle() }
                        // Exactly one public cache control per transition, never in a poll.
                        android.util.Log.i("PlainWebViewAX", "$label oneObserverCacheClear=${automation.clearCache()}")
                        focusBody(index)
                    } finally { control.recycle(); host.recycle() }
                }
                requireBoundTalkBack(context, automation)
                android.util.Log.i("PlainWebViewAX", "Plain native control completed BOTH transitions; NOT production acceptance or speech listening")
            } finally {
                scenario.onActivity {
                    disposed = true
                    if (webCreated) {
                        (web.parent as? android.view.ViewGroup)?.removeView(web)
                        web.stopLoading(); web.destroy()
                    }
                }
            }
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

    private fun awaitNode(automation: UiAutomation, predicate: (AccessibilityNodeInfo, Boolean) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        var count = 0
        fun visit(node: AccessibilityNodeInfo, inside: Boolean, depth: Int): AccessibilityNodeInfo? {
            if (++count > 2000 || depth > 40 || !node.refresh()) return null
            if (predicate(node, inside)) return AccessibilityNodeInfo.obtain(node)
            val inWeb = inside || node.className?.toString() == "android.webkit.WebView"
            for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                try { visit(child, inWeb, depth + 1)?.let { return it } }
                finally { child.recycle() }
            }
            return null
        }
        try {
            await("exact public native node") {
                count = 0
                val root = automation.rootInActiveWindow
                try { found = root?.let { visit(it, false, 0) } }
                finally { root?.recycle() }
                found != null
            }
        } catch (failure: Throwable) {
            android.util.Log.e("PlainWebViewAX", "Native node absent; lastSampleNodes=$count; no DOM/assist fallback", failure)
            throw failure
        }
        return checkNotNull(found)
    }

    private fun requireBoundTalkBack(context: Context, automation: UiAutomation) {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val service = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .singleOrNull { it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback" &&
                it.resolveInfo.serviceInfo.name.endsWith(".TalkBackService") }
        check(manager.isEnabled && service != null)
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
    }
}
