package com.fluxa.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.feedlist.ArticleCard
import com.fluxa.app.ui.theme.FluxaTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated UI fixture, not MainActivity; no database, preferences or network access. */
class ArticleListAppearanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val article = mutableStateOf(Article(
        id = "appearance", title = "中文长标题：阅读与收藏不应改变文章列表的排版、缩略图位置和滚动锚点。".repeat(4),
        feedName = "一个非常长的中文订阅来源名称，用于验证日期不会重叠".repeat(3),
        publishedAt = Instant.parse("2026-10-08T12:00:00Z"), isRead = false, isStarred = false,
        contentHtml = "", source = "direct-rss:fixture",
        summary = "这是中文摘要，用于检查大字体下的两行布局与状态变化前后完全一致的文字几何。".repeat(5)
    ))
    private var surface = Color.Unspecified

    private fun host(html: String) {
        article.value = article.value.copy(contentHtml = html)
        compose.activityRule.scenario.awaitStableHost()
        compose.setContent {
            FluxaTheme {
                surface = MaterialTheme.colorScheme.surface
                Surface {
                    // Narrow even on a landscape host. External font/theme settings remain real.
                    Box(Modifier.widthIn(max = 360.dp).padding(horizontal = 12.dp)) {
                        ArticleCard(article.value, {},
                            { article.value = article.value.copy(isRead = true) },
                            { article.value = article.value.copy(isStarred = !article.value.isStarred) })
                    }
                }
            }
        }
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun layout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }
    private fun action(label: String) {
        val actions = compose.onNodeWithTag("article-swipe").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertTrue(actions.single { it.label == label }.action())
        }
        compose.waitForIdle()
    }
    private fun assertContrast(color: Color) {
        assertEquals(1f, color.alpha, 0f)
        val a = color.luminance()
        val b = surface.luminance()
        assertTrue("Text $color on actual surface $surface", (maxOf(a, b) + .05f) / (minOf(a, b) + .05f) >= 4.5f)
    }
    private fun assertQuietRow(read: Boolean, starred: Boolean) {
        compose.onNodeWithTag("article-swipe").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            "${if (read) "已读" else "未读"} · ${if (starred) "已收藏" else "未收藏"}"))
        val config = compose.onNodeWithTag("article-swipe").fetchSemanticsNode().config
        assertEquals("阅读文章", config[SemanticsActions.OnClick].label)
        assertEquals(buildList {
            if (!read) add("标记已读")
            add(if (starred) "取消收藏" else "收藏文章")
        }, config[SemanticsActions.CustomActions].map { it.label })
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role,
            androidx.compose.ui.semantics.Role.Button), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("已读", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("未读", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(node("article-source").fetchSemanticsNode().boundsInRoot.right <=
            node("article-date").fetchSemanticsNode().boundsInRoot.left)
        assertEquals(1, layout("article-date").lineCount)
        listOf("article-title", "article-summary", "article-source", "article-date").forEach {
            assertContrast(layout(it).layoutInput.style.color)
        }
    }

    // Only the geometry test calls this. The orchestrator must validate this exact owner
    // and file inventory before exporting/removing outputs between matrix runs.
    // Never overwrite or clean up here, including partial output from a failed run.
    private fun prepareAppearanceCapture(): File? {
        if (InstrumentationRegistry.getArguments().getString("appearanceCapture") != "1") return null
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "appearance-screenshots")
        check(directory.isDirectory || directory.mkdir()) { "Cannot create $directory" }
        check(directory.list()?.isEmpty() == true) { "Refusing existing appearance output: $directory" }
        val configuration = compose.activity.resources.configuration
        val files = JSONArray()
        for ((name, read) in listOf("unread.png" to false, "read.png" to true)) {
            files.put(JSONObject().put("name", name).put("isRead", read).put("isStarred", false)
                .put("caption", "ISOLATED COMPONENT HOST — ${if (read) "read" else "unread"}, unstarred; " +
                    "not actual MainActivity/public RSS"))
        }
        val inventory = JSONObject()
            .put("owner", "com.fluxa.app.ArticleListAppearanceUiTest#chineseGeometryAndActualThemeColorsSurviveReadAndStarRecomposition")
            .put("inventoryFile", "inventory.json")
            .put("configuration", JSONObject().put("orientation", configuration.orientation)
                .put("fontScale", configuration.fontScale)
                .put("night", configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK))
            .put("files", files)
        Files.newOutputStream(File(directory, "inventory.json").toPath(), CREATE_NEW, WRITE).use {
            it.write(inventory.toString(2).toByteArray(Charsets.UTF_8))
        }
        return directory
    }

    private fun captureAppearance(directory: File?, name: String) {
        if (directory == null) return
        require(name == "unread.png" || name == "read.png")
        compose.waitForIdle()
        // CREATE_NEW also refuses races with another writer; no existing file is truncated.
        Files.newOutputStream(File(directory, name).toPath(), CREATE_NEW, WRITE).use { output ->
            val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
                "UiAutomation did not return a screenshot"
            }
            try {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG compression failed" }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun chineseGeometryAndActualThemeColorsSurviveReadAndStarRecomposition() {
        host(raster())
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("article-thumbnail", true).fetchSemanticsNodes().size == 1 }
        val tags = listOf("article-swipe", "article-title", "article-summary", "article-thumbnail", "article-source", "article-date")
        val bounds = tags.associateWith { node(it).fetchSemanticsNode().boundsInRoot }
        val title = layout("article-title")
        val summary = layout("article-summary")
        assertEquals(3, title.lineCount)
        assertEquals(1, summary.lineCount)
        assertEquals(FontWeight.SemiBold, title.layoutInput.style.fontWeight)
        assertEquals(bounds.getValue("article-title").top, bounds.getValue("article-thumbnail").top, .1f)
        assertQuietRow(false, false)
        val captureDirectory = prepareAppearanceCapture()
        captureAppearance(captureDirectory, "unread.png")
        action("收藏文章")
        assertFalse(article.value.isRead)
        assertQuietRow(false, true)
        tags.forEach { assertEquals(bounds[it], node(it).fetchSemanticsNode().boundsInRoot) }
        action("标记已读")
        assertQuietRow(true, true)
        assertNotEquals(title.layoutInput.style.color, layout("article-title").layoutInput.style.color)
        assertNotEquals(summary.layoutInput.style.color, layout("article-summary").layoutInput.style.color)
        assertEquals(FontWeight.SemiBold, layout("article-title").layoutInput.style.fontWeight)
        tags.forEach { assertEquals(bounds[it], node(it).fetchSemanticsNode().boundsInRoot) }
        action("取消收藏")
        assertQuietRow(true, false)
        tags.forEach { assertEquals(bounds[it], node(it).fetchSemanticsNode().boundsInRoot) }
        captureAppearance(captureDirectory, "read.png")
    }

    @Test fun onlyDecodedEmbeddedRasterReservesThumbnailAndGap() {
        host(raster())
        var fullWidth: Float? = null
        for (html in listOf("", "<img src='https://invalid.example/never-fetch.png'>",
            "<img src='data:image/png;base64,bm90LWEtcmFzdGVy'>")) {
            compose.runOnIdle { article.value = article.value.copy(contentHtml = raster()) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("article-thumbnail", true).fetchSemanticsNodes().size == 1 }
            val withImage = node("article-title").fetchSemanticsNode().boundsInRoot.width
            compose.runOnIdle { article.value = article.value.copy(contentHtml = html) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("article-thumbnail", true).fetchSemanticsNodes().isEmpty() }
            val withoutImage = node("article-title").fetchSemanticsNode().boundsInRoot.width
            val density = compose.activity.resources.displayMetrics.density
            assertEquals(66f * density, withoutImage - withImage, 1f)
            fullWidth?.let { assertEquals(it, withoutImage, 0f) }
            fullWidth = withoutImage
        }
    }

    private fun raster(): String {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        val bytes = ByteArrayOutputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            it.toByteArray()
        }
        bitmap.recycle()
        return "<img src='data:image/png;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}'>"
    }
}
