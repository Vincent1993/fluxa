package com.fluxa.app

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.fluxa.app.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

// A real Activity/Hilt/Room/WebView flow using local fixtures on the test device.
// No NewsBlur credentials or live network writes are involved.
class OfflineAppFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun loginCacheReaderAndRestartPreserveOfflineActions(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = "999999:offline-ui-fixture"
        val db = Room.databaseBuilder(context, FluxaDatabase::class.java, "newsblur.db")
            .addMigrations(DatabaseMigrations.FROM_1, DatabaseMigrations.FROM_2).build()
        // This test only runs in a dedicated emulator; it neither resets nor deletes the database.
        db.articleDao().upsertAll(listOf(ArticleEntity(id, "离线阅读验证：缓存正文与收藏", "测试订阅",
            1700000000, false, false, "<h2>离线缓存正文</h2><p>即使没有网络，这段正文仍然可以阅读。</p><p>字体档位与收藏状态可立即调整。</p>", "999999")))
        fun screenshot(name: String) {
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            // WebView renders on a separate compositor; allow its frame to settle.
            Thread.sleep(300)
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(10000) {
                    runCatching { compose.onNodeWithText("登录 NewsBlur · 支持免费账号").assertIsDisplayed() }.isSuccess
                }
                compose.onNodeWithText("登录 NewsBlur · 支持免费账号").assertIsDisplayed()
                screenshot("validation-login.png")
                compose.onNodeWithText("阅读本地缓存").performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithText("离线阅读验证：缓存正文与收藏").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("离线阅读验证：缓存正文与收藏").performClick()
                compose.onNodeWithText("24号").performClick()
                compose.onNodeWithText("收藏", useUnmergedTree = true).performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithText("取消收藏").fetchSemanticsNodes().isNotEmpty() }
                screenshot("validation-reader.png")
                compose.onNodeWithContentDescription("返回文章列表").performClick()
                compose.onNodeWithText("离线阅读验证：缓存正文与收藏").assertIsDisplayed()
                screenshot("validation-cache.png")
            }
            val cached = db.articleDao().getById(id)!!
            assertTrue(cached.isRead)
            assertTrue(cached.isStarred)
            assertEquals(2, db.pendingActionDao().getAllOrdered().count { it.articleId == id })
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("阅读本地缓存").performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithText("离线阅读验证：缓存正文与收藏").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("离线阅读验证：缓存正文与收藏").performClick()
                compose.onNodeWithText("取消收藏").assertIsDisplayed()
            }
        } finally {
            db.pendingActionDao().getAllOrdered().filter { it.articleId == id }.forEach { db.pendingActionDao().deleteById(it.id) }
            db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE id = ?", arrayOf(id))
            db.close()
        }
    }
}
