package com.fluxa.app

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.text.Html
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxa.app.data.directrss.DirectArticle
import com.fluxa.app.data.directrss.IFANR_FEED
import com.fluxa.app.di.DirectRssAccess
import com.fluxa.app.ui.article.ReaderWebView
import com.fluxa.app.ui.article.NativeDocumentSnapshot
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch

/** Existing real data only. No explicit refresh, fixtures, settings changes, scripts or replacement client.
 * FeedList entry/source selection automatically refresh; wait for idle before the reader baseline.
 * Normal UI navigation can persist reader positions/session and mark opened articles read.
 * Native assist rectangles establish layout, NOT naturalWidth/complete or successful decoding.
 */
class DirectRssRenderingDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun touchControl(sync: DeviceFlowSync, label: String) {
        val node = compose.onNodeWithContentDescription(label)
        sync.settled(node)
        node.performTouchInput { click() }
    }

    @Test fun allExistingOfficialDailyImagesHaveRealNativeLayout() {
        val args = InstrumentationRegistry.getArguments()
        // Must precede ApplicationProvider, Hilt, Room, UiAutomation and Activity launch.
        assumeTrue("Requires directRenderReal=1", args.getString("directRenderReal") == "1")
        assertEquals("Dedicated API 35 emulator required", 35, Build.VERSION.SDK_INT)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        fun connected(): Boolean {
            val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        assertTrue("An actual validated connected network is required", connected())
        val access = EntryPointAccessors.fromApplication(context, DirectRssAccess::class.java)
        assertFalse("Use the dedicated signed-out emulator; do not change authentication", access.authRepository().hasSession())
        val dao = access.directDatabase().dao()
        val originalSource = runBlocking { dao.subscriptions().single { it.dailyOnly && it.url == IFANR_FEED } }
        val originalArticles = runBlocking { dao.articles().filter { it.source == originalSource.id }
            .sortedWith(compareByDescending<DirectArticle> { it.published }.thenBy { it.id }) }
        assertEquals("Expected the three existing complete daily posts", 3, originalArticles.size)
        assertEquals(3, originalArticles.map { it.originalUrl.substringBefore('?') }.distinct().size)
        val expectedLink = args.getString("directRenderLatestLink") ?: "https://www.ifanr.com/1683053"
        assertEquals(expectedLink.substringBefore('?'), originalArticles.first().originalUrl.substringBefore('?'))
        originalArticles.forEach {
            assertTrue(it.originalUrl.startsWith("https://www.ifanr.com/"))
            assertEquals(it.totalImages, Regex("<img\\b", RegexOption.IGNORE_CASE).findAll(it.body).count())
            assertTrue("Stored post must contain images", it.totalImages > 0)
        }
        val directory = File(context.filesDir, "direct-real-render").apply { check(isDirectory || mkdirs()) }
        // Fresh names retain earlier successful or failed runs, without deleting evidence.
        val run = System.currentTimeMillis().toString()
        checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.serviceInfo)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val sync = DeviceFlowSync(compose, scenario)
            fun touch(text: String) {
                val node = compose.onNodeWithText(text, useUnmergedTree = true)
                sync.settled(node)
                node.performTouchInput { click() }
            }
            fun waitForRefreshEnabled() {
                // The actual toolbar control reflects FeedListState.busy; never trigger refresh here.
                val refresh = hasContentDescription("刷新") and isEnabled()
                compose.waitUntil(180_000) {
                    compose.onAllNodes(refresh).fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithContentDescription("刷新").assertIsEnabled()
            }
            sync.login()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription("本地 RSS · 无需账号").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithContentDescription("本地 RSS · 无需账号").performScrollTo()
            touchControl(sync, "本地 RSS · 无需账号")
            sync.list()
            waitForRefreshEnabled()
            compose.onNodeWithContentDescription("选择订阅").performTouchInput { click() }
            val sourceMatcher = hasContentDescription(originalSource.title)
            compose.waitUntil(10_000) { compose.onAllNodes(sourceMatcher).fetchSemanticsNodes().size == 1 }
            compose.onNode(sourceMatcher).performScrollTo()
            sync.settled(compose.onNode(sourceMatcher))
            compose.onNode(sourceMatcher).performTouchInput { click() }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("关闭").fetchSemanticsNodes().isEmpty() }
            sync.list()
            waitForRefreshEnabled()
            // Read-only, post-auto-refresh baseline, before opening any article. Reject feed drift
            // rather than silently accepting new links, titles, content, cache or star state.
            val source = checkNotNull(runBlocking { dao.subscription(originalSource.id) })
            val articles = runBlocking { dao.articles().filter { it.source == source.id }
                .sortedWith(compareByDescending<DirectArticle> { it.published }.thenBy { it.id }) }
            assertEquals("Automatic refresh changed source identity/metadata", originalSource,
                source.copy(status = originalSource.status, lastSuccess = originalSource.lastSuccess))
            assertEquals("Automatic refresh changed the opted-in three posts; review real data before opting in again",
                originalArticles, articles)
            assertEquals(expectedLink.substringBefore('?'), articles.first().originalUrl.substringBefore('?'))
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("全部"))
            touchControl(sync, "全部")
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(articles.first().title))
            touch(articles.first().title)

            articles.forEachIndexed { index, article ->
                assertTrue("Network lost during real rendering", connected())
                // Runs after the opening title click AND every subsequent next-article click.
                // WebView progress alone does not remove the outgoing Compose destination.
                sync.reader(article.title)
                awaitNativeFrame(scenario)
                val evidence = JSONObject().put("id", article.originalUrl.substringBefore('?').substringAfterLast('/'))
                    .put("title", article.title).put("published", article.published).put("link", article.originalUrl)
                    .put("expectedImageCount", article.totalImages)
                    .put("capturedImageProof", "WebView.onProvideVirtualStructure / asyncCommit; full-document img rectangles; visual-state callback and native end scroll; screenshot")
                    .put("warnings", JSONArray().put("Positive layout rectangles do not prove successful decoding: public assist exposes neither complete nor naturalWidth. Large broken placeholders cannot be excluded by geometry alone.")
                        .put("The existing NativeDocumentSnapshot sink discards builder attributes; src may be unavailable. No source identity is inferred or exported."))
                val file = File(directory, "$run-article-${index + 1}.json")
                var latest: List<NativeDocumentSnapshot.Entry> = emptyList()
                try {
                    val requiredText = sourceText(article.body)
                    assertTrue("Source must include substantial body text", requiredText.sumOf { it.length } > 300)
                    assertTrue("Source must include headings", Regex("<h[1-6]\\b", RegexOption.IGNORE_CASE).containsMatchIn(article.body))
                    val deadline = SystemClock.uptimeMillis() + 90_000
                    var stableSince = 0L
                    var previous: List<android.graphics.Rect>? = null
                    var complete = false
                    while (SystemClock.uptimeMillis() < deadline) {
                        latest = snapshot(scenario)
                        val images = images(latest)
                        val text = normalized(latest.joinToString(" ") { it.text.orEmpty() })
                        val bodyComplete = requiredText.all { normalized(it) in text }
                        val footer = latest.any { it.html?.tag.equals("footer", true) } &&
                            normalized("阅读原文 · 在浏览器打开") in text
                        // Editorial images are not icons: reject tiny broken-image/alt placeholders.
                        val healthy = images.size == article.totalImages && images.all {
                            it.bounds.width() >= 48 && it.bounds.height() >= 48 && it.bounds.top >= 0
                        }
                        val rects = images.map { it.bounds }
                        if (!healthy || !bodyComplete || !footer || rects != previous) stableSince = SystemClock.uptimeMillis()
                        previous = rects
                        if (healthy && bodyComplete && footer && SystemClock.uptimeMillis() - stableSince >= 1_500) {
                            complete = true
                            break
                        }
                        pollWithComposeIdle(400)
                    }
                    recordImages(evidence, latest)
                    file.writeText(evidence.toString(2))
                    assertTrue("All stored images, source text and final footer must be present with stable non-tiny native bounds; see own JSON evidence", complete)
                    scrollToEndAndDraw(scenario)
                    latest = snapshot(scenario)
                    val endImages = images(latest)
                    assertEquals(article.totalImages, endImages.size)
                    assertTrue("End layout contains failed/tiny image bounds", endImages.all { it.bounds.width() >= 48 && it.bounds.height() >= 48 })
                    var linkVisible = false
                    scenario.onActivity {
                        val web = web(it)
                        linkVisible = latest.any { entry ->
                            entry.text?.contains("阅读原文") == true && entry.bounds.width() > 0 &&
                                entry.bounds.top >= web.scrollY && entry.bounds.bottom <= web.scrollY + web.height
                        }
                        assertFalse("Must actually reach document end", web.canScrollVertically(1))
                    }
                    assertTrue("Original article link must be fully visible at actual native end", linkVisible)
                    sync.reader(article.title)
                    screenshot(scenario, File(directory, "$run-article-${index + 1}-end.png"))
                    recordImages(evidence, latest)
                    evidence.put("warnings", evidence.getJSONArray("warnings").put("Passed geometry checks only; end screenshot is not pixel proof for every offscreen image."))
                    file.writeText(evidence.toString(2))
                } catch (failure: Throwable) {
                    recordImages(evidence, latest)
                    evidence.getJSONArray("warnings").put("FAILED: rendering/layout/completeness assertion or native callback; not accepted as image proof.")
                    file.writeText(evidence.toString(2))
                    // Only the public article reader is captured, never login or account UI.
                    runCatching {
                        sync.reader(article.title)
                        screenshot(scenario, File(directory, "$run-article-${index + 1}-failure.png"))
                    }
                    throw failure
                }
                if (index < articles.lastIndex) touchControl(sync, "下一篇")
            }
            compose.onNodeWithContentDescription("已是末篇").assertIsNotEnabled()
            // Read-only verification: no cache/content/star mutation or new rows is authorized.
            val after = runBlocking { dao.articles().filter { it.source == source.id } }
            assertEquals(articles.map { it.id }.toSet(), after.map { it.id }.toSet())
            articles.forEach { before ->
                val saved = after.single { it.id == before.id }
                assertTrue(saved.isRead)
                assertEquals(before.copy(isRead = true), saved)
            }
            assertEquals(source, runBlocking { dao.subscription(source.id) })
        }
    }

    /** Screenshot collection only. Root must match every stored image against these pixels. */
    @Test fun captureExistingOfficialDailyViewportEvidence() {
        val args = InstrumentationRegistry.getArguments()
        // No application/activity/UiAutomation access before explicit opt-in.
        assumeTrue("Requires directRenderReal=1", args.getString("directRenderReal") == "1")
        assertEquals("Dedicated API 35 emulator required", 35, Build.VERSION.SDK_INT)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        fun requireNetwork() {
            val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            assertTrue("Validated network required throughout collection", caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }
        requireNetwork()
        val access = EntryPointAccessors.fromApplication(context, DirectRssAccess::class.java)
        assertFalse("Use the dedicated signed-out emulator", access.authRepository().hasSession())
        val dao = access.directDatabase().dao()
        val originalSource = runBlocking { dao.subscriptions().single { it.dailyOnly && it.url == IFANR_FEED } }
        val originalArticles = runBlocking { dao.articles().filter { it.source == originalSource.id }
            .sortedWith(compareByDescending<DirectArticle> { it.published }.thenBy { it.id }) }
        assertEquals(3, originalArticles.size)
        assertEquals(3, originalArticles.map { it.originalUrl.substringBefore('?') }.distinct().size)
        assertEquals("https://www.ifanr.com/1683053", originalArticles.first().originalUrl.substringBefore('?'))
        assertEquals("Expected 69 stored source images across the existing three posts", 69,
            originalArticles.sumOf { it.totalImages })
        originalArticles.forEach {
            assertTrue(it.originalUrl.startsWith("https://www.ifanr.com/"))
            assertTrue(it.totalImages > 0)
            assertEquals(it.totalImages, Regex("<img\\b", RegexOption.IGNORE_CASE).findAll(it.body).count())
        }
        val run = "${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}"
        val root = File(context.filesDir, "direct-real-viewport").apply { check(isDirectory || mkdirs()) }
        val directory = File(root, run).apply { check(mkdirs()) }
        val report = JSONObject().put("run", run).put("deviceApi", Build.VERSION.SDK_INT)
            .put("status", "collecting").put("proof", "Screenshot collection ONLY; NOT passing 69-image/decode proof")
            .put("processPid", android.os.Process.myPid())
            .put("processStartElapsedRealtimeMs", android.os.Process.getStartElapsedRealtime())
            .put("rangeDefinition", "max(0, WebView provider computeVerticalScrollRange() - computeVerticalScrollExtent()); native scroll pixels")
            .put("articles", JSONArray())
        fun save() {
            // Per-run manifest is immutable across runs; root manifest points at the latest attempt.
            File(directory, "run.json").writeText(report.toString(2))
            File(root, "run.json").writeText(report.toString(2))
        }
        save()
        try {
            checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.serviceInfo)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val sync = DeviceFlowSync(compose, scenario)
                fun touch(text: String) {
                    val node = compose.onNodeWithText(text, useUnmergedTree = true)
                    sync.settled(node)
                    node.performTouchInput { click() }
                }
                fun refreshIdle() {
                    compose.waitUntil(180_000) {
                        compose.onAllNodes(hasContentDescription("刷新") and isEnabled()).fetchSemanticsNodes().size == 1
                    }
                    compose.onNodeWithContentDescription("刷新").assertIsEnabled()
                    requireNetwork()
                }
                sync.login()
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithContentDescription("本地 RSS · 无需账号").fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithContentDescription("本地 RSS · 无需账号").performScrollTo()
                touchControl(sync, "本地 RSS · 无需账号")
                sync.list()
                refreshIdle()
                compose.onNodeWithContentDescription("选择订阅").performTouchInput { click() }
                val sourceMatcher = hasContentDescription(originalSource.title)
                compose.waitUntil(10_000) { compose.onAllNodes(sourceMatcher).fetchSemanticsNodes().size == 1 }
                compose.onNode(sourceMatcher).performScrollTo()
                sync.settled(compose.onNode(sourceMatcher))
                compose.onNode(sourceMatcher).performTouchInput { click() }
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("关闭").fetchSemanticsNodes().isEmpty() }
                sync.list()
                refreshIdle()
                val source = checkNotNull(runBlocking { dao.subscription(originalSource.id) })
                val articles = runBlocking { dao.articles().filter { it.source == source.id }
                    .sortedWith(compareByDescending<DirectArticle> { it.published }.thenBy { it.id }) }
                assertEquals("Automatic refresh changed source identity/metadata", originalSource,
                    source.copy(status = originalSource.status, lastSuccess = originalSource.lastSuccess))
                assertEquals("Automatic refresh changed the opted-in posts/cache/stars", originalArticles, articles)
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("全部"))
                touchControl(sync, "全部")
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(articles.first().title))
                touch(articles.first().title)
                articles.forEachIndexed { index, article ->
                    val evidence = JSONObject().put("id", article.id).put("sourceId", article.source)
                        .put("sourceTitle", source.title).put("sourceUrl", source.url)
                        .put("title", article.title).put("link", article.originalUrl).put("published", article.published)
                        .put("expectedImages", article.totalImages).put("bodyTextComplete", false)
                        .put("footerTextPresent", false).put("reachedEnd", false).put("frames", JSONArray())
                        .put("limitations", "Assist image count/bounds are diagnostic only (offscreen 1px/decorative omissions); attributes discarded by production sink. No decode or source-image match inferred. Footer pixels require root inspection.")
                    report.getJSONArray("articles").put(evidence)
                    save()
                    requireNetwork()
                    sync.reader(article.title)
                    awaitNativeFrame(scenario)
                    // Share one existing 90s budget across both native stability waits.
                    val setupDeadline = SystemClock.uptimeMillis() + 90_000
                    val setupTrace = JSONArray()
                    evidence.put("setupTrace", setupTrace)
                    fun traceSetup(phase: String, state: ViewportState) {
                        setupTrace.put(state.json().put("phase", phase)
                            .put("uptimeMs", SystemClock.uptimeMillis())
                            .put("layoutRequested", state.layoutRequested))
                        save()
                    }
                    traceSetup("beforeGesture", viewportState(scenario))
                    // Real ACTION_DOWN reaches ReaderPage's touch listener and cancels restore.
                    // Move beyond slop inside the reader, then CANCEL: no click or UP fling.
                    compose.onNodeWithTag("reader-content").performTouchInput {
                        down(androidx.compose.ui.geometry.Offset(width / 2f, height * .4f))
                        moveTo(androidx.compose.ui.geometry.Offset(width / 2f, height * .65f),
                            delayMillis = 400)
                        cancel()
                    }
                    val settledGesture = awaitViewportStable(scenario, ::requireNetwork, setupDeadline)
                    traceSetup("afterSettledGesture", settledGesture)
                    scenario.onActivity { web(it).scrollTo(0, 0) }
                    traceSetup("immediateAfterScrollTo", viewportState(scenario))
                    awaitNativeFrame(scenario)
                    val baseline = awaitViewportStable(scenario, ::requireNetwork, setupDeadline)
                    traceSetup("stableFinal", baseline)
                    assertEquals("Collection must start at zero", 0, baseline.scrollY)
                    // Exactly one complete assist callback per article, retaining the original deadline.
                    val entries = snapshot(scenario)
                    val nativeText = entries.joinToString("\n") { it.text.orEmpty() }
                    val text = normalized(nativeText)
                    val required = sourceText(article.body)
                    val missing = required.filter { normalized(it) !in text }
                    val footer = entries.any { it.html?.tag.equals("footer", true) } &&
                        normalized("阅读原文 · 在浏览器打开") in text
                    evidence.put("nativeText", nativeText).put("missingBodyLines", JSONArray(missing))
                        .put("bodyTextComplete", missing.isEmpty()).put("footerTextPresent", footer)
                    recordImages(evidence, entries)
                    save()
                    assertTrue("Stored body must be substantial", required.sumOf { it.length } > 300)
                    assertTrue("Complete native assist must contain every normalized source body line", missing.isEmpty())
                    assertTrue("Native assist must contain original-link/footer text", footer)
                    var reachedEnd = false
                    for (frameIndex in 0 until 200) {
                        requireNetwork()
                        awaitNativeFrame(scenario)
                        val state = viewportState(scenario)
                        assertEquals("Late reflow invalidates coverage; rerun rather than accepting gaps",
                            baseline.layoutKey(), state.layoutKey())
                        assertTrue("Captured scrollY must stay within legal native bounds",
                            state.scrollY >= 0 && state.scrollY <= state.range)
                        val frames = evidence.getJSONArray("frames")
                        if (frames.length() > 0) {
                            val previousY = frames.getJSONObject(frames.length() - 1).getInt("scrollY")
                            assertTrue("Scroll must advance with at least 30% overlap",
                                state.scrollY > previousY && state.scrollY - previousY <= state.visibleHeight * 0.7)
                        }
                        val name = "$run-article-${index + 1}-frame-${frameIndex.toString().padStart(3, '0')}.png"
                        screenshot(scenario, File(directory, name))
                        val afterShot = viewportState(scenario)
                        frames.put(state.json().put("file", "$run/$name"))
                        save()
                        assertEquals("Viewport changed during screenshot", state, afterShot)
                        requireNetwork()
                        if (!state.canScrollDown) {
                            val end = awaitViewportStable(scenario, ::requireNetwork)
                            assertEquals("Late reflow at end invalidates collection", state, end)
                            scenario.onActivity { assertFalse("True native end required", web(it).canScrollVertically(1)) }
                            assertTrue("Native end scrollY must equal provider range within 2px",
                                kotlin.math.abs(end.scrollY - end.range) <= 2)
                            evidence.put("reachedEnd", true).put("nativeEndScrollY", end.scrollY)
                                .put("endScreenshot", "$run/$name")
                            reachedEnd = true
                            save()
                            break
                        }
                        val step = (state.visibleHeight * 0.65).toInt().coerceAtLeast(1)
                        // View.scrollTo does not clamp WebView's native scroll offset here.
                        val requestedY = minOf(state.range, state.scrollY + step)
                        assertTrue("Scrollable viewport must request strict forward progress",
                            requestedY > state.scrollY)
                        assertTrue("Requested scrollY must not exceed legal native range",
                            requestedY <= state.range)
                        scenario.onActivity { web(it).scrollTo(0, requestedY) }
                        awaitNativeFrame(scenario)
                    }
                    assertTrue("Exceeded bounded 200 frames without stable native end", reachedEnd)
                    if (index < articles.lastIndex) touchControl(sync, "下一篇")
                }
                compose.onNodeWithContentDescription("已是末篇").assertIsNotEnabled()
                requireNetwork()
                val after = runBlocking { dao.articles().filter { it.source == source.id } }
                assertEquals(articles.map { it.id }.toSet(), after.map { it.id }.toSet())
                articles.forEach { before ->
                    val saved = after.single { it.id == before.id }
                    assertTrue("Opened real post read state must persist", saved.isRead)
                    assertEquals("Only isRead may change", before.copy(isRead = true), saved)
                }
                assertEquals(source, runBlocking { dao.subscription(source.id) })
                report.put("daoPreservedExceptRead", true)
            }
            report.put("status", "screenshots_collected_pixel_validation_pending")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure", failure.toString())
            throw failure
        } finally {
            save()
        }
    }

    /** Root force-stops between successful collection and this separately opted-in run. */
    @Test fun verifyExistingOfficialDailyColdProcessEndRestoration() {
        val args = InstrumentationRegistry.getArguments()
        // Both guards precede ApplicationProvider, Activity and UiAutomation.
        assumeTrue("Requires directRenderReal=1 AND directColdEndVerify=1",
            args.getString("directRenderReal") == "1" && args.getString("directColdEndVerify") == "1")
        assertEquals("Dedicated API 35 emulator required", 35, Build.VERSION.SDK_INT)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        fun requireNetwork() {
            val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            assertTrue("Actual validated network required", caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }
        requireNetwork()
        val access = EntryPointAccessors.fromApplication(context, DirectRssAccess::class.java)
        assertFalse("Use the dedicated signed-out emulator", access.authRepository().hasSession())
        val dao = access.directDatabase().dao()
        val originalSource = runBlocking { dao.subscriptions().single { it.dailyOnly && it.url == IFANR_FEED } }
        val originalArticles = runBlocking { dao.articles().filter { it.source == originalSource.id }
            .sortedWith(compareByDescending<DirectArticle> { it.published }.thenBy { it.id }) }
        assertEquals(3, originalArticles.size)
        assertEquals(3, originalArticles.map { it.originalUrl.substringBefore('?') }.distinct().size)
        assertEquals("https://www.ifanr.com/1683053", originalArticles.first().originalUrl.substringBefore('?'))
        assertEquals(69, originalArticles.sumOf { it.totalImages })
        originalArticles.forEach {
            assertTrue(it.originalUrl.startsWith("https://www.ifanr.com/"))
            assertTrue(it.totalImages > 0)
            assertEquals(it.totalImages, Regex("<img\\b", RegexOption.IGNORE_CASE).findAll(it.body).count())
        }
        fun savedPosition(id: String): JSONObject = JSONObject(
            context.getSharedPreferences("reader_private", Context.MODE_PRIVATE).getString("state", "{}") ?: "{}"
        ).getJSONObject("positions").getJSONObject(id)
        val initialPositions = originalArticles.associate { article ->
            val position = savedPosition(article.id)
            assertEquals("Successful capture must have saved end for ${article.id}",
                1.0, position.getDouble("fraction"), 0.0)
            assertTrue(position.getInt("range") > 0)
            assertTrue(kotlin.math.abs(position.getInt("pixels") - position.getInt("range")) <= 2)
            article.id to position
        }
        val capture = JSONObject(File(context.filesDir, "direct-real-viewport/run.json").readText())
        assertEquals("screenshots_collected_pixel_validation_pending", capture.getString("status"))
        assertTrue("Root must force-stop between capture and verification",
            capture.getInt("processPid") != android.os.Process.myPid() ||
                capture.getLong("processStartElapsedRealtimeMs") != android.os.Process.getStartElapsedRealtime())
        val capturedArticles = capture.getJSONArray("articles")
        assertEquals(3, capturedArticles.length())
        originalArticles.forEach { article ->
            val captured = (0 until capturedArticles.length()).map { capturedArticles.getJSONObject(it) }
                .single { it.getString("id") == article.id }
            assertEquals(article.originalUrl, captured.getString("link"))
            assertTrue(captured.getBoolean("reachedEnd"))
        }
        val run = "${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}"
        val directory = File(context.filesDir, "direct-real-cold-end/$run").apply { check(mkdirs()) }
        val report = JSONObject().put("run", run).put("captureRun", capture.getString("run"))
            .put("processPid", android.os.Process.myPid())
            .put("processStartElapsedRealtimeMs", android.os.Process.getStartElapsedRealtime())
            .put("captureProcessPid", capture.getInt("processPid"))
            .put("captureProcessStartElapsedRealtimeMs", capture.getLong("processStartElapsedRealtimeMs"))
            .put("deviceApi", Build.VERSION.SDK_INT).put("status", "verifying")
            .put("rangeDefinition", "max(0, WebView provider computeVerticalScrollRange() - computeVerticalScrollExtent()); native scroll pixels")
            .put("articles", JSONArray())
        fun save() { File(directory, "run.json").writeText(report.toString(2)) }
        save()
        try {
            checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.serviceInfo)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val sync = DeviceFlowSync(compose, scenario)
                fun touch(text: String) {
                    val node = compose.onNodeWithText(text, useUnmergedTree = true)
                    sync.settled(node)
                    node.performTouchInput { click() }
                }
                fun refreshIdle() {
                    compose.waitUntil(180_000) {
                        compose.onAllNodes(hasContentDescription("刷新") and isEnabled()).fetchSemanticsNodes().size == 1
                    }
                    compose.onNodeWithContentDescription("刷新").assertIsEnabled()
                    requireNetwork()
                }
                sync.login()
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithContentDescription("本地 RSS · 无需账号").fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithContentDescription("本地 RSS · 无需账号").performScrollTo()
                touchControl(sync, "本地 RSS · 无需账号")
                sync.list()
                refreshIdle()
                compose.onNodeWithContentDescription("选择订阅").performTouchInput { click() }
                val sourceMatcher = hasContentDescription(originalSource.title)
                compose.waitUntil(10_000) { compose.onAllNodes(sourceMatcher).fetchSemanticsNodes().size == 1 }
                compose.onNode(sourceMatcher).performScrollTo()
                sync.settled(compose.onNode(sourceMatcher))
                compose.onNode(sourceMatcher).performTouchInput { click() }
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("关闭").fetchSemanticsNodes().isEmpty() }
                sync.list()
                refreshIdle()
                val source = checkNotNull(runBlocking { dao.subscription(originalSource.id) })
                val articles = runBlocking { dao.articles().filter { it.source == source.id }
                    .sortedWith(compareByDescending<DirectArticle> { it.published }.thenBy { it.id }) }
                assertEquals("Automatic refresh changed source identity/metadata", originalSource,
                    source.copy(status = originalSource.status, lastSuccess = originalSource.lastSuccess))
                assertEquals("Automatic refresh changed posts/cache/stars", originalArticles, articles)
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("全部"))
                touchControl(sync, "全部")
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(articles.first().title))
                touch(articles.first().title)
                val ends = mutableMapOf<String, ViewportState>()
                articles.forEachIndexed { index, article ->
                    sync.reader(article.title)
                    // No reader touch, scrollTo, end jump or preference writes: observe automatic restore only.
                    val evidence = JSONObject().put("id", article.id).put("link", article.originalUrl)
                        .put("savedBefore", initialPositions.getValue(article.id))
                    report.getJSONArray("articles").put(evidence)
                    save()
                    val started = SystemClock.uptimeMillis()
                    val deadline = started + 90_000
                    var previous: ViewportState? = null
                    var stableSince = started
                    var end: ViewportState? = null
                    while (SystemClock.uptimeMillis() < deadline) {
                        requireNetwork()
                        val drawn = CountDownLatch(1)
                        scenario.onActivity {
                            val web = web(it)
                            web.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                                override fun onComplete(requestId: Long) {
                                    web.postOnAnimation { web.postOnAnimation { drawn.countDown() } }
                                }
                            })
                        }
                        val remaining = deadline - SystemClock.uptimeMillis()
                        if (remaining <= 0) break
                        compose.waitUntil(minOf(5_000L, remaining)) { drawn.count == 0L }
                        val state = viewportState(scenario)
                        val now = SystemClock.uptimeMillis()
                        val ready = state.progress == 100 && state.range > 0 && !state.layoutRequested &&
                            !state.canScrollDown && kotlin.math.abs(state.scrollY - state.range) <= 2
                        if (!ready || previous != state) stableSince = now
                        previous = state
                        // Include the existing bounded production restore passes, not just an early frame.
                        if (ready && now - stableSince >= 1_500 && now - started >= 6_000 && now < deadline) {
                            end = state
                            break
                        }
                    }
                    evidence.put("lastViewport", previous?.json())
                    save()
                    if (end == null) {
                        // Already in the public reader; never capture login/account surfaces.
                        val failureName = "article-${index + 1}-restore-failure.png"
                        runCatching {
                            screenshot(scenario, File(directory, failureName))
                            evidence.put("failureScreenshot", failureName)
                        }
                        save()
                    }
                    assertNotNull("Automatic restore must settle at true native end within 90s", end)
                    val restored = checkNotNull(end)
                    assertFalse(restored.canScrollDown)
                    assertTrue(kotlin.math.abs(restored.scrollY - restored.range) <= 2)
                    val name = "article-${index + 1}-restored-end.png"
                    screenshot(scenario, File(directory, name))
                    assertEquals("Restored viewport changed during screenshot", restored, viewportState(scenario))
                    fun savedAtEnd(): Boolean {
                        val saved = savedPosition(article.id)
                        return saved.getDouble("fraction") == 1.0 &&
                            kotlin.math.abs(saved.getInt("pixels") - restored.range) <= 2 &&
                            saved.getInt("range") == restored.range
                    }
                    val remaining = deadline - SystemClock.uptimeMillis()
                    assertTrue("Native restore/persistence exceeded 90s", remaining > 0)
                    compose.waitUntil(minOf(5_000L, remaining)) { savedAtEnd() }
                    assertTrue("Actual saved preferences must retain native end", savedAtEnd())
                    ends[article.id] = restored
                    evidence.put("viewport", restored.json()).put("savedAfter", savedPosition(article.id))
                        .put("screenshot", name).put("automaticEndRestored", true)
                    save()
                    if (index < articles.lastIndex) touchControl(sync, "下一篇")
                }
                compose.onNodeWithContentDescription("已是末篇").assertIsNotEnabled()
                requireNetwork()
                val after = runBlocking { dao.articles().filter { it.source == source.id } }
                assertEquals(articles.map { it.id }.toSet(), after.map { it.id }.toSet())
                articles.forEach { before ->
                    assertEquals("Only isRead may change", before.copy(isRead = true), after.single { it.id == before.id })
                    val saved = savedPosition(before.id)
                    assertEquals(1.0, saved.getDouble("fraction"), 0.0)
                    assertEquals(ends.getValue(before.id).range, saved.getInt("range"))
                    assertTrue(kotlin.math.abs(saved.getInt("pixels") - saved.getInt("range")) <= 2)
                }
                assertEquals(source, runBlocking { dao.subscription(source.id) })
                assertFalse(access.authRepository().hasSession())
                report.put("daoPreservedExceptRead", true)
            }
            report.put("status", "automatic_native_end_restored")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure", failure.toString())
            throw failure
        } finally {
            save()
        }
    }

    private data class ViewportState(
        val scrollY: Int, val range: Int, val contentHeight: Int, val density: Float,
        val scale: Float, val width: Int, val height: Int, val screenRect: List<Int>,
        val visibleHeight: Int, val progress: Int, val layoutRequested: Boolean, val canScrollDown: Boolean
    ) {
        fun layoutKey() = listOf(range, contentHeight, density, scale, width, height, screenRect)
        fun json() = JSONObject().put("scrollY", scrollY).put("range", range)
            .put("contentHeight", contentHeight).put("density", density).put("webScale", scale)
            .put("width", width).put("height", height).put("webScreenRect", JSONArray(screenRect))
            .put("visibleHeight", visibleHeight).put("progress", progress).put("canScrollDown", canScrollDown)
    }

    @Suppress("DEPRECATION") // Scale is diagnostic only; range comes from the native provider.
    private fun viewportState(scenario: ActivityScenario<MainActivity>): ViewportState {
        var state: ViewportState? = null
        scenario.onActivity {
            val web = web(it)
            val location = IntArray(2)
            web.getLocationOnScreen(location)
            val visible = android.graphics.Rect()
            assertTrue("Reader WebView must be visible", web.getGlobalVisibleRect(visible))
            assertTrue(web.width > 0 && web.height > 0 && visible.height() > 0)
            state = ViewportState(web.scrollY,
                (web as ReaderWebView).nativeMaxScrollOffset,
                web.contentHeight, web.resources.displayMetrics.density, web.scale, web.width, web.height,
                listOf(location[0], location[1], location[0] + web.width, location[1] + web.height),
                visible.height(), web.progress, web.isLayoutRequested, web.canScrollVertically(1))
        }
        return checkNotNull(state)
    }

    private fun awaitViewportStable(
        scenario: ActivityScenario<MainActivity>, requireNetwork: () -> Unit,
        deadline: Long = SystemClock.uptimeMillis() + 90_000
    ): ViewportState {
        var previous: ViewportState? = null
        var stableSince = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() < deadline) {
            requireNetwork()
            awaitNativeFrame(scenario)
            val state = viewportState(scenario)
            val ready = state.progress == 100 && state.contentHeight > 0 && !state.layoutRequested
            if (!ready || state != previous) stableSince = SystemClock.uptimeMillis()
            previous = state
            val now = SystemClock.uptimeMillis()
            if (ready && now - stableSince >= 1_500 && now < deadline) return state
            pollWithComposeIdle(100)
        }
        error("Native page load/dimensions did not stabilize before deadline")
    }

    private fun normalized(text: String) = text.replace(Regex("[\\s\\p{Z}]+"), "")
    private fun sourceText(html: String): List<String> = Html.fromHtml(
        html.replace(Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE), ""), Html.FROM_HTML_MODE_LEGACY
    ).toString().lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
    private fun web(activity: MainActivity): WebView = views(activity.window.decorView)
        .filterIsInstance<WebView>().single().also {
            assertFalse(it.settings.javaScriptEnabled)
            assertFalse(it.settings.allowFileAccess)
            assertFalse(it.settings.allowContentAccess)
        }

    private fun snapshot(scenario: ActivityScenario<MainActivity>): List<NativeDocumentSnapshot.Entry> {
        compose.waitForIdle()
        val probe = NativeDocumentSnapshotProbe()
        scenario.onActivity { web(it).onProvideVirtualStructure(probe.sink) }
        // Never block main waiting for Chromium's asynchronous document commit.
        assertTrue("Full native document callback timed out", probe.awaitCompletion())
        var entries: List<NativeDocumentSnapshot.Entry> = emptyList()
        scenario.onActivity { entries = probe.flatten() }
        return entries
    }

    private fun images(entries: List<NativeDocumentSnapshot.Entry>): List<NativeDocumentSnapshot.Entry> {
        val tagged = entries.filter { it.html?.tag.equals("img", true) }
        // Some providers repeat an img as a structural descendant. Do not collapse siblings
        // or equal src values: repeated real images each count separately.
        return tagged.filter { child -> tagged.none { parent -> child.path.startsWith(parent.path + "/") } }
    }

    private fun recordImages(json: JSONObject, entries: List<NativeDocumentSnapshot.Entry>) {
        val images = images(entries)
        json.put("nativeImageCount", images.size).put("imageRectangles", JSONArray().apply {
            images.forEach { entry -> put(JSONArray(listOf(entry.bounds.left, entry.bounds.top, entry.bounds.right, entry.bounds.bottom))) }
        })
        // Inspect attributes only when actually provided. Never save data URIs or infer src.
        json.put("nativeImagesWithSuppliedSrc", images.count { entry ->
            entry.html?.attributes?.any { it.first.equals("src", true) && it.second.isNotBlank() } == true
        })
    }

    private fun scrollToEndAndDraw(scenario: ActivityScenario<MainActivity>) {
        var previous: Pair<Int, Int>? = null
        var stableSince = SystemClock.uptimeMillis()
        val deadline = stableSince + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            compose.waitForIdle()
            val drawn = CountDownLatch(1)
            scenario.onActivity {
                val web = web(it)
                web.scrollTo(0, Int.MAX_VALUE) // WebView clamps to its actual native range.
                web.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        web.postOnAnimation { web.postOnAnimation { drawn.countDown() } }
                    }
                })
            }
            compose.waitUntil(5_000) {
                compose.waitForIdle()
                drawn.count == 0L
            }
            var sample: Pair<Int, Int>? = null
            var end = false
            scenario.onActivity {
                val web = web(it)
                sample = web.scrollY to web.contentHeight
                end = !web.canScrollVertically(1) && !web.isLayoutRequested
            }
            if (!end || sample != previous) stableSince = SystemClock.uptimeMillis()
            previous = sample
            if (end && SystemClock.uptimeMillis() - stableSince >= 700) return
            pollWithComposeIdle(100)
        }
        fail("Native document end never settled")
    }

    /** Yield real time to Chromium while continuing to advance Compose navigation/layout. */
    private fun pollWithComposeIdle(milliseconds: Long) {
        val until = SystemClock.uptimeMillis() + milliseconds
        compose.waitUntil(milliseconds + 5_000) {
            compose.waitForIdle()
            SystemClock.uptimeMillis() >= until
        }
    }

    private fun awaitNativeFrame(scenario: ActivityScenario<MainActivity>) {
        compose.waitForIdle()
        val drawn = CountDownLatch(1)
        scenario.onActivity {
            val web = web(it)
            web.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    web.postOnAnimation { web.postOnAnimation { drawn.countDown() } }
                }
            })
        }
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            drawn.count == 0L
        }
        compose.waitForIdle()
    }

    private fun screenshot(scenario: ActivityScenario<MainActivity>, file: File) {
        // Caller has settled the reader transition; now require a native drawn frame.
        awaitNativeFrame(scenario)
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}
