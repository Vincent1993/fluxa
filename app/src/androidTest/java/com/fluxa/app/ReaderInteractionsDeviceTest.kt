package com.fluxa.app

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxa.app.data.local.*
import com.fluxa.app.data.directrss.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Explicit independent processes only. Opt-in owned-fixture screenshots; no network, auth writes or global deletion. */
class ReaderInteractionsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val prefix = "reeder-interaction-2026-10-08-"
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("reader_private", Context.MODE_PRIVATE)
    private val ownership get() = File(context.filesDir, "${prefix}owner.json")
    private val evidence get() = File(context.filesDir, "${prefix}verified-state.json")
    private val ownedPrefix = "direct-rss:$prefix"
    private fun id(i: Int) = "$ownedPrefix$i"
    private fun title(i: Int) = "$prefix article $i"
    private val feed = "${ownedPrefix}feed"
    private fun html(i: Int): String = when (i) {
        4 -> "<p>Short offline article.</p>"
        5 -> ""
        else -> buildString {
            append("<img width='64' height='64' alt='Owned fixture pixel' src='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jV1kAAAAASUVORK5CYII='>")
            append("<table><tr><th>Fixture</th><th>Value</th></tr><tr><td>Offline</td><td>Native table</td></tr></table>")
            repeat(45) { append("<p>Owned article $i paragraph $it. Reading along the river preserves each native paragraph. 离线阅读，保留真实位置与段落。</p>") }
        }
    }

    @Test fun phasedInteractions(): Unit = runBlocking {
        // Guard precedes ALL context, database, Activity, preferences and auth access.
        val phase = InstrumentationRegistry.getArguments().getString("readerInteractionsPhase")
        assumeTrue("Opt in with readerInteractionsPhase=seed|verify|cleanup|focus-probe", phase != null)
        require(phase in listOf("seed", "verify", "cleanup", "focus-probe"))
        check(SecureTokenStore(context).getNewsBlurSession() == null) { "Signed-out device required" }
        check((context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork == null) {
            "Offline device required; test does not change connectivity"
        }
        val db = Room.databaseBuilder(context, DirectRssDatabase::class.java, "direct-rss.db")
            .addMigrations(DirectRssDatabase.MIGRATION_1_2, DirectRssDatabase.MIGRATION_2_3, DirectRssDatabase.MIGRATION_3_4, DirectRssDatabase.MIGRATION_4_5, DirectRssDatabase.MIGRATION_5_6).build()
        val ids = (0..5).map(::id)
        try {
            when (phase) {
                "seed", "focus-probe" -> {
                    check(!ownership.exists() && !evidence.exists()) { "Unfinished ownership: cleanup in a fresh process first" }
                    check(ids.all { db.dao().article(it) == null })
                    check(db.dao().subscriptions().none { it.id == feed })
                    check(db.dao().articles().none { it.source == feed })
                    // FeedListViewModel's existing entry refresh writes even offline
                    // failure statuses. Do not launch it against any user's feeds.
                    check(db.dao().subscriptions().isEmpty()) {
                        "Use an isolated signed-out fixture device: list entry refresh would mutate non-owned subscriptions"
                    }
                    val original = JSONObject().put("prefix", prefix).put("pid", android.os.Process.myPid())
                        .put("backend", "direct-rss-v1").put("outsideHash", outsideHash(db))
                        .put("present", prefs.contains("state")).put("state", prefs.getString("state", null) ?: JSONObject.NULL)
                    check(ownership.createNewFile())
                    ownership.writeText(original.toString())
                    db.dao().putSubscription(DirectSubscription(feed, prefix, "https://example.invalid/owned-feed", false))
                    (0..5).forEach {
                        db.dao().putArticle(DirectArticle(id(it), id(it), feed, title(it),
                            1800000000L - it, html(it), "", cachedImages = if (it < 4) 1 else 0,
                            totalImages = if (it < 4) 1 else 0))
                    }
                    if (phase == "focus-probe") {
                        try { focusProbe() }
                        finally { assertEquals(original.getString("outsideHash"), outsideHash(db)) }
                    } else seed(db)
                    assertEquals(original.getString("outsideHash"), outsideHash(db))
                }
                "verify" -> {
                    check(ownership.exists() && evidence.exists())
                    check(JSONObject(ownership.readText()).optString("backend") == "direct-rss-v1") {
                        "Legacy ownership/evidence retained; this fixture cannot modify it"
                    }
                    check(db.dao().subscriptions().all { it.id == feed }) {
                        "Non-owned subscriptions appeared; refusing Activity entry refresh"
                    }
                    val saved = JSONObject(evidence.readText())
                    assertNotEquals(saved.getInt("pid"), android.os.Process.myPid())
                    assertEquals(saved.getString("position"), JSONObject(prefs.getString("state", "{}")!!)
                        .getJSONObject("positions").getJSONObject(id(0)).toString())
                    assertEquals(JSONObject(ownership.readText()).getString("outsideHash"), outsideHash(db))
                    assertTrue(db.dao().article(id(0))!!.isStarred)
                    assertTrue(db.dao().article(id(0))!!.isRead)
                    evidence.writeText(saved.put("lastPid", android.os.Process.myPid()).toString())
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        val sync = InteractionSync(scenario)
                        enter(sync); sync.article(title(0), click = true); sync.reader(title(0))
                        stable(sync, scenario)
                        assertEquals(saved.getInt("y"), sample(scenario).y)
                        security(scenario)
                    }
                    assertEquals(JSONObject(ownership.readText()).getString("outsideHash"), outsideHash(db))
                }
                "cleanup" -> {
                    check(ownership.exists()) { "No ownership record: refusing cleanup" }
                    val original = JSONObject(ownership.readText())
                    check(original.getString("prefix") == prefix)
                    check(original.optString("backend") == "direct-rss-v1") {
                        "Legacy ownership/evidence retained; this fixture cannot clean it"
                    }
                    check(original.getInt("pid") != android.os.Process.myPid()) { "Cleanup must be a fresh process" }
                    if (evidence.exists()) check(JSONObject(evidence.readText()).optInt("lastPid", -1) != android.os.Process.myPid())
                    // Never construct an Activity or the Hilt reader writer in cleanup.
                    val beforeCleanup = outsideHash(db)
                    ids.forEach { db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE id = ?", arrayOf(it)) }
                    db.openHelper.writableDatabase.execSQL("DELETE FROM subscriptions WHERE id = ?", arrayOf(feed))
                    val editor = prefs.edit()
                    if (original.getBoolean("present")) editor.putString("state", original.getString("state")) else editor.remove("state")
                    check(editor.commit())
                    assertEquals(original.getBoolean("present"), prefs.contains("state"))
                    if (original.getBoolean("present")) assertEquals(original.getString("state"), prefs.getString("state", null))
                    assertEquals(beforeCleanup, outsideHash(db))
                    assertEquals(original.getString("outsideHash"), outsideHash(db))
                    if (evidence.exists()) check(evidence.delete())
                    check(ownership.delete())
                }
            }
        } finally { db.close() }
    }

    // Hash all fields (including marks) outside the exact owned set, never export rows.
    private suspend fun outsideHash(db: DirectRssDatabase): String {
        val ids = (0..5).map(::id).toSet()
        val data = db.dao().articles().filterNot { it.id in ids }.toString() +
            db.dao().subscriptions().filterNot { it.id == feed }.toString()
        return java.security.MessageDigest.getInstance("SHA-256").digest(data.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    private fun enter(sync: InteractionSync) {
        sync.configuration()
        sync.login(); sync.scrollAndClick("本地 RSS · 无需账号"); sync.list()
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNodeWithText("搜索文章").performScrollTo().performTextInput(prefix)
        pressBack()
        sync.await { compose.onAllNodesWithText(title(0)).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun focusProbe() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var observer: android.view.ViewTreeObserver? = null
                val listener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { old, new ->
                    android.util.Log.i("FluxaFocusProof",
                        "uptime=${SystemClock.uptimeMillis()} globalFocus old=${old?.javaClass?.name} new=${new?.javaClass?.name}")
                }
                try {
                    scenario.onActivity { activity ->
                        observer = activity.window.decorView.viewTreeObserver
                        observer!!.addOnGlobalFocusChangeListener(listener)
                    }
                    val sync = InteractionSync(scenario)
                    enter(sync)
                    val anchor = sync.listAnchor(title(0), "focus-probe-before")
                    compose.onNodeWithText(title(0), substring = false).performTouchInput { click() }
                    sync.reader(title(0)); stable(sync, scenario)
                    android.util.Log.i("FluxaFocusProof", "instrumentation back before uptime=${SystemClock.uptimeMillis()}")
                    pressBack()
                    android.util.Log.i("FluxaFocusProof", "instrumentation back after uptime=${SystemClock.uptimeMillis()}")
                    android.util.Log.i("FluxaFocusProof", "immediate uptime=${SystemClock.uptimeMillis()} ${sync.ownedListGeometry()}")
                    sync.list()
                    // Observe settlement without trying to reveal missing rows or dismiss the IME.
                    var previous = ""
                    var since = SystemClock.uptimeMillis()
                    sync.await {
                        val current = sync.ownedListGeometry()
                        if (current != previous) since = SystemClock.uptimeMillis()
                        previous = current
                        SystemClock.uptimeMillis() - since >= 750
                    }
                    android.util.Log.i("FluxaFocusProof", "settled uptime=${SystemClock.uptimeMillis()} ${sync.ownedListGeometry()}")
                    var imeHidden = false
                    scenario.onActivity {
                        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                        imeHidden = insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false
                    }
                    val focused = compose.onAllNodes(hasSetTextAction() and hasText("搜索文章", substring = false))
                        .fetchSemanticsNodes().any {
                            val key = androidx.compose.ui.semantics.SemanticsProperties.Focused
                            it.config.contains(key) && it.config[key]
                        }
                    val returned = sync.listAnchor(title(0), "focus-probe-after")
                    assertEquals("Original list anchor", anchor, returned, 2f)
                    assertTrue("Returned IME must be hidden", imeHidden)
                    assertFalse("Returned search must not be focused", focused)

                    // Only AFTER strict Back/anchor/no-IME checks: search must still
                    // accept deliberate touch focus and real Android keyboard input.
                    val search = compose.onNode(hasSetTextAction() and hasText("搜索文章", substring = false))
                    val editable = androidx.compose.ui.semantics.SemanticsProperties.EditableText
                    assertEquals("Back preserves the query prefix", prefix,
                        search.fetchSemanticsNode().config[editable].text)
                    search.performScrollTo()
                    sync.settled(search)
                    search.performTouchInput { click() }
                    fun searchAndImeFocused(): Boolean {
                        val key = androidx.compose.ui.semantics.SemanticsProperties.Focused
                        val config = search.fetchSemanticsNode().config
                        var imeVisible = false
                        scenario.onActivity {
                            imeVisible = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                        }
                        return config.contains(key) && config[key] && imeVisible
                    }
                    sync.await { searchAndImeFocused() }
                    search.assertIsFocused()
                    assertEquals("Touch preserves the query prefix", prefix,
                        search.fetchSemanticsNode().config[editable].text)
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    // Native right-arrow dispatch saturates at the true end, regardless
                    // of touch location. Verify selection before sending the native edit.
                    val selection = androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange
                    fun typingDiagnostics(stage: String) {
                        val config = search.fetchSemanticsNode().config
                        val value = config[editable].text
                        val key = androidx.compose.ui.semantics.SemanticsProperties.Focused
                        var nativeIme = false
                        scenario.onActivity {
                            nativeIme = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                        }
                        // Only emit the owned fixture alphabet (prefix plus injected 'a').
                        // Unexpected text is redacted, never an arbitrary user query dump.
                        val knownValues = (0..prefix.length).map { index ->
                            prefix.take(index) + "a" + prefix.drop(index)
                        } + listOf(prefix, "", "a")
                        val safeValue = if (value in knownValues) JSONObject.quote(value)
                            else "<redacted; length=${value.length}>"
                        android.util.Log.i("FluxaFocusProof", "$stage uptime=${SystemClock.uptimeMillis()} " +
                            "editable=$safeValue selection=${if (config.contains(selection)) config[selection] else "unavailable"} " +
                            "focused=${config.contains(key) && config[key]} nativeIME=$nativeIme " +
                            "exactPrefix=${value == prefix} exactPrefixPlusA=${value == prefix + "a"}")
                    }
                    typingDiagnostics("before native DPAD_RIGHT via Instrumentation.sendKeyDownUpSync")
                    try {
                        repeat(prefix.length) {
                            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                            // Commit controlled TextField selection before the next native event.
                            compose.waitForIdle()
                        }
                        sync.await {
                            val config = search.fetchSemanticsNode().config
                            config.contains(selection) && config[selection].start == prefix.length &&
                                config[selection].end == prefix.length
                        }
                        val positioned = search.fetchSemanticsNode().config
                        assertEquals("Native cursor movement preserves the query prefix", prefix,
                            positioned[editable].text)
                        assertEquals("Native caret start reaches true end", prefix.length, positioned[selection].start)
                        assertEquals("Native caret end reaches true end", prefix.length, positioned[selection].end)
                        typingDiagnostics("native caret at true end before KEYCODE_A")
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_A)
                        sync.await {
                            search.fetchSemanticsNode().config[editable].text == prefix + "a" && searchAndImeFocused()
                        }
                    } catch (failure: Throwable) {
                        // Capture the final failed wait, without retrying or weakening it.
                        runCatching { typingDiagnostics("final failed native typing wait") }
                            .onFailure { android.util.Log.e("FluxaFocusProof", "Typing diagnostic capture failed", it) }
                        throw failure
                    }
                    typingDiagnostics("native typing wait passed")
                    search.assertIsFocused()
                    assertEquals("Native KEYCODE_A edits the real query", prefix + "a",
                        search.fetchSemanticsNode().config[editable].text)
                } finally {
                    scenario.onActivity { activity ->
                        observer?.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(listener)
                        activity.window.decorView.viewTreeObserver.takeIf { it.isAlive }
                            ?.removeOnGlobalFocusChangeListener(listener)
                    }
                }
        }
    }

    private fun controls() {
        if (compose.onAllNodesWithContentDescription("显示阅读工具").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription("显示阅读工具").performTouchInput { click() }
    }
    private fun click(sync: InteractionSync, text: String) {
        controls()
        val node = compose.onNodeWithContentDescription(text, useUnmergedTree = true)
        sync.settled(node)
        node.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,
                androidx.compose.ui.semantics.Role.Button))
            .assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.Text))
        node.performTouchInput { click() }
    }
    private fun seed(db: DirectRssDatabase) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val sync = InteractionSync(scenario)
            fun drag(up: Boolean, distance: Float = .4f) = nativeDrag(scenario, up, distance)
            enter(sync)
            val row = compose.onNode(hasTestTag("article-swipe") and hasText(title(0)))
            row.performScrollTo()
            row.performTouchInput {
                val shallowRelease = minOf(176.dp.toPx(), (width - 48.dp.toPx()) * .5f) * .75f + 12.dp.toPx()
                swipe(Offset(width * .8f, height / 2f), Offset(width * .8f - shallowRelease, height / 2f), 600)
            }
            compose.onNodeWithTag("reader-content").assertDoesNotExist()
            row.performTouchInput {
                val shallowRelease = minOf(176.dp.toPx(), (width - 48.dp.toPx()) * .5f) * .75f + 12.dp.toPx()
                swipe(Offset(width * .2f, height / 2f), Offset(width * .2f + shallowRelease, height / 2f), 600)
            }
            compose.onNodeWithTag("reader-content").assertDoesNotExist()
            sync.await { runBlocking { db.dao().article(id(0))!!.let { it.isRead && it.isStarred } } }
            val anchor = sync.listAnchor(title(0), "before-open")
            // Open the measured node without another scroll-to changing the anchor.
            compose.onNodeWithText(title(0), substring = false).performTouchInput { click() }
            sync.reader(title(0)); stable(sync, scenario)
            compose.onNodeWithContentDescription("已是首篇").assertIsNotEnabled()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            compose.onNodeWithContentDescription("下一篇").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val probe = NativeDocumentSnapshotProbe()
            scenario.onActivity { activity -> views(activity.window.decorView).filterIsInstance<WebView>().single()
                .onProvideVirtualStructure(probe.sink) }
            assertTrue(probe.awaitCompletion())
            assertTrue("Native body, not host semantics", probe.flatten().any { it.text?.contains("Owned article 0 paragraph") == true })
            val beforeDrag = sample(scenario)
            val height = beforeDrag.height
            drag(up = true)
            stable(sync, scenario)
            try {
                val afterDrag = sample(scenario)
                assertTrue("Auto-hide requires real native forward scrolling: $beforeDrag -> $afterDrag",
                    afterDrag.y > beforeDrag.y)
                assertTrue("Auto-hide must be checked before native bottom: $afterDrag", afterDrag.down)
                compose.onNodeWithContentDescription("显示阅读工具").assertIsDisplayed()
            } catch (failure: AssertionError) {
                throw AssertionError("Native auto-hide: before=$beforeDrag after=${sample(scenario)}; " +
                    "showControlsNodes=${compose.onAllNodesWithContentDescription("显示阅读工具").fetchSemanticsNodes().size}; " +
                    "nextNodes=${compose.onAllNodesWithContentDescription("下一篇").fetchSemanticsNodes().size}", failure)
            }
            assertEquals(height, sample(scenario).height)
            screenshot(scenario, "middle-hidden-tools")
            val beforeShow = sample(scenario)
            controls(); assertEquals(beforeShow, sample(scenario))
            click(sync, "取消收藏"); click(sync, "收藏")
            sync.await { runBlocking { db.dao().article(id(0))!!.isStarred } }
            stable(sync, scenario)
            assertEquals("Star must not reload or move native content", beforeShow, sample(scenario))
            compose.onNodeWithContentDescription("阅读设置").performClick()
            compose.onNodeWithText("阅读预览").assertIsDisplayed()
            pressBack(); compose.onNodeWithText("阅读预览").assertDoesNotExist()
            // Hide again via native interior scrolling, then restore by a genuine
            // reverse scroll, not the show-controls button or arrival at the top.
            val beforeReverseSetup = sample(scenario)
            drag(up = true); stable(sync, scenario)
            val beforeReverse = sample(scenario)
            assertTrue("Reverse setup must scroll forward", beforeReverse.y > beforeReverseSetup.y)
            assertTrue("Reverse setup must remain interior", beforeReverse.down)
            compose.onNodeWithContentDescription("显示阅读工具").assertIsDisplayed()
            assertEquals(height, beforeReverse.height)
            drag(up = false, distance = .2f); stable(sync, scenario)
            val afterReverse = sample(scenario)
            assertTrue("Reverse must change native y", afterReverse.y < beforeReverse.y)
            assertTrue("Reverse restore must not rely on reaching top", afterReverse.y > 0)
            assertTrue("Reverse restore must remain before bottom", afterReverse.down)
            compose.onNodeWithContentDescription("下一篇").assertIsDisplayed()
            compose.onNodeWithContentDescription("显示阅读工具").assertDoesNotExist()
            assertEquals(height, afterReverse.height)
            screenshot(scenario, "reverse-restored-tools")
            // A normal gesture that merely reaches bottom MUST NOT turn.
            var bottom = false
            for (attempt in 0..80) {
                stable(sync, scenario)
                if (!sample(scenario).down) { bottom = true; break }
                drag(up = true)
                assertEquals(title(0), sample(scenario).title)
            }
            assertTrue("Bounded real gestures must reach native bottom: ${sample(scenario)}", bottom)
            diagnostic("initial-bottom", scenario)
            val expectedBottom = sample(scenario)
            sync.await { JSONObject(storedPosition(0)).getInt("pixels") == expectedBottom.y }
            val expectedBottomJson = storedPosition(0)
            // Actual AndroidView input, not reducer calls. Each rejected stream stays
            // on this native page; no programmatic scroll is used to establish a boundary.
            compose.onNodeWithTag("reader-content").performTouchInput {
                down(Offset(width / 2f, height * .7f))
                moveTo(Offset(width / 2f, height * .3f), 400)
                cancel()
            }
            stable(sync, scenario)
            assertEquals(title(0), sample(scenario).title)
            compose.onNodeWithTag("reader-content").performTouchInput {
                swipe(Offset(1f, height * .7f), Offset(1f, height * .3f), 400)
            }
            stable(sync, scenario)
            assertEquals(title(0), sample(scenario).title)
            compose.onNodeWithTag("reader-content").performTouchInput {
                swipe(Offset(width * .25f, height * .65f), Offset(width * .75f, height * .55f), 400)
            }
            stable(sync, scenario)
            assertEquals(title(0), sample(scenario).title)
            compose.onNodeWithTag("reader-content").performTouchInput {
                down(0, Offset(width * .4f, height * .7f))
                down(1, Offset(width * .6f, height * .7f))
                moveTo(0, Offset(width * .3f, height * .3f), 200)
                moveTo(1, Offset(width * .7f, height * .3f), 200)
                up(1); up(0)
            }
            stable(sync, scenario)
            assertEquals(title(0), sample(scenario).title)
            // Also introduce a second pointer AFTER the pull has canceled Chromium.
            // Its remaining pointer UP must stay consumed, not become a native click.
            compose.onNodeWithTag("reader-content").performTouchInput {
                down(0, Offset(width * .5f, height * .7f))
                moveTo(0, Offset(width * .5f, height * .4f), 300)
                down(1, Offset(width * .65f, height * .4f))
                up(1); up(0)
            }
            stable(sync, scenario)
            assertEquals(title(0), sample(scenario).title)
            // Pinch rejection is required even if Chromium consumes the native stream.
            assertFalse("Rejected inputs must leave native bottom available", sample(scenario).down)
            compose.onNodeWithTag("reader-content").performTouchInput {
                down(Offset(width / 2f, height * .7f))
                moveTo(Offset(width / 2f, height * .3f), 400)
                moveTo(Offset(width / 2f, height * .65f), 400)
                up()
            }
            assertEquals(title(0), sample(scenario).title)
            stable(sync, scenario)
            diagnostic("after-rejections-before-queue", scenario)
            assertFalse("New release gesture must start at real native bottom: ${sample(scenario)}", sample(scenario).down)
            // Queue a second pull immediately around replacement, without waiting for
            // the new page to settle. It must neither skip forward nor bounce backward.
            compose.onNodeWithTag("reader-content").performTouchInput {
                swipe(Offset(width / 2f, height * .7f), Offset(width / 2f, height * .3f), 300)
                swipe(Offset(width / 2f, height * .3f), Offset(width / 2f, height * .7f), 120)
            }
            sync.reader(title(1)); stable(sync, scenario)
            assertEquals(title(1), sample(scenario).title)
            diagnostic("page1-after-queue", scenario)
            assertEquals("Previous-page pull must start at native top: ${sample(scenario)}", 0, sample(scenario).y)
            assertEquals("Queued reverse must not overwrite retired article 0", expectedBottomJson, storedPosition(0))
            val storedBeforeReturn = storedPosition(0)
            boundaryDrag(up = false)
            sync.reader(title(0)); stable(sync, scenario)
            diagnostic("page0-return-storedBefore=$storedBeforeReturn", scenario)
            assertEquals("Return must restore exact native bottom before any fresh setup", expectedBottom, sample(scenario))
            assertEquals("Return preserves exact saved position JSON", expectedBottomJson, storedPosition(0))
            assertFalse("Next-page pull must start at native bottom: ${sample(scenario)}", sample(scenario).down)
            boundaryDrag(up = true)
            sync.reader(title(1)); stable(sync, scenario)
            // Another upward swipe begins at TOP, not bottom: cannot skip to page 2.
            drag(up = true); stable(sync, scenario)
            assertEquals(title(1), sample(scenario).title)
            click(sync, "上一篇"); sync.reader(title(0)); stable(sync, scenario)
            assertEquals("Toolbar return restores exact old native position", expectedBottom, sample(scenario))
            assertEquals("Toolbar transition cannot write another article", expectedBottomJson, storedPosition(0))
            security(scenario)
            val saved = sample(scenario)
            pressBack(); sync.list()
            val returnedAnchor = sync.listAnchor(title(0), "after-back")
            assertEquals("List return anchor; ${sync.ownedListGeometry()}", anchor, returnedAnchor, 2f)
            sync.article(title(4), click = true); sync.reader(title(4)); stable(sync, scenario)
            boundaryDrag(up = true); stable(sync, scenario); assertEquals(title(4), sample(scenario).title)
            click(sync, "下一篇"); sync.reader(title(5)); stable(sync, scenario)
            compose.onNodeWithContentDescription("已是末篇").assertIsNotEnabled()
            boundaryDrag(up = true); stable(sync, scenario); assertEquals(title(5), sample(scenario).title)
            // Persist article 0 again without reflow or simulated preference writes.
            pressBack(); sync.list(); sync.article(title(0), click = true); sync.reader(title(0)); stable(sync, scenario)
            assertEquals(saved.y, sample(scenario).y)
            sync.await {
                JSONObject(prefs.getString("state", "{}")!!).optJSONObject("positions")
                    ?.optJSONObject(id(0))?.optInt("pixels") == saved.y
            }
            val position = JSONObject(prefs.getString("state", "{}")!!).getJSONObject("positions").getJSONObject(id(0))
            evidence.writeText(JSONObject().put("pid", android.os.Process.myPid()).put("lastPid", android.os.Process.myPid()).put("y", saved.y)
                .put("position", position.toString()).toString())
        }
    }
    // UiAutomation dispatches through Android to the actual native body. Event
    // times are real uptime, never Compose's synthetic batched gesture clock.
    private fun nativeDrag(scenario: ActivityScenario<MainActivity>, up: Boolean, distance: Float) {
        require(distance > 0f && distance <= .4f)
        val bounds = android.graphics.Rect()
        scenario.onActivity { activity ->
            val web = views(activity.window.decorView).filterIsInstance<WebView>().single()
            check(web.getGlobalVisibleRect(bounds) && bounds.width() > 0 && bounds.height() > 0)
            // getLocationOnScreen includes the window/status-bar offset on API 26.
            val screen = IntArray(2)
            web.getLocationOnScreen(screen)
            bounds.set(screen[0], screen[1], screen[0] + web.width, screen[1] + web.height)
        }
        val from = if (up) .5f + distance / 2 else .5f - distance / 2
        val to = if (up) from - distance else from + distance
        val x = bounds.exactCenterX()
        var y = bounds.top + bounds.height() * from
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(automation.injectInputEvent(event, true)) { "Android input rejected action=$action bounds=$bounds" } }
            finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN)
        var released = false
        try {
            for (step in 1..30) {
                SystemClock.sleep(20)
                y = bounds.top + bounds.height() * (from + (to - from) * step / 30f)
                inject(MotionEvent.ACTION_MOVE)
            }
            // Real stationary time and repeated samples let Chromium discard velocity.
            repeat(20) { SystemClock.sleep(20); inject(MotionEvent.ACTION_MOVE) }
            SystemClock.sleep(20)
            inject(MotionEvent.ACTION_UP)
            released = true
        } finally { if (!released) inject(MotionEvent.ACTION_CANCEL) }
    }
    private fun storedPosition(index: Int): String = JSONObject(prefs.getString("state", "{}")!!)
        .optJSONObject("positions")?.optJSONObject(id(index))?.toString() ?: "absent"

    private fun diagnostic(label: String, scenario: ActivityScenario<MainActivity>) {
        android.util.Log.i("ReaderInteractions", "$label native=${sample(scenario)} stored0=${storedPosition(0)}")
    }

    private fun screenshot(scenario: ActivityScenario<MainActivity>, point: String) {
        if (InstrumentationRegistry.getArguments().getString("readerInteractionsScreenshots") != "1") return
        check(ownership.exists())
        check(JSONObject(ownership.readText()).getInt("pid") == android.os.Process.myPid())
        check(sample(scenario).title == title(0)) { "Only owned article 0 may be captured" }
        check(point in setOf("middle-hidden-tools", "reverse-restored-tools"))
        val file = File(context.filesDir, "${prefix}evidence-${android.os.Process.myPid()}-$point.png")
        check(file.createNewFile()) { "Refusing to overwrite evidence" }
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        android.util.Log.i("ReaderInteractions", "Raw fixture screenshot: ${file.absolutePath}")
    }
    // Deliberate boundary releases retain their original input, separately from
    // controlled ordinary scrolling that establishes the boundary.
    private fun boundaryDrag(up: Boolean) = compose.onNodeWithTag("reader-content").performTouchInput {
        val from = if (up) .7f else .3f
        val to = if (up) .3f else .7f
        swipe(Offset(width / 2f, height * from), Offset(width / 2f, height * to), 600)
    }
    private data class Sample(val y: Int, val height: Int, val down: Boolean, val title: String?, val url: String?,
        val width: Int, val contentHeight: Int, val scale: Float, val up: Boolean)
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
    private fun sample(scenario: ActivityScenario<MainActivity>): Sample {
        var result: Sample? = null
        scenario.onActivity { activity ->
            val web = views(activity.window.decorView).filterIsInstance<WebView>().single()
            @Suppress("DEPRECATION")
            val scale = web.scale
            result = Sample(web.scrollY, web.height, web.canScrollVertically(1), web.title, web.url,
                web.width, web.contentHeight, scale, web.canScrollVertically(-1))
        }
        return checkNotNull(result)
    }
    private fun stable(sync: InteractionSync, scenario: ActivityScenario<MainActivity>) {
        var previous: Sample? = null
        var since = SystemClock.uptimeMillis()
        sync.await {
            val now = sample(scenario)
            if (previous != now) since = SystemClock.uptimeMillis()
            previous = now
            SystemClock.uptimeMillis() - since >= 750
        }
    }
    /** Local bounded synchronization deliberately does not capture legacy failure screenshots. */
    private inner class InteractionSync(private val scenario: ActivityScenario<MainActivity>) {
        fun configuration() {
            val args = InstrumentationRegistry.getArguments()
            val orientation = args.getString("expectedOrientation")?.let {
                when (it) {
                    "portrait" -> android.content.res.Configuration.ORIENTATION_PORTRAIT
                    "landscape" -> android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    else -> error("expectedOrientation must be portrait or landscape: $it")
                }
            }
            val font = args.getString("expectedFontScale")?.let {
                it.toFloatOrNull()?.takeIf { value -> value.isFinite() && value > 0 }
                    ?: error("expectedFontScale must be a positive scale: $it")
            }
            var actualOrientation = 0
            var actualFont = 0f
            try {
                await {
                    scenario.onActivity {
                        val actual = it.resources.configuration
                        actualOrientation = actual.orientation
                        actualFont = actual.fontScale
                    }
                    (orientation == null || actualOrientation == orientation) &&
                        (font == null || kotlin.math.abs(actualFont - font) < .01f)
                }
            } catch (failure: AssertionError) {
                throw AssertionError("Activity configuration: expected orientation=$orientation font=$font; " +
                    "actual orientation=$actualOrientation font=$actualFont", failure)
            }
        }
        fun await(predicate: () -> Boolean) { compose.waitUntil(10_000) { predicate() }; compose.waitForIdle() }
        fun settled(node: SemanticsNodeInteraction) {
            var previous: androidx.compose.ui.geometry.Rect? = null
            await {
                val displayed = try { node.assertIsDisplayed(); true } catch (_: AssertionError) { false }
                if (!displayed) false else {
                    val bounds = node.fetchSemanticsNode().boundsInRoot
                    val ready = bounds == previous
                    previous = bounds
                    ready
                }
            }
        }
        fun login() {
            await { compose.onAllNodesWithText("登录 NewsBlur · 支持免费账号").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithContentDescription("选择订阅").fetchSemanticsNodes().isNotEmpty() }
            if (compose.onAllNodesWithContentDescription("选择订阅").fetchSemanticsNodes().isNotEmpty()) {
                for (label in listOf("更多", "同步详情", "登录 NewsBlur")) {
                    val node = compose.onNodeWithContentDescription(label)
                    settled(node); node.performTouchInput { click() }
                }
            }
            settled(compose.onNodeWithText("登录 NewsBlur · 支持免费账号"))
        }
        fun scrollAndClick(text: String) {
            val node = compose.onNodeWithContentDescription(text)
            scrollControlIntoView(compose, scenario, node)
            node.performTouchInput { click() }
        }
        fun list() {
            settled(compose.onNodeWithContentDescription("选择订阅"))
            await { compose.onAllNodesWithTag("reader-content").fetchSemanticsNodes().isEmpty() }
        }
        // list() only establishes navigation readiness. Rows and the saveable
        // LazyColumn anchor restore asynchronously; never scroll to make this pass.
        fun listAnchor(title: String, phase: String): Float {
            require((0..5).any { title == this@ReaderInteractionsDeviceTest.title(it) })
            val matcher = hasText(title, substring = false)
            val node = compose.onNode(matcher)
            var previous: androidx.compose.ui.geometry.Rect? = null
            var since = SystemClock.uptimeMillis()
            val changes = java.util.ArrayDeque<String>()
            fun failure(): AssertionError = AssertionError(
                "List anchor $phase target=$title did not remain displayed/stable for 750ms within 10000ms; " +
                    "recentGeometry=$changes; ${ownedListGeometry()}")
            try {
                await {
                    val bounds = compose.onAllNodes(matcher).fetchSemanticsNodes()
                        .singleOrNull()?.boundsInRoot
                    val displayed = bounds != null && bounds.width > 0f && bounds.height > 0f &&
                        try { node.assertIsDisplayed(); true } catch (_: AssertionError) { false }
                    val current = bounds.takeIf { displayed }
                    val now = SystemClock.uptimeMillis()
                    if (current == null || current != previous) {
                        if (current != previous || changes.isEmpty()) {
                            if (changes.size == 8) changes.removeFirst()
                            changes.addLast("bounds=$bounds displayed=$displayed")
                        }
                        since = now
                    }
                    previous = current
                    current != null && now - since >= 750
                }
            } catch (cause: androidx.compose.ui.test.ComposeTimeoutException) {
                throw failure().apply { initCause(cause) }
            } catch (_: AssertionError) {
                // Do not attach Compose's potentially non-owned semantics dump.
                throw failure()
            } catch (_: Exception) {
                throw failure()
            }
            return checkNotNull(previous).top
        }
        fun ownedListGeometry(): String {
            var configuration = "unavailable"
            var nativeGeometry = "unavailable"
            // Only geometry/state and the focused class: never native view text or a tree dump.
            runCatching {
                scenario.onActivity { activity ->
                    val c = activity.resources.configuration
                    configuration = "api=${android.os.Build.VERSION.SDK_INT} orientation=${c.orientation} " +
                        "fontScale=${c.fontScale} widthDp=${c.screenWidthDp} heightDp=${c.screenHeightDp} densityDpi=${c.densityDpi}"
                    val decor = activity.window.decorView
                    val screen = IntArray(2)
                    decor.getLocationOnScreen(screen)
                    val global = android.graphics.Rect()
                    val globallyVisible = decor.getGlobalVisibleRect(global)
                    val visibleFrame = android.graphics.Rect()
                    decor.getWindowVisibleDisplayFrame(visibleFrame)
                    val insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor)
                    val ime = androidx.core.view.WindowInsetsCompat.Type.ime()
                    nativeGeometry = "decorScreenBounds=${android.graphics.Rect(screen[0], screen[1], screen[0] + decor.width, screen[1] + decor.height)} " +
                        "decorGloballyVisible=$globallyVisible decorGlobalVisibleBounds=$global windowVisibleFrame=$visibleFrame " +
                        "lifecycle=${activity.lifecycle.currentState} windowFocus=${decor.hasWindowFocus()} " +
                        "focusedViewClass=${activity.currentFocus?.javaClass?.name} " +
                        "imeVisible=${insets?.isVisible(ime)} imeBottom=${insets?.getInsets(ime)?.bottom}"
                }
            }
            val viewportBounds = runCatching {
                compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().map { it.boundsInRoot }.toString()
            }.getOrDefault("unavailable")
            // Restrict to the list's labelled editable field; export only focus booleans.
            val queryFocus = runCatching {
                compose.onAllNodes(hasSetTextAction() and hasText("搜索文章", substring = false) and
                    hasAnyAncestor(hasScrollToIndexAction())).fetchSemanticsNodes().map { node ->
                    val focused = androidx.compose.ui.semantics.SemanticsProperties.Focused
                    if (node.config.contains(focused)) node.config[focused] else null
                }.toString()
            }.getOrDefault("unavailable")
            val rows = (0..5).joinToString { index ->
                val ownedTitle = title(index)
                val bounds = compose.onAllNodesWithText(ownedTitle, substring = false)
                    .fetchSemanticsNodes().map { it.boundsInRoot }
                "fixtureIndex=$index bounds=$bounds"
            }
            return "$configuration; native=[$nativeGeometry]; listViewportBoundsInRoot=$viewportBounds; " +
                "queryFieldFocused=$queryFocus; ownedTitleBounds=[$rows]"
        }
        fun article(title: String, click: Boolean) {
            list()
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(title))
            val node = compose.onNodeWithText(title)
            settled(node)
            if (click) node.performTouchInput { click() }
        }
        fun reader(title: String) {
            try {
                await {
                    var ready = false
                    scenario.onActivity { activity ->
                        val web = views(activity.window.decorView).filterIsInstance<WebView>().singleOrNull()
                        ready = web != null && web.title == title && web.progress == 100 && web.contentHeight > 0
                    }
                    ready
                }
                settled(compose.onNodeWithTag("reader-content"))
            } catch (failure: Throwable) {
                // One bounded snapshot, no retry/input or native document/accessibility dump.
                fun ownedTitle(value: String?): String = when {
                    value == null -> "null"
                    value.startsWith(prefix) -> JSONObject.quote(value.take(160))
                    else -> "<non-owned title redacted; length=${value.length}>"
                }
                runCatching {
                    scenario.onActivity { activity ->
                        val decor = activity.window.decorView
                        val webs = views(decor).filterIsInstance<WebView>().toList()
                        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor)
                        android.util.Log.e("ReaderInteractions", "reader failure expected=${ownedTitle(title)} " +
                            "webViewCount=${webs.size} focusedViewClass=${activity.currentFocus?.javaClass?.name} " +
                            "imeVisible=${insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())}")
                        webs.take(8).forEachIndexed { index, web ->
                            android.util.Log.e("ReaderInteractions", "web[$index] title=${ownedTitle(web.title)} " +
                                "progress=${web.progress} contentHeight=${web.contentHeight} scrollY=${web.scrollY}")
                        }
                    }
                }.onFailure { android.util.Log.e("ReaderInteractions", "Native reader snapshot unavailable: ${it.javaClass.simpleName}") }
                runCatching {
                    val exists = compose.onAllNodesWithTag("reader-content").fetchSemanticsNodes().isNotEmpty()
                    android.util.Log.e("ReaderInteractions", "reader-content exists=$exists")
                    (0..5).forEach { index ->
                        val owned = this@ReaderInteractionsDeviceTest.title(index)
                        val nodes = compose.onAllNodesWithText(owned, substring = false).fetchSemanticsNodes()
                        android.util.Log.e("ReaderInteractions", "ownedTitle=${JSONObject.quote(owned)} " +
                            "nodeCount=${nodes.size} boundsInRoot=${nodes.take(8).map { it.boundsInRoot }}")
                    }
                }.onFailure { android.util.Log.e("ReaderInteractions", "Reader semantics snapshot unavailable: ${it.javaClass.simpleName}") }
                throw failure
            }
        }
    }
    private fun security(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val settings = views(activity.window.decorView).filterIsInstance<WebView>().single().settings
            assertFalse(settings.javaScriptEnabled); assertFalse(settings.allowFileAccess)
            assertFalse(settings.allowContentAccess); assertFalse(settings.domStorageEnabled)
        }
    }
}
