package com.fluxa.app

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** Validate the visual observer against native rendering before trusting app screenshots.
 * No Fluxa reader, Compose, source-HTML lookup, JS, accessibility actions or text substitutes.
 */
class RenderedTextProbeTest {
    @Test fun freshRendererEvidenceRejectsWrongHiddenOffscreenAndBlankContent() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.awaitStableHost()
            var web: WebView? = null
            lateinit var root: FrameLayout
            scenario.onActivity { activity -> root = FrameLayout(activity); activity.setContentView(root) }
            fun onWeb(action: (WebView) -> Unit) { scenario.onActivity { action(checkNotNull(web)) } }
            try {
                repeat(6) { index ->
                    val body = "RENDERED-CONTROL-$index exact visible body."
                    val hidden = "HIDDEN-$index must not pass."
                    val offscreen = "OFFSCREEN-$index requires scrolling."
                    val faded = "FADED-$index visible composited caption."
                    val zeroOpacity = "ZERO-OPACITY-$index must not pass."
                    val loaded = AtomicBoolean()
                    scenario.onActivity { activity ->
                        // Cover both reload and native host replacement, plus a resized viewport.
                        if (index == 0 || index == 3) {
                            web?.let { root.removeView(it); it.stopLoading(); it.destroy() }
                            web = WebView(activity).apply {
                                settings.javaScriptEnabled = false
                                settings.blockNetworkLoads = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                            }
                            root.addView(web)
                        }
                        checkNotNull(web).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                if (index % 2 == 0) -1 else (root.width * .8f).toInt(), -1)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) { loaded.set(true) }
                            }
                            loadDataWithBaseURL("https://example.invalid/rendered/$index/", """
                                <!doctype html><html><head><title>Rendered control $index</title>
                                <meta name="viewport" content="width=device-width, initial-scale=1">
                                <style>body{margin:12px;font:16px sans-serif;color:#202020;background:white}</style>
                                </head><body><p>$body</p><p style="display:none">$hidden</p>
                                <p style="visibility:hidden">INVISIBLE-$index</p>
                                <p style="color:transparent">TRANSPARENT-$index</p>
                                <p style="opacity:.75">$faded</p><p style="opacity:0">$zeroOpacity</p>
                                <div style="height:120vh"></div><p>$offscreen</p></body></html>
                            """.trimIndent(), "text/html", "utf-8", null)
                        }
                    }
                    await { loaded.get() }
                    onWeb { it.scrollTo(0, 0) }
                    await {
                        var ready = false
                        onWeb { ready = it.isShown && it.hasWindowFocus() && it.width > 0 &&
                            it.progress == 100 && it.contentHeight > 0 }
                        ready
                    }
                    val document = RenderedTextProbe.read(::onWeb, ::await)
                    assertEquals("Rendered control $index", document.frame.title)
                    val text = checkNotNull(document.visible(body)) { "Visible current document missing: $body; $document" }
                    assertNull("Wrong/previous content must fail", document.exact("RENDERED-CONTROL-${index - 1} exact visible body."))
                    assertNull("display:none must fail", document.visible(hidden))
                    assertNull("visibility:hidden must fail", document.visible("INVISIBLE-$index"))
                    assertNull("Transparent text must fail", document.visible("TRANSPARENT-$index"))
                    assertNotNull("Offscreen prose must exist in renderer", document.exact(offscreen))
                    assertNull("Offscreen prose cannot count as visible", document.visible(offscreen))
                    val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                    try {
                        assertTrue(RenderedTextProbe.inkPixels(image, text) >= 12)
                        val caption = checkNotNull(document.visible(faded))
                        assertTrue(RenderedTextProbe.inkPixels(image, caption, .75f) >= 12)
                        document.visible(zeroOpacity)?.let { invisible ->
                            assertTrue("Zero opacity must not pass a nonzero expected opacity",
                                runCatching { RenderedTextProbe.inkPixels(image, invisible, .75f) }.isFailure)
                        }
                    }
                    finally { image.recycle() }
                    val blank = Bitmap.createBitmap(maxOf(document.frame.width, text.screenBounds.right + 1),
                        maxOf(document.frame.height, text.screenBounds.bottom + 1), Bitmap.Config.ARGB_8888)
                    try {
                        blank.eraseColor(Color.WHITE)
                        assertTrue("Blank output must fail despite valid renderer geometry",
                            runCatching { RenderedTextProbe.inkPixels(blank, text) }.isFailure)
                        assertTrue("Blank output must also fail translucent text verification",
                            runCatching { RenderedTextProbe.inkPixels(blank, text, .75f) }.isFailure)
                    } finally { blank.recycle() }
                    // A native control can set its own scroll position to validate the observer's
                    // transform. App screenshot framing still uses only real touch gestures.
                    onWeb { it.scrollTo(0, it.height) }
                    val scrolled = RenderedTextProbe.read(::onWeb, ::await)
                    assertTrue(scrolled.frame.scrollY > 0)
                    assertNull("Former top paragraph must become offscreen", scrolled.visible(body))
                    val revealed = checkNotNull(scrolled.visible(offscreen)) { "Native scroll did not reveal actual lower paragraph: $scrolled" }
                    val after = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                    try { assertTrue(RenderedTextProbe.inkPixels(after, revealed) >= 12) }
                    finally { after.recycle() }
                    android.util.Log.i("RenderedTextControl", "document=$index geometry=${text.geometry} exactText=true visibleInk=true negativesPassed=true scrollTransformPassed=true")
                }
            } finally {
                scenario.onActivity { web?.let { root.removeView(it); it.stopLoading(); it.destroy() }; web = null }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        throw AssertionError("Renderer control did not complete within 10000ms")
    }
}
