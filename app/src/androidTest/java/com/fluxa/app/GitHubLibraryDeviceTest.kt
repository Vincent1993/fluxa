package com.fluxa.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.withTransaction
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxa.app.data.github.*
import com.fluxa.app.data.local.SecureTokenStore
import com.fluxa.app.di.GitHubAccess
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Explicit, local demo data only. No fixture or network work occurs in the default test suite. */
class GitHubLibraryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val args get() = InstrumentationRegistry.getArguments()
    private val marker get() = File(context.filesDir, "github-fixture-owner.json")
    private val output get() = File(context.filesDir, "github-fixture-screenshots")
    private val user = "fluxa-fixture-github-v1"
    private val emptyUser = "fluxa-fixture-github-empty-v1"
    private val owner = "GitHubLibraryDeviceTest/v1"
    private val ids = (1L..9L).map { -990000L - it }
    private val titles = listOf("演示/青叶笔记", "演示/林间工具", "演示/轻舟界面", "演示/绿芽模型", "演示/山间数据", "演示/风铃自动化", "演示/溪流安全", "演示/星光移动", "演示/花园部署")
    private val purposes = listOf("学习参考", "开发工具", "前端", "AI", "数据处理", "自动化", "安全", "移动开发", "自托管")

    @Test fun actualActivityHiltRoomLibrary() = runBlocking {
        val phase = args.getString("githubPhase")
        assumeTrue("Opt in: githubPhase=seed|verify|cleanup", phase != null)
        require(phase in listOf("seed", "verify", "cleanup"))
        check(args.getString("githubDedicatedEmulator") == "true")
        check(Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("Emulator", true) || Build.HARDWARE in listOf("goldfish", "ranchu") || Build.PRODUCT.contains("sdk", true))
        check(SecureTokenStore(context).getNewsBlurSession() == null) { "Requires signed-out dedicated emulator; never clears credentials" }
        val entry = EntryPointAccessors.fromApplication(context, GitHubAccess::class.java)
        val db = entry.database()
        val dao = db.dao()
        when (phase) {
            "seed" -> {
                check(!marker.exists()) { "Run fresh-process cleanup before reseeding" }
                check(!output.exists() || output.listFiles().orEmpty().isEmpty()) { "Archive previous screenshots first" }
                db.withTransaction {
                    assertEmptyReserved(db)
                    check(dao.repos(user).isEmpty() && dao.profile(user) == null)
                    for (table in listOf("daily", "history")) {
                        db.openHelper.readableDatabase.query("SELECT 1 FROM $table WHERE profile = ? LIMIT 1", arrayOf(user)).use { check(!it.moveToFirst()) { "Reserved fixture collision" } }
                    }
                    db.openHelper.readableDatabase.query("SELECT 1 FROM repositories WHERE id <= -990001 AND id >= -990009 LIMIT 1").use { check(!it.moveToFirst()) }
                    val settings = dao.settings() ?: GitHubSettings()
                    check(settings.active != user && settings.active != emptyUser)
                    atomic(JSONObject().put("owner", owner).put("pid", Process.myPid()).put("start", Process.getStartElapsedRealtime())
                        .put("active", settings.active).put("ids", JSONArray(ids)).put("date", LocalDate.now().toString()))
                    ids.forEachIndexed { index, id -> dao.putRepo(GitHubRepo(user, id, titles[index],
                        "原创演示数据：探索安静的本地收藏与绿色界面，不代表真实 GitHub 项目。", "fluxa-demo\nlocal-first", "Kotlin",
                        "https://example.invalid/fluxa-demo/$index", manualTags = purposes[index])) }
                    dao.putProfile(GitHubProfile(user, status = "原创演示数据 · 未访问网络"))
                    dao.putSettings(settings.copy(active = user))
                }
                entry.repository().daily(user)
                captureAndExercise(db, edit = true)
                val saved = JSONObject(marker.readText())
                saved.put("batch", dao.daily(user, LocalDate.now().toString())!!.ids)
                saved.put("batchNumber", dao.daily(user, LocalDate.now().toString())!!.batch)
                atomic(saved)
            }
            "verify" -> {
                val saved = ownedFreshProcess()
                check(saved.getString("date") == LocalDate.now().toString()) { "Run same-day verify for exact daily stability" }
                check(dao.settings()!!.active == user)
                check(dao.repo(user, ids[0])!!.manualTags == "演示自定义")
                check(dao.daily(user, LocalDate.now().toString())!!.ids == saved.getString("batch"))
                check(dao.daily(user, LocalDate.now().toString())!!.batch == saved.getInt("batchNumber"))
                captureAndExercise(db, edit = false)
            }
            "cleanup" -> {
                val saved = ownedFreshProcess()
                db.withTransaction {
                    check(dao.repos(user).all { it.id in ids }) { "Unexpected repository in reserved profile: refusing deletion" }
                    dao.deleteRepos(user); dao.deleteProfile(user); dao.deleteDaily(user); dao.deleteHistory(user)
                    dao.putSettings((dao.settings() ?: GitHubSettings()).copy(active = saved.getString("active")))
                }
                check(marker.delete())
            }
        }
        // Hilt owns this database. Do not close it under application-lived collectors.
    }

    @Test fun explicitlyOptedInPublicImport(): Unit = runBlocking {
        assumeTrue("Explicit real-network opt in only", args.getString("githubPublicImport") == "Vincent1993")
        check(args.getString("githubDedicatedEmulator") == "true")
        check(Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("Emulator", true) || Build.HARDWARE in listOf("goldfish", "ranchu") || Build.PRODUCT.contains("sdk", true))
        check(!marker.exists()) { "Clean demo fixture before real public import" }
        val entry = EntryPointAccessors.fromApplication(context, GitHubAccess::class.java)
        entry.repository().importStars("Vincent1993")
        val dao = entry.database().dao()
        val state = checkNotNull(dao.profile("vincent1993"))
        File(context.filesDir, "github-public-import-result.json").writeText(JSONObject()
            .put("username", "vincent1993").put("localCount", dao.repos("vincent1993").size)
            .put("pages", state.pages).put("cursor", state.cursor ?: JSONObject.NULL)
            .put("status", state.status).put("cooldownUntil", dao.settings()?.cooldownUntil ?: 0)
            .put("recordedAt", java.time.Instant.now().toString()).toString(2))
        check(state.cursor == null && state.status.startsWith("导入完成")) { "Public import incomplete: ${state.status}" }
    }

    private fun ownedFreshProcess(): JSONObject {
        check(marker.exists())
        val saved = JSONObject(marker.readText())
        check(saved.getString("owner") == owner && saved.getJSONArray("ids").toString() == JSONArray(ids).toString())
        check(saved.getInt("pid") != Process.myPid() || saved.getLong("start") != Process.getStartElapsedRealtime()) { "Requires independent instrumentation process" }
        return saved
    }
    private fun atomic(json: JSONObject) {
        val file = AtomicFile(marker); val stream = file.startWrite()
        try { stream.write(json.toString(2).toByteArray()); file.finishWrite(stream) }
        catch (e: Throwable) { file.failWrite(stream); throw e }
    }
    private fun captureAndExercise(db: GitHubDatabase, edit: Boolean) {
        val variant = args.getString("githubLayout") ?: error("Supply githubLayout=portrait100|landscape150")
        require(variant in listOf("portrait100", "landscape150"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val sync = DeviceFlowSync(compose, scenario)
            val expectedOrientation = if (variant == "portrait100") Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
            val expectedFont = if (variant == "portrait100") 1f else 1.5f
            sync.step("GitHub real Activity configuration: $variant") {
                sync.await {
                    var matches = false
                    scenario.onActivity { activity ->
                        val config = activity.resources.configuration
                        matches = config.orientation == expectedOrientation && config.fontScale == expectedFont
                    }
                    matches
                }
                scenario.onActivity { activity ->
                    val config = activity.resources.configuration
                    check(config.orientation == expectedOrientation)
                    check(config.fontScale == expectedFont) { "Configure real device font scale externally" }
                }
            }
            fun awaitIme(visible: Boolean) = sync.step("GitHub IME visibility=$visible: $variant, edit=$edit") {
                try {
                    sync.await {
                        var matches = false
                        scenario.onActivity {
                            matches = ViewCompat.getRootWindowInsets(it.window.decorView)
                                ?.isVisible(WindowInsetsCompat.Type.ime()) == visible
                        }
                        matches
                    }
                } catch (failure: Throwable) {
                    // Sample before ActivityScenario closes; diagnostics must not replace the failure.
                    runCatching {
                        scenario.onActivity { activity ->
                            val decor = activity.window.decorView
                            val frame = android.graphics.Rect().also { decor.getWindowVisibleDisplayFrame(it) }
                            val insets = ViewCompat.getRootWindowInsets(decor)
                            val details = JSONObject()
                                .put("owner", owner)
                                .put("expectedVisibleIME", visible)
                                .put("api", Build.VERSION.SDK_INT)
                                .put("decorWidth", decor.width).put("decorHeight", decor.height)
                                .put("visibleDisplayFrame", JSONObject()
                                    .put("left", frame.left).put("top", frame.top)
                                    .put("right", frame.right).put("bottom", frame.bottom))
                                .put("ime.bottom", insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: JSONObject.NULL)
                                .put("nav.bottom", insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: JSONObject.NULL)
                                .put("isVisibleIME", insets?.isVisible(WindowInsetsCompat.Type.ime()) ?: JSONObject.NULL)
                                .put("configuration", activity.resources.configuration.toString())
                            android.util.Log.e("FluxaGitHubIme", details.toString())
                        }
                    }.onFailure { failure.addSuppressed(it) }
                    throw failure
                }
            }
            sync.login()
            sync.scrollAndClick("GitHub 收藏 · 无需登录")
            sync.step("GitHub initial profile ready: $variant, edit=$edit") {
                compose.waitUntil(10_000) { compose.onAllNodesWithText("当前用户：$user · 9 个收藏").fetchSemanticsNodes().isNotEmpty() }
            }
            screenshot("$variant-${if (edit) "seed" else "verify"}-library")
            fun scroll(text: String) {
                compose.onNodeWithTag("github-scroll").performScrollToNode(hasText(text))
                compose.onAllNodesWithText(text).onFirst().performScrollTo()
            }
            fun click(label: String) {
                compose.onNodeWithTag("github-scroll").performScrollToNode(hasContentDescription(label))
                compose.onNodeWithContentDescription(label).performScrollTo()
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                    .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,
                        androidx.compose.ui.semantics.Role.Button))
                    .assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.Text))
                    .performClick()
            }
            scroll("搜索名称、描述、主题、语言、用途")
            compose.onNodeWithText("搜索名称、描述、主题、语言、用途").performClick().performTextInput("青叶")
            awaitIme(true)
            scroll("收藏库 · 1 个结果"); compose.onNodeWithText("收藏库 · 1 个结果").assertIsDisplayed()
            scroll("搜索名称、描述、主题、语言、用途")
            compose.onNodeWithText("搜索名称、描述、主题、语言、用途").performImeAction()
            awaitIme(false)
            scroll("收藏库 · 1 个结果")
            screenshot("$variant-search-results-header-ime-dismissed")
            compose.onNodeWithTag("github-scroll").performScrollToNode(hasTestTag("github-result-${ids[0]}"))
            compose.onNodeWithTag("github-result-${ids[0]}").performScrollTo().assertIsDisplayed()
            screenshot("$variant-search-result-ime-dismissed")
            scroll("搜索名称、描述、主题、语言、用途")
            compose.onNodeWithText("搜索名称、描述、主题、语言、用途").performTextClearance()
            compose.onNodeWithText("搜索名称、描述、主题、语言、用途").performImeAction()
            awaitIme(false)
            if (edit) {
                val before = runBlocking { db.dao().daily(user, LocalDate.now().toString())!!.ids }
                click("换一批")
                compose.waitUntil(10_000) { runBlocking { db.dao().daily(user, LocalDate.now().toString())!!.ids != before } }
            }
            val daily = runBlocking { checkNotNull(db.dao().daily(user, LocalDate.now().toString())) }
            scroll("每日 3")
            screenshot("$variant-daily-header")
            scroll("${daily.date} · 第 ${daily.batch} 批 · 本地规则推荐")
            screenshot("$variant-daily-date-batch")
            daily.ids.split(',').forEachIndexed { index, id ->
                val tag = "github-daily-$id"
                compose.onNodeWithTag("github-scroll").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                screenshot("$variant-daily-recommendation-${index + 1}")
            }
            scroll(titles[0]); compose.onAllNodesWithText(titles[0]).onFirst().performClick()
            compose.onNodeWithText("收藏详情").assertExists()
            scroll(titles[0])
            screenshot("$variant-detail-overview")
            compose.onNodeWithTag("github-scroll").performScrollToNode(hasContentDescription("Open on GitHub"))
            compose.onNodeWithContentDescription("Open on GitHub").performScrollTo().assertIsNotEnabled()
            screenshot("$variant-detail-fictional-link-unavailable")
            if (edit) {
                click("清空用途")
                scroll("自定义用途")
                compose.onNodeWithText("自定义用途").performClick().performTextInput("演示自定义")
                awaitIme(true)
                click("添加用途"); click("保存用途")
                compose.waitUntil(10_000) { runBlocking { db.dao().repo(user, ids[0])?.manualTags == "演示自定义" } }
            }
            awaitIme(false)
            scroll("用途 · 手动分类")
            screenshot("$variant-tag-editor-saved-heading")
            scroll("演示自定义")
            compose.onNodeWithTag("purpose-selector-演示自定义").assertIsSelected()
            screenshot("$variant-tag-editor-saved-selection")
            scroll("相似收藏 · 本地匹配")
            compose.onNodeWithTag("github-scroll").performScrollToNode(hasText("共同主题：fluxa-demo、local-first", substring = true))
            compose.onAllNodesWithText("共同主题：fluxa-demo、local-first", substring = true).onFirst().performScrollTo().assertExists()
            screenshot("$variant-detail-similar")
            compose.onNodeWithContentDescription("返回").performClick()
            scroll("搜索名称、描述、主题、语言、用途")
            val purposeTag = "github-filter-${if (edit) "演示自定义" else "学习参考"}"
            val purposeChip = compose.onNodeWithTag(purposeTag)
            sync.step("GitHub purpose viewport preparation and selection: $variant, edit=$edit") {
                // Anchor the distinct following results item to move the vertical viewport;
                // the visible search field shares its lazy item with the horizontal filter row.
                scroll("收藏库 · 9 个结果")
                compose.onNodeWithText("收藏库 · 9 个结果").assertIsDisplayed()
                scrollControlIntoView(compose, scenario, purposeChip)
                sync.settled(purposeChip)
                purposeChip.assertIsDisplayed().assertIsNotSelected().performClick()
                sync.await {
                    compose.onAllNodes(hasTestTag(purposeTag) and isSelected()).fetchSemanticsNodes().size == 1
                }
                purposeChip.assertIsSelected()
            }
            scroll(if (edit) "收藏库 · 1 个结果" else "收藏库 · 0 个结果")
            screenshot("$variant-purpose")
            val preservedRepos = runBlocking { db.dao().repos(user) }
            val preservedDaily = runBlocking { checkNotNull(db.dao().daily(user, LocalDate.now().toString())) }
            val preservedSettings = runBlocking { checkNotNull(db.dao().settings()) }
            check(preservedRepos.size == 9 && preservedRepos.single { it.id == ids[0] }.manualTags == "演示自定义")
            runBlocking { db.withTransaction { assertEmptyReserved(db) } }
            fun switchProfile(target: String, count: Int) {
                scroll("GitHub 用户名")
                compose.onNodeWithText("GitHub 用户名").performClick().performTextReplacement(target)
                click("切换本地库")
                awaitIme(false)
                sync.await { compose.onAllNodesWithText("当前用户：$target · $count 个收藏").fetchSemanticsNodes().isNotEmpty() }
                scroll("当前用户：$target · $count 个收藏")
                compose.onNodeWithText("当前用户：$target · $count 个收藏").assertIsDisplayed()
            }
            switchProfile(emptyUser, 0)
            screenshot("$variant-fictional-demo-empty-profile")
            scroll("本地库为空。输入公开用户名并导入；之后可离线浏览。")
            screenshot("$variant-fictional-demo-empty-library")
            runBlocking {
                db.withTransaction {
                    assertEmptyReserved(db)
                    check(db.dao().settings() == preservedSettings.copy(active = emptyUser))
                    check(db.dao().repos(user) == preservedRepos)
                    check(db.dao().daily(user, preservedDaily.date) == preservedDaily)
                }
            }
            switchProfile(user, 9)
            screenshot("$variant-fictional-demo-restored-profile")
            runBlocking {
                db.withTransaction {
                    assertEmptyReserved(db)
                    check(db.dao().settings() == preservedSettings)
                    check(db.dao().repos(user) == preservedRepos)
                    check(db.dao().daily(user, preservedDaily.date) == preservedDaily)
                }
            }
            scroll("${preservedDaily.date} · 第 ${preservedDaily.batch} 批 · 本地规则推荐")
            screenshot("$variant-fictional-demo-restored-exact-daily")
            scroll("收藏库 · 9 个结果")
            screenshot("$variant-fictional-demo-restored-library")
            compose.onNodeWithContentDescription("返回").performClick()
            sync.login()
            sync.scrollAndClick("直接阅读")
            sync.await {
                compose.onAllNodesWithContentDescription("更多").fetchSemanticsNodes().size == 1 &&
                    compose.onAllNodesWithContentDescription("直接阅读").fetchSemanticsNodes().isEmpty()
            }
            val actualMore = compose.onNodeWithContentDescription("更多")
            sync.settled(actualMore)
            actualMore.performClick()
            val actualGitHub = compose.onNodeWithContentDescription("GitHub 收藏")
            sync.await {
                compose.onAllNodesWithContentDescription("GitHub 收藏").fetchSemanticsNodes().size == 1 &&
                    runCatching { actualGitHub.assertIsDisplayed() }.isSuccess
            }
            actualGitHub.performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("当前用户：$user · 9 个收藏").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("返回").performClick()
            sync.await { compose.onAllNodesWithContentDescription("更多").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithContentDescription("更多").assertExists()
        }
    }
    /** Never seed, import into, or delete this profile, even during cleanup. */
    private suspend fun assertEmptyReserved(db: GitHubDatabase) {
        check(db.dao().repos(emptyUser).isEmpty() && db.dao().profile(emptyUser) == null) { "Reserved empty profile collision" }
        for (table in listOf("daily", "history")) {
            db.openHelper.readableDatabase.query("SELECT 1 FROM $table WHERE profile = ? LIMIT 1", arrayOf(emptyUser)).use {
                check(!it.moveToFirst()) { "Reserved empty profile has $table state" }
            }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        check(output.mkdirs() || output.isDirectory)
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(output, "${args.getString("githubPhase")}-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(output, "inventory.txt").appendText("${args.getString("githubPhase") }-$name.png — 原创演示数据；实际 MainActivity/Hilt/Room；非真实 GitHub 项目；${LocalDate.now()}\n")
    }
}
