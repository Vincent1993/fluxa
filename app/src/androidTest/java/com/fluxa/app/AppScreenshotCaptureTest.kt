package com.fluxa.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityWindowInfo
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxa.app.data.local.*
import com.fluxa.app.ui.article.ReaderWebView
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

/**
 * Opt in by selecting this class and supplying screenshotPhase=capture|cleanup and
 * screenshotDedicatedEmulator=true. Optional screenshotFocus=read-state-audit captures only
 * the original offline fixture's unread/read pair in actual MainActivity (not user RSS).
 * No phase skips without opening preferences/Room/Activity.
 * Run cleanup in a NEW instrumentation process (not merely a recreated Activity), even after
 * failed capture. Never sign in while the ownership marker exists. Artifacts survive cleanup.
 * Application startup is runner-owned; this test never requests a Hilt entry point before backup.
 * No network controls are invoked: the real list's automatic refresh/source selection is rejected
 * by the production signed-out session guard before transport. No authentication is modified.
 */
class AppScreenshotCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val prefs get() = context.getSharedPreferences("reader_private", Context.MODE_PRIVATE)
    private val marker get() = File(context.filesDir, "app-screenshot-original.json")
    private val output get() = File(context.filesDir, "app-screenshots")
    private val owner = "com.fluxa.app.AppScreenshotCaptureTest/v1"
    private val ids = (0..9).map { "999971:screenshot-${it.toString().padStart(2, '0')}" }
    private val feeds = (999971..999973).map(Int::toString)
    private val names = listOf("城市漫游", "设计与技术", "慢读周刊")
    private val titles = listOf(
        "河边的图书馆：把一个下午留给阅读", "让界面安静下来：留白的日常练习",
        "秋日书单：在慢读中重新发现生活", "沿着老街走，听一座城市醒来",
        "一盏台灯里的设计心意", "给周末留一段没有安排的时间",
        "菜市场的清晨，藏着城市的温度", "从纸笔到屏幕，记录灵感的轻工具",
        "一杯茶的时间，读完一封远方来信", "傍晚的河岸与最后一班渡船"
    )
    private val intro = "午后的光落在河面上。推开图书馆的木门，把脚步放轻，也把时间还给自己。"
    private val caption = "临水的阅览室，把树影和天光一起借进屋里。"
    private val quote = "阅读不是为了赶到终点，而是学会在途中停留。"
    private val secondCaption = "窗边的一本书，和水面上缓缓移动的光。"
    private val closing = "离开时，夕阳刚好越过屋檐。书签留在故事中间，下一次来，仍有一个下午可以慢慢展开。"
    private val inventory = JSONArray()

    @Test fun actualAppScreenshots() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("screenshotPhase")
        assumeTrue("Opt-in only: screenshotPhase=capture|cleanup", phase != null)
        require(phase == "capture" || phase == "cleanup")
        check(args.getString("screenshotDedicatedEmulator") == "true") {
            "Explicit screenshotDedicatedEmulator=true acknowledgement required"
        }
        check(Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("Emulator", true) ||
            Build.HARDWARE in listOf("goldfish", "ranchu") || Build.PRODUCT.contains("sdk", true)) {
            "Use a dedicated emulator, never a personal device"
        }
        val auth = SecureTokenStore(context)
        check(auth.getNewsBlurSession() == null && auth.getNewsBlurAccount().isNullOrBlank() &&
            auth.getAccessToken().isNullOrBlank() && auth.getRefreshToken().isNullOrBlank()) {
            "Dedicated signed-out emulator required; accounts are never cleared by this test"
        }
        val db = Room.databaseBuilder(context, FluxaDatabase::class.java, "newsblur.db")
            .addMigrations(DatabaseMigrations.FROM_1, DatabaseMigrations.FROM_2, DatabaseMigrations.FROM_3).build()
        try {
            if (phase == "cleanup") {
                cleanup(db)
            } else {
                check(!marker.exists()) { "Ownership marker exists; run fresh-process cleanup first" }
                // Refuse artifact replacement too; previous exports belong to their previous run.
                check(!output.exists() || output.listFiles().orEmpty().isEmpty()) {
                    "Export/archive existing app-screenshots artifacts before another capture"
                }
                val epoch = Instant.parse("2026-10-04T09:00:00Z").epochSecond
                db.withTransaction {
                    // Keep the sample at the top without falsifying its date or touching existing
                    // cache. Future-dated reader fixtures must be cleaned by their own harness.
                    db.openHelper.readableDatabase.query("SELECT MAX(publishedAtEpochSeconds) FROM articles").use {
                        check(!it.moveToFirst() || it.isNull(0) || it.getLong(0) < epoch - 9 * 2700L) {
                            "Existing newer cache/fixtures: refusing to replace them or invent future publication dates"
                        }
                    }
                    check(ids.all { db.articleDao().getById(it) == null }) { "Reserved article collision" }
                    check(db.subscriptionDao().getAll().none { it.id in feeds }) { "Reserved feed collision" }
                    check(db.pendingActionDao().getAllOrdered().none { it.articleId in ids ||
                        it.articleId.substringBefore(':') in feeds }) { "Reserved pending-action collision" }
                    db.openHelper.readableDatabase.query(
                        "SELECT source FROM articles WHERE source IN ('999971','999972','999973') LIMIT 1"
                    ).use { check(!it.moveToFirst()) { "Existing article references a reserved feed" } }
                    check(feeds.all { db.syncStateDao().getBySource(it) == null }) { "Reserved sync-state collision" }
                    // Durable ownership precedes every preference edit and insert. A collision above
                    // creates no marker. A crash below leaves this record for idempotent cleanup.
                    atomicJson(marker, JSONObject().put("owner", owner)
                        .put("pid", Process.myPid()).put("processStart", Process.getStartElapsedRealtime())
                        .put("ids", JSONArray(ids)).put("feeds", JSONArray(feeds))
                        .put("present", prefs.contains("state"))
                        .put("state", prefs.getString("state", null) ?: JSONObject.NULL))
                    check(prefs.edit().putString("state", JSONObject().put("font", 18)
                        .put("spacing", 1.8).put("theme", "Light").put("session", JSONArray())
                        .put("positions", JSONObject()).toString()).commit())
                    db.subscriptionDao().upsertAll(feeds.mapIndexed { i, id ->
                        SubscriptionEntity(id, names[i], "https://example.invalid/journal/${i + 1}.xml")
                    })
                    db.articleDao().upsertAll(ids.mapIndexed { i, id ->
                        ArticleEntity(id, titles[i], names[i % 3], epoch - i * 2700L,
                            i in listOf(2, 5, 8), i in listOf(1, 4, 7),
                            if (i == 0) articleHtml() else "<p>${titles[i]}。在日常的细节里，发现值得珍惜的片刻。</p>", feeds[i % 3])
                    })
                }
                checkNotNull(instrumentation.uiAutomation.serviceInfo)
                capture(db, readStateAudit = args.getString("screenshotFocus") == "read-state-audit")
                // Deliberately retain ownership on success AND failure. ReaderPreferences owns an
                // application-lived asynchronous writer; same-process restoration would race it.
            }
        } finally { db.close() }
    }

    private suspend fun cleanup(db: FluxaDatabase) {
        check(marker.exists()) { "No ownership marker; refusing cleanup" }
        val saved = JSONObject(marker.readText())
        check(saved.getString("owner") == owner)
        check(saved.getJSONArray("ids").toString() == JSONArray(ids).toString())
        check(saved.getJSONArray("feeds").toString() == JSONArray(feeds).toString())
        check(saved.getInt("pid") != Process.myPid() ||
            saved.getLong("processStart") != Process.getStartElapsedRealtime()) {
            "Cleanup requires a fresh instrumentation process, not Activity recreation"
        }
        // Validate backup before deleting anything; exact string restored, never parsed/reserialized.
        val original = if (saved.getBoolean("present")) saved.getString("state") else null
        db.withTransaction {
            db.pendingActionDao().getAllOrdered().filter { it.articleId in ids }
                .forEach { db.pendingActionDao().deleteById(it.id) }
            ids.forEach { db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE id = ?", arrayOf(it)) }
            feeds.forEach { db.openHelper.writableDatabase.execSQL("DELETE FROM subscriptions WHERE id = ?", arrayOf(it)) }
        }
        val editor = prefs.edit()
        if (original == null) editor.remove("state") else editor.putString("state", original)
        check(editor.commit())
        check(prefs.contains("state") == saved.getBoolean("present") && prefs.getString("state", null) == original)
        check(marker.delete())
        // Do not touch screenshots, inventory, unrelated cache, sync cursors, or other preferences.
    }

    private fun atomicJson(file: File, json: JSONObject) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }

    private fun capture(db: FluxaDatabase, readStateAudit: Boolean) {
        check(output.mkdirs() || output.isDirectory)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val sync = DeviceFlowSync(compose, scenario)
            fun text(value: String) = compose.onNodeWithText(value)
            fun settle(node: SemanticsNodeInteraction) {
                // Sheets/dialogs publish their controls after the click's frame.
                // Wait for the actual accessible control before inspecting its window.
                sync.await { sync.displayed(node) }
                val target = node.fetchSemanticsNode()
                if (generateSequence(target) { it.parent }.none { isPopup().matches(it) }) {
                    sync.settled(node)
                    return
                }
                val automation = instrumentation.uiAutomation
                val info = checkNotNull(automation.serviceInfo)
                if (info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
                    info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    automation.serviceInfo = info
                }
                var previous: androidx.compose.ui.geometry.Rect? = null
                var previousWindow: Int? = null
                sync.await {
                    compose.waitForIdle()
                    if (!sync.displayed(node)) {
                        previous = null
                        previousWindow = null
                        return@await false
                    }
                    val current = node.fetchSemanticsNode()
                    val bounds = androidx.compose.ui.geometry.Rect(
                        current.positionOnScreen, current.boundsInWindow.size
                    )
                    if (!bounds.left.isFinite() || !bounds.top.isFinite() ||
                        !bounds.right.isFinite() || !bounds.bottom.isFinite() ||
                        bounds.width <= 0 || bounds.height <= 0) {
                        previous = null
                        previousWindow = null
                        return@await false
                    }
                    // Popup focus belongs to its own application window, not MainActivity.
                    val windows = automation.windows
                    val focusedWindow = try {
                        windows.firstOrNull { window ->
                            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isFocused)
                                return@firstOrNull false
                            val windowBounds = android.graphics.Rect().also(window::getBoundsInScreen)
                            val root = window.root ?: return@firstOrNull false
                            try {
                                root.packageName?.toString() == instrumentation.targetContext.packageName &&
                                    !windowBounds.isEmpty && bounds.left >= windowBounds.left &&
                                    bounds.top >= windowBounds.top && bounds.right <= windowBounds.right &&
                                    bounds.bottom <= windowBounds.bottom
                            } finally {
                                @Suppress("DEPRECATION")
                                root.recycle()
                            }
                        }?.id
                    } finally {
                        windows.forEach { @Suppress("DEPRECATION") it.recycle() }
                    }
                    val stable = focusedWindow != null && previous == bounds && previousWindow == focusedWindow
                    previous = bounds.takeIf { focusedWindow != null }
                    previousWindow = focusedWindow
                    stable
                }
                node.assertIsDisplayed()
            }
            fun showReaderControls() {
                val reveal = compose.onNodeWithContentDescription("显示阅读工具")
                if (compose.onAllNodesWithContentDescription("显示阅读工具").fetchSemanticsNodes().isNotEmpty()) {
                    settle(reveal)
                    reveal.performTouchInput { click() }
                    sync.await { sync.absent(hasContentDescription("显示阅读工具")) }
                }
            }
            fun touch(value: String) {
                showReaderControls()
                val node = compose.onNodeWithContentDescription(value)
                settle(node); node.performTouchInput { click() }
            }
            fun icon(value: String) {
                showReaderControls()
                val node = compose.onNodeWithContentDescription(value)
                settle(node); node.performTouchInput { click() }
            }
            fun shot(name: String, caption: String, anchor: SemanticsNodeInteraction, proof: JSONObject? = null) {
                settle(anchor)
                if (name == "01-login.png") {
                    // Lower actions and footer must share the captured viewport with the heading.
                    listOf("直接阅读", "在 NewsBlur 网站创建账号").forEach {
                        compose.onNodeWithContentDescription(it).assertIsDisplayed()
                    }
                    listOf("密码不会保存。登录会话仅保存在此设备的加密存储中。", 
                        "${BuildConfig.CHANNEL} · ${BuildConfig.VERSION_NAME}").forEach {
                        text(it).assertIsDisplayed()
                    }
                }
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(output, name).outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                } } finally { bitmap.recycle() }
                inventory.put(JSONObject().put("file", name).put("caption", caption)
                    .put("contentOrigin", "Original local sample fixtures, not an authenticated account; any pre-existing cache is retained")
                    .put("nativeReader", proof ?: JSONObject.NULL))
                atomicJson(File(output, "inventory.json"), JSONObject().put("owner", owner)
                    .put("signedOut", true).put("fixtureIds", JSONArray(ids))
                    .put("fixtureDate", "2026-10-04").put("screenshots", inventory))
            }
            fun filter(label: String) {
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(label))
                touch(label); compose.onNodeWithContentDescription(label).assertIsSelected()
            }
            fun query(value: String) {
                val field = text("搜索文章")
                field.performScrollTo(); sync.settled(field)
                field.performTextReplacement(value)
                // performTextReplacement uses the real editor but need not open IME. Only dismiss
                // when it is actually present, otherwise Back would collapse/navigate the screen.
                dismissKeyboard(scenario)
                sync.await { compose.onAllNodes(hasText(value) and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            }
            if (readStateAudit) {
                sync.step("actual MainActivity original offline fixture read-state audit") {
                    sync.login()
                    dismissKeyboard(scenario)
                    sync.scrollAndClick("直接阅读"); sync.list()
                    val firstTitle = text(titles[0])
                    sync.settled(firstTitle)
                    val originalBounds = firstTitle.fetchSemanticsNode().boundsInWindow
                    val original = runBlocking { checkNotNull(db.articleDao().getById(ids[0])) }
                    check(!original.isRead && original.title == titles[0])
                    shot("audit-01-unread.png",
                        "ORIGINAL offline fixture · actualMainActivity · not user RSS；同一原创离线文章，未读；仅审计已读字形层级，不验证 RSS 缩略图",
                        firstTitle)

                    // Only the real reader transition marks this owned fixture read.
                    sync.article(titles[0], click = true); sync.reader(titles[0]); sync.body(intro)
                    icon("返回文章列表"); sync.list()
                    // Do not scroll on return: preserve and verify the actual list anchor.
                    sync.settled(firstTitle)
                    sync.await { runBlocking {
                        db.articleDao().getById(ids[0])?.isRead == true &&
                            db.pendingActionDao().getAllOrdered().any {
                                it.articleId == ids[0] && it.actionType == "MarkRead"
                            }
                    } }
                    val returned = runBlocking { checkNotNull(db.articleDao().getById(ids[0])) }
                    // Includes unchanged source/feed, title, summary-producing HTML and star.
                    check(returned == original.copy(isRead = true))
                    val returnedBounds = firstTitle.fetchSemanticsNode().boundsInWindow
                    check(kotlin.math.abs(returnedBounds.left - originalBounds.left) <= 2f &&
                        kotlin.math.abs(returnedBounds.top - originalBounds.top) <= 2f &&
                        kotlin.math.abs(returnedBounds.right - originalBounds.right) <= 2f &&
                        kotlin.math.abs(returnedBounds.bottom - originalBounds.bottom) <= 2f) {
                        "Original article viewport changed after real reader return"
                    }
                    shot("audit-02-read.png",
                        "ORIGINAL offline fixture · actualMainActivity · not user RSS；同一原创离线文章，经真实阅读返回后已读；来源、标题、摘要及收藏不变；不验证 RSS 缩略图",
                        firstTitle)
                }
                return@use
            }
            sync.step("actual app screenshot flow") {
                sync.login()
                dismissKeyboard(scenario)
                shot("01-login.png", "真实登录页；未输入账户信息", text("登录 NewsBlur · 支持免费账号"))
                sync.scrollAndClick("直接阅读"); sync.list()
                shot("02-list.png", "本地原创示例文章与混合阅读状态", text(titles[0]))
                filter("未读")
                sync.await { sync.absent(hasText(titles[2])) }
                shot("03-unread.png", "未读文章筛选", compose.onNodeWithContentDescription("未读"))
                filter("收藏")
                sync.await { !sync.absent(hasText(titles[1])) && sync.absent(hasText(titles[0])) }
                shot("04-starred.png", "已收藏文章筛选", text(titles[1]))
                filter("全部"); icon("搜索"); query("图书馆")
                sync.await { !sync.absent(hasText(titles[0])) && sync.absent(hasText(titles[1])) }
                shot("05-search.png", "展开搜索：图书馆及匹配结果", text("搜索文章"))
                query("不存在的海底邮局")
                sync.settled(text("没有匹配的文章"))
                shot("06-search-empty.png", "真实搜索空结果", text("没有匹配的文章"))
                query(""); icon("搜索")
                icon("选择订阅")
                shot("07-sources.png", "真实订阅来源选择器", compose.onNodeWithContentDescription("全部订阅"))
                touch("城市漫游"); sync.list()
                sync.await { sync.absent(hasText(titles[1])) && !sync.absent(hasText(titles[0])) }
                shot("08-source-list.png", "城市漫游来源筛选", text(titles[0]))
                icon("选择订阅"); touch("全部订阅"); sync.list()
                icon("更多")
                shot("09-more.png", "更多操作菜单", compose.onNodeWithContentDescription("添加订阅"))
                touch("添加订阅")
                shot("10-add-rss.png", "添加 RSS 对话框，尚未输入", text("添加 RSS 订阅"))
                val url = text("HTTP / HTTPS RSS 地址")
                url.performTouchInput { click() }
                url.performTextInput("https://example.invalid/journal/rss.xml")
                sync.await {
                    var shown = false
                    scenario.onActivity {
                        shown = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                    }
                    shown
                }
                dismissKeyboard(scenario)
                shot("11-add-rss-filled.png", "地址示例；键盘已收起，未提交订阅", text("添加 RSS 订阅"))
                touch("取消")
                icon("更多"); touch("账户与同步设置")
                shot("12-account-sync.png", "账户与同步；不改变后台刷新开关", text("账户与同步"))
                touch("完成")
                sync.article(titles[0], click = true); sync.reader(titles[0]); sync.body(intro)
                val reader = compose.onNodeWithTag("reader-content")
                shot("13-reader-hero.png", "18px / 1.8 浅色：标题、引言与河岸插画", reader,
                    reveal(sync, scenario, intro, move = false))
                touch("收藏"); sync.settled(compose.onNodeWithContentDescription("取消收藏"))
                shot("14-reader-image.png", "原创河岸插画与图注", reader, reveal(sync, scenario, caption, .85f))
                shot("15-reader-quote.png", "慢读章节与引文", reader, reveal(sync, scenario, quote, .35f))
                shot("16-reader-code-table.png", "阅读笔记代码与观察表格", reader,
                    reveal(sync, scenario, "val afternoon", .18f))
                shot("17-reader-second-image.png", "第二幅窗边插画与正文", reader,
                    reveal(sync, scenario, secondCaption, .7f))
                shot("18-reader-closing.png", "长文结尾与阅读留白", reader, reveal(sync, scenario, closing, .6f))
                icon("阅读设置")
                text("字号 18").assertExists()
                text("行距 %.1f".format(1.8f)).assertExists()
                shot("19-reading-settings.png", "真实阅读设置：默认字号与行距", text("字号 18"))
                touch("完成")
                // Return to the same intro with genuine reverse swipes before comparing palettes.
                reveal(sync, scenario, intro, .45f)
                fun theme(label: String, filename: String) {
                    var beforeY = 0
                    var beforeHeight = 0
                    scenario.onActivity {
                        val web = views(it.window.decorView).filterIsInstance<ReaderWebView>().single()
                        beforeY = web.scrollY; beforeHeight = web.contentHeight
                    }
                    icon("阅读设置")
                    val chip = compose.onNodeWithContentDescription(label); chip.performScrollTo(); sync.settled(chip)
                    chip.performTouchInput { click() }; chip.assertIsSelected(); touch("完成")
                    sync.reader(titles[0])
                    // A palette-only replacement must retain the actual viewport,
                    // as well as publish the real body and visible prose below.
                    sync.await {
                        var retained = false
                        scenario.onActivity {
                            val web = views(it.window.decorView).filterIsInstance<ReaderWebView>().single()
                            retained = !web.documentCovered && web.progress == 100 &&
                                web.contentHeight == beforeHeight && kotlin.math.abs(web.scrollY - beforeY) <= 2
                        }
                        retained
                    }
                    sync.body(intro)
                    shot(filename, "真实阅读主题：$label", reader, reveal(sync, scenario, intro, move = false))
                }
                theme("深色", "20-reader-dark.png")
                theme("纸色", "21-reader-sepia.png")
                icon("返回文章列表"); sync.list()
                sync.await { runBlocking {
                    val actions = db.pendingActionDao().getAllOrdered().filter { it.articleId == ids[0] }
                    db.articleDao().getById(ids[0])?.let { it.isRead && it.isStarred } == true &&
                        actions.any { it.actionType == "MarkRead" } &&
                        actions.any { it.actionType == "ToggleStar" && it.payload == "true" }
                } }
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("全部"))
                icon("更多"); icon("同步详情")
                shot("22-pending-sync.png", "离线缓存真实已读、收藏操作待同步；未连接账户", text("同步状态"))
                touch("关闭")
                // Append after the original 22 captures, leaving their viewports unchanged.
                sync.article(titles[0], click = true); sync.reader(titles[0])
                var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                scenario.onActivity { originalOrientation = it.requestedOrientation }
                fun awaitOrientation(expected: Int) = sync.await {
                    var matches = false
                    scenario.onActivity { matches = it.resources.configuration.orientation == expected }
                    matches
                }
                try {
                    scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                    awaitOrientation(Configuration.ORIENTATION_LANDSCAPE)
                    sync.reader(titles[0])
                    shot("23-reader-landscape.png", "真实横屏阅读：插画与图注", reader,
                        reveal(sync, scenario, secondCaption, .7f))
                } finally {
                    scenario.onActivity { it.requestedOrientation = originalOrientation }
                    awaitOrientation(Configuration.ORIENTATION_PORTRAIT)
                }
            }
        }
    }

    private fun dismissKeyboard(scenario: ActivityScenario<MainActivity>) {
        var visible = false
        scenario.onActivity {
            visible = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
        if (visible) pressBack()
        compose.waitUntil(10_000) {
            scenario.onActivity {
                visible = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            !visible
        }
        compose.waitForIdle()
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }

    /** Provider-native text geometry, not a host label or JavaScript query. Only real gestures
     * change the viewport. The inventory records progress/dimensions and actual visible prose.
     */
    private fun reveal(sync: DeviceFlowSync, scenario: ActivityScenario<MainActivity>, needle: String,
        fraction: Float = .3f, move: Boolean = true): JSONObject {
        fun sample(): Pair<JSONObject, Float> {
            val probe = NativeDocumentSnapshotProbe()
            var y = 0; var width = 0; var height = 0; var content = 0
            scenario.onActivity {
                val web = views(it.window.decorView).filterIsInstance<WebView>().single()
                check(web.title == titles[0] && web.progress == 100 && web.isShown)
                check(!web.settings.javaScriptEnabled)
                y = web.scrollY; width = web.width; height = web.height; content = web.contentHeight
                check(width > 0 && height > 0 && content > 0)
                web.onProvideVirtualStructure(probe.sink)
            }
            check(probe.awaitCompletion()) { "Native document snapshot timeout" }
            val entries = probe.flatten()
            val target = checkNotNull(entries.filter { it.text?.contains(needle) == true &&
                it.bounds.width() > 0 && it.bounds.height() > 0 }.minByOrNull { it.bounds.height() }) {
                "Actual native paragraph missing: $needle"
            }
            // flatten() already applies the document's native scroll offsets.
            // These bounds are in the WebView viewport; subtracting scrollY again
            // makes an actually visible caption look offscreen and causes oscillation.
            val visible = target.bounds.width() > 1 && target.bounds.height() > 1 &&
                target.bounds.left >= 0 && target.bounds.right <= width &&
                target.bounds.top >= 0 && target.bounds.bottom <= height
            return JSONObject().put("progress", 100).put("width", width).put("height", height)
                .put("contentHeight", content).put("scrollY", y).put("javascriptEnabled", false)
                .put("matchedText", target.text).put("bounds", target.bounds.toShortString())
                .put("visible", visible).put("visibleBody", JSONArray(entries.filter {
                    !it.text.isNullOrBlank() && it.bounds.width() > 1 && it.bounds.height() > 1 &&
                        it.bounds.left >= 0 && it.bounds.right <= width &&
                        it.bounds.top >= 0 && it.bounds.bottom <= height
                }.map { it.text }.distinct())) to (target.bounds.top - height * fraction)
        }
        for (attempt in 0..35) {
            var previous: String? = null
            var stableSince = SystemClock.uptimeMillis()
            sync.await {
                var geometry = ""
                scenario.onActivity {
                    val web = views(it.window.decorView).filterIsInstance<WebView>().single()
                    geometry = "${web.progress}:${web.scrollY}:${web.width}:${web.height}:${web.contentHeight}"
                }
                if (geometry != previous) stableSince = SystemClock.uptimeMillis()
                previous = geometry
                SystemClock.uptimeMillis() - stableSince >= 500
            }
            val (proof, delta) = sample()
            if (proof.getBoolean("visible")) {
                // fraction guides offscreen scrolling; it is not a pixel-perfect
                // layout assertion. Once the paragraph fits, require real visible
                // accessibility text rather than oscillating to align its baseline.
                sync.await { sync.accessibilityText(visibleOnly = true).any { it.contains(needle) } }
                return proof.put("accessibleVisibleTextMatched", true)
            }
            check(move && attempt < 35) { "Native text not visibly reached: $proof" }
            nativeScroll(scenario, delta)
        }
        error("Unreachable")
    }

    /** Chromium consumes Android wall-clock input; a stationary tail prevents a
     * synthetic test-clock swipe from flinging past the paragraph being framed. */
    private fun nativeScroll(scenario: ActivityScenario<MainActivity>, requested: Float) {
        val viewport = android.graphics.Rect()
        scenario.onActivity { activity ->
            val web = views(activity.window.decorView).filterIsInstance<WebView>().single()
            check(web.title == titles[0] && web.getGlobalVisibleRect(viewport))
            val origin = IntArray(2).also(web::getLocationOnScreen)
            viewport.set(origin[0], origin[1], origin[0] + web.width, origin[1] + web.height)
        }
        val distance = requested.coerceIn(-viewport.height() * .4f, viewport.height() * .4f)
        val start = viewport.top + viewport.height() * (if (distance >= 0) .75f else .25f)
        val x = viewport.exactCenterX()
        var y = start
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int) {
            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        inject(android.view.MotionEvent.ACTION_DOWN)
        var released = false
        try {
            for (step in 1..30) {
                SystemClock.sleep(20)
                y = start - distance * step / 30f
                inject(android.view.MotionEvent.ACTION_MOVE)
            }
            repeat(20) { SystemClock.sleep(20); inject(android.view.MotionEvent.ACTION_MOVE) }
            inject(android.view.MotionEvent.ACTION_UP)
            released = true
        } finally { if (!released) inject(android.view.MotionEvent.ACTION_CANCEL) }
    }

    /** Original geometric artwork; two independently painted PNGs, no external assets or text. */
    private fun artwork(detail: Boolean): String {
        val bitmap = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun rect(l: Float, t: Float, r: Float, b: Float, color: String) {
            paint.color = Color.parseColor(color); canvas.drawRect(l, t, r, b, paint)
        }
        fun circle(x: Float, y: Float, radius: Float, color: String) {
            paint.color = Color.parseColor(color); canvas.drawCircle(x, y, radius, paint)
        }
        canvas.drawColor(Color.parseColor("#DFECE9"))
        circle(516f, 62f, 30f, "#F4DCAA")
        rect(0f, 202f, 640f, 320f, "#84AFB4")
        rect(0f, 187f, 640f, 215f, "#AFBF9C")
        rect(183f, 100f, 473f, 201f, "#D9C7A6")
        rect(170f, 88f, 486f, 104f, "#65796F")
        for (i in 0..3) {
            val x = 201f + i * 66
            rect(x, 119f, x + 45, 182f, "#526F70")
            rect(x + 5, 127f, x + 20, 171f, "#EACF92")
            rect(x + 24, 127f, x + 39, 171f, "#F2E3BE")
            rect(x + 4, 173f, x + 40, 177f, "#B59A70")
        }
        for (x in listOf(72f, 125f, 540f, 593f)) {
            rect(x - 4, 137f, x + 4, 213f, "#7B7760")
            circle(x, 124f, 33f, "#789B7C")
            circle(x - 12, 109f, 23f, "#92B08A")
        }
        for (i in 0..6) rect(35f + i * 86, 239f + (i % 3) * 21,
            86f + i * 86, 242f + (i % 3) * 21, "#BDD3CB")
        if (detail) {
            rect(0f, 0f, 22f, 320f, "#B69A75"); rect(618f, 0f, 640f, 320f, "#B69A75")
            rect(0f, 278f, 640f, 320f, "#D8BB8C")
            rect(242f, 235f, 319f, 291f, "#F7EBD3")
            rect(322f, 235f, 399f, 291f, "#EBDDC0")
            rect(318f, 236f, 323f, 294f, "#B68A69")
            for (i in 0..3) {
                rect(254f, 248f + i * 9, 304f, 250f + i * 9, "#C6BAA3")
                rect(337f, 248f + i * 9, 386f, 250f + i * 9, "#C6BAA3")
            }
        }
        return try { ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            "data:image/png;base64," + Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP)
        } } finally { bitmap.recycle() }
    }

    private fun articleHtml() = """
        <p>$intro</p>
        <figure><img src="${artwork(false)}" width="640" height="320" alt="河岸图书馆与树影"><figcaption>$caption</figcaption></figure>
        <p>这座小小的图书馆没有醒目的招牌。沿着河岸走过两排梧桐，便能看见落地窗后整齐的书架。有人伏案写字，有人把刚读过的一页轻轻折回，窗外的水声替每一次停顿留出了余地。</p>
        <p>我挑了一本关于城市的旧书，坐在靠窗的位置。书页间夹着一张淡黄色的借阅卡，日期已经模糊，却让人想到许多个相似的下午：陌生的读者在同一段文字前停下来，再带着不同的心事回到街上。</p>
        <h2>把注意力还给眼前</h2>
        <p>手机收进包里以后，才听见椅脚挪动的轻响。我们不必给每一刻都安排用途，有时只要坐下来，就已经重新找到了生活的节奏。</p>
        <blockquote>$quote</blockquote>
        <p>我用几行简单的笔记记下今天的安排。它不像任务清单，更像一个温柔的提醒：为观察和发呆留一点空间。</p>
        <pre><code>val afternoon = listOf("散步", "阅读", "喝茶")
        afternoon.forEach { moment ->
            println("慢慢享受：" + moment)
        }</code></pre>
        <table><tr><th>时刻</th><th>窗边的发现</th></tr><tr><td>午后</td><td>树影落在书页边缘</td></tr><tr><td>傍晚</td><td>渡船划开金色水面</td></tr></table>
        <p>合上书时，河岸上的行人渐渐多了起来。一位老人牵着小狗经过窗前，孩子蹲下来观察石缝里的草。阅读把我们带向远处，也让熟悉的街道重新变得具体而生动。</p>
        <figure><img src="${artwork(true)}" width="360" height="180" alt="窗台上的打开的书"><figcaption>$secondCaption</figcaption></figure>
        <p>窗台很宽，恰好放下一本摊开的书。风从半开的窗扇进来，纸页轻轻抬起，又安稳地落下。那些没来得及记住的句子，也许会在明天的某一次散步中重新出现。</p>
        <p>$closing</p>
    """.trimIndent()
}
