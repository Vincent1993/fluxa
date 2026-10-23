package com.fluxa.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Failure-only causal control: native WebView, no Fluxa reader, Compose, Assist or app stores.
 * Its outcome never replaces the original acceptance failure or permits publication.
 */
class NativeWebViewPublicationProbeTest {
    @Test fun inlineDocumentsPublishBodyAcrossReloadAndHostReplacement() {
        check(InstrumentationRegistry.getArguments().getString("readerAccessibilityService") == "true")
        val automation = readerUiAutomation()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.awaitStableHost()
            var web: WebView? = null
            lateinit var root: FrameLayout
            scenario.onActivity { activity ->
                root = FrameLayout(activity)
                activity.setContentView(root)
            }
            try {
                // Alternating reload and host replacement reproduces both document paths.
                repeat(6) { document ->
                    val exact = "NATIVE-CONTROL-$document Real paragraph from the displayed inline document."
                    val url = "https://www.newsblur.com/native-publication-control/$document/"
                    val drawn = AtomicBoolean(false)
                    scenario.onActivity { activity ->
                        if (document % 2 == 0) {
                            web?.let { root.removeView(it); it.stopLoading(); it.destroy() }
                            web = WebView(activity).apply {
                                settings.javaScriptEnabled = false
                                settings.blockNetworkLoads = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.domStorageEnabled = false
                            }
                            root.addView(web, FrameLayout.LayoutParams(-1, -1))
                        }
                    }
                    await("attached native host $document") {
                        var ready = false
                        scenario.onActivity {
                            val current = checkNotNull(web)
                            ready = current.isAttachedToWindow && current.hasWindowFocus() &&
                                current.isShown && current.width > 0 && current.height > 0
                        }
                        ready
                    }
                    scenario.onActivity {
                        val current = checkNotNull(web)
                        current.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, finished: String?) {
                                if (finished == url) view.postVisualStateCallback(document.toLong(),
                                    object : WebView.VisualStateCallback() {
                                        override fun onComplete(requestId: Long) { drawn.set(true) }
                                    })
                            }
                        }
                        // Ordinary public inline load; same origin/history arguments as the reader.
                        // No focus action, native provider manipulation, JS or replacement text.
                        current.loadDataWithBaseURL(url, """<!doctype html><html><head>
                            <title>Native control $document</title>
                            <meta name="viewport" content="width=device-width, initial-scale=1">
                            <style>body{font:20px sans-serif;padding:24px}p{margin:24px 0}</style>
                            </head><body><header><p>Owned metadata</p><h1>Native control $document</h1></header>
                            <main><h2>Owned body</h2><p>$exact</p><p>Second owned paragraph.</p></main>
                            </body></html>""".trimIndent(), "text/html", "utf-8", url)
                        Log.i(TAG, "api=${Build.VERSION.SDK_INT} provider=${WebView.getCurrentWebViewPackage()?.versionName} document=$document host=${System.identityHashCode(current)} mode=${if (document % 2 == 0) "new-host" else "reload"}")
                    }
                    await("visual commit $document") { drawn.get() }
                    var lastTree = "not sampled"
                    try {
                        await("native body $document") {
                            if (Build.VERSION.SDK_INT >= 34) check(automation.clearCache())
                            val lines = mutableListOf<String>()
                            fun visit(node: AccessibilityNodeInfo, inWeb: Boolean): Boolean {
                                if (lines.size >= 160) return false
                                val host = node.className?.toString() == "android.webkit.WebView"
                                val inside = inWeb || host
                                if (inside) lines += "class=${node.className} children=${node.childCount} text=${node.text} visible=${node.isVisibleToUser}"
                                var found = inWeb && !host && node.isVisibleToUser && node.text?.toString() == exact
                                for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                                    try { found = visit(child, inside) || found }
                                    finally { @Suppress("DEPRECATION") child.recycle() }
                                }
                                return found
                            }
                            val nativeRoot = automation.rootInActiveWindow
                            val found = try { nativeRoot?.let { visit(it, false) } == true }
                                finally { @Suppress("DEPRECATION") nativeRoot?.recycle() }
                            lastTree = lines.joinToString("\n")
                            found
                        }
                        Log.i(TAG, "document=$document exactNativeBody=true")
                    } catch (failure: AssertionError) {
                        Log.e(TAG, "document=$document exactNativeBody=false\n$lastTree")
                        scenario.onActivity {
                            val current = checkNotNull(web)
                            val visible = Rect()
                            current.getGlobalVisibleRect(visible)
                            Log.e(TAG, "native title=${current.title} progress=${current.progress} contentHeight=${current.contentHeight} visible=$visible windowFocus=${current.hasWindowFocus()} js=${current.settings.javaScriptEnabled}")
                        }
                        val bitmap = checkNotNull(automation.takeScreenshot())
                        try {
                            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                                "native-webview-control-failure.png").outputStream().use {
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                        } finally { bitmap.recycle() }
                        throw failure
                    }
                }
            } finally {
                scenario.onActivity { web?.let { root.removeView(it); it.stopLoading(); it.destroy() }; web = null }
            }
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        throw AssertionError("Native WebView control timed out: $label")
    }

    private companion object { const val TAG = "NativeWebViewControl" }
}
