package com.fluxa.app.ui.article

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.local.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReaderModePositionTest {
    /** Scheduling/geometry substitute only, never a Chromium or TalkBack acceptance oracle. */
    @org.robolectric.annotation.Implements(android.webkit.WebView::class)
    class LayoutSamplingWebView : org.robolectric.shadows.ShadowWebView() {
        @org.robolectric.annotation.RealObject
        private lateinit var metricView: android.webkit.WebView
        // SDK 28 WebView delegates setFrame to its provider. Robolectric's
        // deep-proxy provider does not apply View bounds, so use the real base
        // View implementation for this fixture's parent-driven layout only.
        @org.robolectric.util.reflector.ForType(android.view.View::class)
        interface ViewFrameReflector {
            @org.robolectric.util.reflector.Direct
            fun setFrame(left: Int, top: Int, right: Int, bottom: Int): Boolean
        }
        @org.robolectric.annotation.Implementation
        protected fun setFrame(left: Int, top: Int, right: Int, bottom: Int): Boolean =
            org.robolectric.util.reflector.Reflector.reflector(ViewFrameReflector::class.java, metricView)
                .setFrame(left, top, right, bottom)

        var range = 1000
        val commits = mutableListOf<Pair<Long, android.webkit.WebView.VisualStateCallback>>()
        val samples = mutableListOf<android.view.ViewStructure>()
        @org.robolectric.annotation.Implementation
        protected fun computeVerticalScrollRange() = range + metricView.height
        @org.robolectric.annotation.Implementation
        protected fun computeVerticalScrollExtent() = metricView.height
        @org.robolectric.annotation.Implementation
        protected fun getContentHeight() = range + metricView.height
        @org.robolectric.annotation.Implementation
        protected fun postVisualStateCallback(id: Long, callback: android.webkit.WebView.VisualStateCallback) {
            commits += id to callback
        }
        @org.robolectric.annotation.Implementation
        protected fun onProvideVirtualStructure(structure: android.view.ViewStructure) { samples += structure }
    }

    @Config(sdk = [28], shadows = [LayoutSamplingWebView::class])
    @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    @Test fun explorationFallbackRetainsOnlySamePositionAnchorAndRejectsLateAssistAcrossModes() = runTest {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val name = "exploration-position-${UUID.randomUUID()}"
        val owned = object : ContextWrapper(activity.get()) {
            override fun getSharedPreferences(key: String, mode: Int) = super.getSharedPreferences(name, mode)
        }
        val reader = ReaderPreferences(owned, backgroundScope)
        val host = android.widget.FrameLayout(activity.get())
        activity.get().setContentView(host, android.view.ViewGroup.LayoutParams(600, 600))
        val web = ReaderWebView(activity.get())
        val native = org.robolectric.Shadows.shadowOf(web) as LayoutSamplingWebView
        val main = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        val manager = activity.get().getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val accessibility = org.robolectric.Shadows.shadowOf(manager)
        fun assertGeometry(expectedRange: Int) {
            assertTrue("Document host must remain attached", web.isAttachedToWindow)
            assertEquals("Parent must retain its height", 600, host.height)
            assertEquals("Parent must retain its width", 600, host.width)
            assertEquals("Parent traversal must retain the viewport", 600, web.height)
            assertEquals(600, web.width)
            assertEquals(expectedRange, web.nativeMaxScrollOffset)
            assertEquals(expectedRange + web.height, web.contentHeight)
        }
        fun assertPolicy(page: ReaderPage, key: String, ready: Boolean, writable: Boolean) {
            val state = page.diagnosticState()
            assertTrue(state, web.owns(page))
            assertTrue(state, state.startsWith("keyHash=${com.fluxa.app.data.directrss.stableHash(key)} "))
            assertTrue(state, state.contains("retired=false ready=$ready writable=$writable "))
            assertTrue(state, manager.isEnabled && manager.isTouchExplorationEnabled)
            assertTrue(state, state.endsWith("assistBlocked=true"))
        }
        fun commit(page: ReaderPage, key: String, expectedRange: Int) {
            main.idle()
            assertGeometry(expectedRange)
            assertPolicy(page, key, ready = false, writable = false)
            val previousDocument = web.committedDocument
            web.webViewClient.onPageFinished(web, native.lastLoadDataWithBaseURL.baseUrl)
            val (id, callback) = native.commits.single()
            native.commits.clear()
            callback.onComplete(id)
            assertTrue("A new owned document must visually commit", web.committedDocument > previousDocument)
            assertPolicy(page, key, ready = true, writable = false)
            // Real settle takes three stable 80 ms samples. Advance only the test
            // looper, checking geometry through queued activity/frame traversals;
            // do not wait for the 20-attempt fallback or the 5-second watchdog.
            repeat(3) {
                main.idleFor(java.time.Duration.ofMillis(80))
                assertGeometry(expectedRange)
            }
            assertPolicy(page, key, ready = true, writable = true)
        }
        try {
            runCurrent()
            val id = "direct-rss:owned-exploration"
            val rssKey = readerPositionKey(id, false)
            val fullKey = readerPositionKey(id, true)
            reader.freeze(listOf(id, "direct-rss:neighbor"), id)
            val block = NativeBlock(NativeAnchors.digest("Owned paragraph"), "text", "/0", 0, 400, 200)
            val rssPosition = ReadingPosition(.5f, 500, 1000, NativeAnchors.capture(listOf(block), 500))
            val fullPosition = ReadingPosition(.2f, 800, 4000)
            reader.savePosition(rssKey, rssPosition); reader.savePosition(fullKey, fullPosition)
            accessibility.setTouchExplorationEnabled(true); accessibility.setEnabled(true)
            // Keep parent/child dimensions through Activity/FrameLayout traversal.
            // The fixture shadow applies real View framing instead of the stock
            // no-op WebView provider; neither height nor width getters are faked.
            host.addView(web, android.widget.FrameLayout.LayoutParams(600, 600))
            val exact = android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY)
            host.measure(exact, exact); host.layout(0, 0, 600, 600)
            main.idleFor(java.time.Duration.ofMillis(32))
            assertGeometry(1000)
            assertNotEquals(rssKey, fullKey)
            val rss = ReaderPage(rssKey, reader)
            web.render(rss, "<p>RSS</p>", 100)
            commit(rss, rssKey, 1000)
            assertTrue(native.samples.isEmpty())
            assertEquals(500, web.scrollY)
            assertEquals(rssPosition, reader.position(rssKey))
            // A moved pixel position cannot retain the old paragraph anchor.
            web.scrollTo(0, 650); rss.capture()
            val moved = reader.position(rssKey)
            assertEquals(650, moved.pixels); assertNull(moved.anchor)
            // Public state changes re-enable ordinary sampling. Leave it pending,
            // then enable exploration and rebind FULL before its async completion.
            accessibility.setTouchExplorationEnabled(false); accessibility.setEnabled(false)
            main.idle()
            val pending = native.samples.single()
            pending.setChildCount(1)
            val document = pending.asyncNewChild(0)
            document.setDimens(0, 0, 0, 0, 600, 900)
            document.setText("Obsolete RSS geometry")
            accessibility.setTouchExplorationEnabled(true); accessibility.setEnabled(true)
            main.idle()
            val full = ReaderPage(fullKey, reader)
            web.render(full, "<p>FULL</p>", 100)
            native.range = 4000
            rss.release()
            commit(full, fullKey, 4000)
            document.asyncCommit() // Canceled sink from a retired RSS controller.
            main.idleFor(java.time.Duration.ofSeconds(5))
            assertGeometry(4000)
            assertPolicy(full, fullKey, ready = true, writable = true)
            assertEquals(1, native.samples.size)
            assertEquals(800, web.scrollY)
            assertEquals(fullPosition, reader.position(fullKey))
            assertEquals(moved, reader.position(rssKey))
            assertTrue(web.owns(full))
            assertEquals(listOf(id, "direct-rss:neighbor"), reader.session.value.ids)
            web.disposeReader()
            accessibility.setTouchExplorationEnabled(false); accessibility.setEnabled(false)
            main.idle()
            assertEquals(1, native.samples.size) // Released listeners cannot sample the destroyed host.
            assertEquals(fullPosition, reader.position(fullKey))
        } finally {
            web.disposeReader(); host.removeAllViews()
            accessibility.setTouchExplorationEnabled(false); accessibility.setEnabled(false)
            activity.get().deleteSharedPreferences(name)
            activity.pause().stop().destroy()
        }
    }

    @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    @Test fun nativeDocumentStartsOnlyAfterAttachmentAndRetirementCancelsQueuedStart() {
        // Scheduling contract only; Robolectric cannot establish Chromium/TalkBack AX.
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val host = android.widget.FrameLayout(activity.get())
        activity.get().setContentView(host)
        val web = ReaderWebView(activity.get())
        val starts = mutableListOf<String>()
        val main = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        try {
            web.loadAfterAttachment { starts += "superseded" }
            web.loadAfterAttachment { starts += "attached" }
            main.idle()
            assertTrue("Detached factory/update cannot start the document", starts.isEmpty())
            host.addView(web)
            assertTrue("Attachment queues rather than starts inline", starts.isEmpty())
            main.idle()
            assertEquals(listOf("attached"), starts)
            web.loadAfterAttachment { starts += "retired" }
            web.cancelPendingDocument()
            main.idle()
            assertEquals(listOf("attached"), starts)
            web.loadAfterAttachment { starts += "reattached" }
            host.removeView(web)
            main.idle()
            assertEquals(listOf("attached"), starts)
            host.addView(web)
            main.idle()
            assertEquals(listOf("attached", "reattached"), starts)
        } finally {
            web.cancelPendingDocument()
            host.removeAllViews()
            web.destroy()
            activity.pause().stop().destroy()
        }
    }

    @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    @Test fun sameRendererRebindsModesRejectsRetiredLoadsAndCancelsOldWork() = runTest {
        // Real controller/host ownership and scheduling, NOT Chromium AX acceptance.
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val name = "rebind-${UUID.randomUUID()}"
        val owned = object : ContextWrapper(activity.get()) {
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences = super.getSharedPreferences(name, mode)
        }
        val reader = ReaderPreferences(owned, backgroundScope)
        val host = android.widget.FrameLayout(activity.get())
        activity.get().setContentView(host)
        val web = ReaderWebView(activity.get())
        val native = org.robolectric.Shadows.shadowOf(web)
        val main = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        try {
            runCurrent()
            val id = "direct-rss:rebind"
            val rssKey = readerPositionKey(id, false)
            val fullKey = readerPositionKey(id, true)
            reader.freeze(listOf(id, "direct-rss:neighbor"), id)
            val rssPosition = ReadingPosition(.5f, 500, 1000)
            val fullPosition = ReadingPosition(.2f, 800, 4000)
            reader.savePosition(rssKey, rssPosition)
            reader.savePosition(fullKey, fullPosition)
            val images = mutableListOf<String>()
            val rss = ReaderPage(rssKey, reader).apply { onImage = { images += "rss:$it" } }
            val full = ReaderPage(fullKey, reader).apply { onImage = { images += "full:$it" } }
            host.addView(web)
            web.render(rss, "<p>RSS</p>", 100)
            main.idle()
            val oldLoad = native.lastLoadDataWithBaseURL
            val oldClient = web.webViewClient
            assertEquals("<p>RSS</p>", oldLoad.data)
            assertTrue(oldLoad.baseUrl.startsWith("https://www.newsblur.com/fluxa-reader/"))
            assertEquals("Inline navigation must not collapse to about:blank", oldLoad.baseUrl, oldLoad.historyUrl)
            // Queue an outgoing reflow, then change modes before it can load.
            web.render(rss, "<p>obsolete RSS reflow</p>", 110)
            web.render(full, "<p>FULL</p>", 100)
            val fullClient = web.webViewClient
            rss.release() // Late outgoing composition disposal must not detach the new owner.
            web.render(rss, "<p>stale composition update</p>", 120)
            rss.load("<p>stale direct update</p>", 120)
            oldClient.onPageFinished(web, oldLoad.baseUrl)
            main.idle()
            assertSame(fullClient, web.webViewClient)
            val fullLoad = native.lastLoadDataWithBaseURL
            assertEquals("<p>FULL</p>", fullLoad.data)
            assertEquals(fullLoad.baseUrl, fullLoad.historyUrl)
            assertNotEquals("Replacement controllers start at generation 1 but need distinct navigation identities",
                oldLoad.historyUrl, fullLoad.historyUrl)
            assertFalse(native.wasDestroyCalled())
            native.setHitTestResult(org.robolectric.shadows.ShadowWebView.createHitTestResult(
                android.webkit.WebView.HitTestResult.IMAGE_TYPE, "owned-image"))
            assertFalse("Outgoing pixels cannot activate an incoming image before commit", web.performLongClick())
            assertTrue(images.isEmpty())
            // Retire before the new restore finishes: neither watchdog nor old snapshot
            // delivery may save another document's geometry into either position key.
            full.release()
            main.idleFor(java.time.Duration.ofSeconds(6))
            assertEquals(rssPosition, reader.position(rssKey))
            assertEquals(fullPosition, reader.position(fullKey))
            assertEquals(listOf(id, "direct-rss:neighbor"), reader.session.value.ids)
            val reopened = ReaderPage(rssKey, reader)
            web.render(reopened, "<p>RSS reopened</p>", 100)
            main.idle()
            val reopenedLoad = native.lastLoadDataWithBaseURL
            assertEquals("<p>RSS reopened</p>", reopenedLoad.data)
            assertEquals(reopenedLoad.baseUrl, reopenedLoad.historyUrl)
            assertNotEquals(oldLoad.historyUrl, reopenedLoad.historyUrl)
            assertNotEquals(fullLoad.historyUrl, reopenedLoad.historyUrl)
            web.render(reopened, "<p>must never start after disposal</p>", 110)
            web.disposeReader()
            web.disposeReader()
            main.idleFor(java.time.Duration.ofSeconds(6))
            assertTrue(native.wasDestroyCalled())
            assertEquals("<p>RSS reopened</p>", native.lastLoadDataWithBaseURL.data)
            assertEquals("A canceled queued load cannot publish its history identity",
                reopenedLoad.historyUrl, native.lastLoadDataWithBaseURL.historyUrl)
        } finally {
            web.disposeReader()
            host.removeAllViews()
            activity.get().deleteSharedPreferences(name)
            activity.pause().stop().destroy()
        }
    }

    @Config(sdk = [28], shadows = [LayoutSamplingWebView::class])
    @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    @Test fun opaqueCoverKeepsDrawingAndOnlyCurrentVisualCommitCanUncover() = runTest {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val name = "cover-${UUID.randomUUID()}"
        val owned = object : ContextWrapper(activity.get()) {
            override fun getSharedPreferences(key: String, mode: Int) = super.getSharedPreferences(name, mode)
        }
        val reader = ReaderPreferences(owned, backgroundScope)
        val host = android.widget.FrameLayout(activity.get())
        activity.get().setContentView(host)
        val web = ReaderWebView(activity.get())
        val native = org.robolectric.Shadows.shadowOf(web) as LayoutSamplingWebView
        val main = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        fun pendingCommit(): Pair<Long, android.webkit.WebView.VisualStateCallback> {
            main.idle()
            web.webViewClient.onPageFinished(web, native.lastLoadDataWithBaseURL.baseUrl)
            return native.commits.last()
        }
        fun complete(commit: Pair<Long, android.webkit.WebView.VisualStateCallback>) = commit.second.onComplete(commit.first)
        fun covered(color: Int) {
            assertTrue(web.isAttachedToWindow)
            assertEquals(1f, web.alpha, 0f)
            assertTrue(web.documentCovered)
            assertEquals(color, (web.foreground as android.graphics.drawable.ColorDrawable).color)
        }
        try {
            runCurrent()
            host.addView(web)
            val rss = ReaderPage(readerPositionKey("owned-cover", false), reader)
            val full = ReaderPage(readerPositionKey("owned-cover", true), reader)
            val paper = 0xfff4ecd9.toInt()
            val dark = 0xff171b19.toInt()
            web.render(rss, "<p>RSS first</p>", 100, paper)
            covered(paper)
            val obsolete = pendingCommit()
            web.render(rss, "<p>RSS reflow</p>", 110, paper)
            val current = pendingCommit()
            complete(obsolete)
            covered(paper)
            assertEquals(0L, web.committedDocument)
            complete(current)
            assertFalse(web.documentCovered)
            assertNull(web.foreground)
            val committed = web.committedDocument
            assertTrue(committed > 0)
            rss.retire()
            covered(paper)
            complete(current)
            covered(paper)
            web.render(full, "<p>FULL</p>", 100, dark)
            val incoming = pendingCommit()
            rss.release()
            complete(obsolete)
            covered(dark)
            main.idleFor(java.time.Duration.ofSeconds(5)) // Watchdog is not a visual commit.
            covered(dark)
            assertEquals(committed, web.committedDocument)
            complete(incoming)
            assertFalse(web.documentCovered)
            assertNull(web.foreground)
            assertTrue(web.committedDocument > committed)
            web.render(full, "<p>queued but retired</p>", 120, dark)
            full.release()
            main.idle()
            covered(dark)
            assertEquals("<p>FULL</p>", native.lastLoadDataWithBaseURL.data)
            complete(incoming)
            covered(dark)
        } finally {
            web.disposeReader(); host.removeAllViews()
            activity.get().deleteSharedPreferences(name)
            activity.pause().stop().destroy()
        }
    }

    @Test fun independentModeAnchorsSurviveReopenWithoutChangingFrozenQueueOrSavedPageKey() = runTest {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val name = "mode-position-${UUID.randomUUID()}"
        val owned = object : ContextWrapper(base) {
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences = super.getSharedPreferences(name, mode)
        }
        try {
            val reader = ReaderPreferences(owned, backgroundScope)
            runCurrent()
            val id = "direct-rss:owned"
            reader.freeze(listOf(id, "direct-rss:neighbor"), id)
            reader.savePosition(readerPositionKey(id, false), ReadingPosition(.5f, 500, 1000))
            reader.savePosition(readerPositionKey(id, true), ReadingPosition(.2f, 800, 4000))
            advanceTimeBy(650); runCurrent()
            val reopened = ReaderPreferences(owned, backgroundScope)
            runCurrent()
            assertEquals(500, reopened.position(readerPositionKey(id, false)).pixels)
            assertEquals(800, reopened.position(readerPositionKey(id, true)).pixels)
            assertEquals(listOf(id, "direct-rss:neighbor"), reopened.session.value.ids)
            assertEquals("direct-rss:page:saved", readerPositionKey("direct-rss:page:saved", false))
            val guard = PageRestoreGuard()
            val old = guard.begin(readerPositionKey(id, false))
            guard.begin(readerPositionKey(id, true))
            assertFalse(guard.rendered(readerPositionKey(id, false), old))
            assertFalse(guard.canWrite(readerPositionKey(id, false), old))
        } finally { base.deleteSharedPreferences(name) }
    }
}
