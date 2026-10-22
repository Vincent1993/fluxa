package com.fluxa.app

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.espresso.Espresso.pressBack
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
        val title = "离线阅读验证：缓存正文与收藏"
        val bodyText = "即使没有网络，这段正文仍然可以阅读。"
        val db = Room.databaseBuilder(context, FluxaDatabase::class.java, "newsblur.db")
            .addMigrations(DatabaseMigrations.FROM_1, DatabaseMigrations.FROM_2).build()
        // Dedicated test device: cleanup below touches only this fictional fixture.
        fun screenshot(name: String) {
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                ?: return // Some legacy emulator display overrides cannot capture a frame.
            File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            db.articleDao().upsertAll(listOf(ArticleEntity(id, title, "测试订阅",
                1700000000, false, false, "<h2>离线缓存正文</h2><p>$bodyText</p><p>字体档位与收藏状态可立即调整。</p>", "999999")))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val sync = DeviceFlowSync(compose, scenario)
                sync.step("first launch through persisted offline actions") {
                    sync.login()
                    compose.onNodeWithText("登录 NewsBlur · 支持免费账号").assertIsDisplayed()
                    screenshot("validation-login.png")
                    sync.scrollAndClick("直接阅读")
                    sync.article(title, click = true)
                    sync.reader(title)
                    sync.body(bodyText)
                    compose.onNodeWithContentDescription("阅读设置").performTouchInput { click() }
                    val font = compose.onNodeWithTag("reader-font")
                    font.performScrollTo()
                    sync.settled(font)
                    font.performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .625f, height / 2f)) }
                    compose.onNodeWithText("字号 24").assertExists()
                    val done = compose.onNodeWithContentDescription("完成")
                    sync.settled(done)
                    done.performTouchInput { click() }
                    sync.reader(title)
                    sync.body(bodyText)
                    val star = compose.onNodeWithContentDescription("收藏", useUnmergedTree = true)
                    sync.settled(star)
                    star.performTouchInput { click() }
                    sync.settled(compose.onNodeWithContentDescription("取消收藏"))
                    screenshot("validation-reader.png")
                    // Exercise AndroidX/system back after target 36 enables predictive back.
                    pressBack()
                    sync.article(title, click = false)
                    compose.onNodeWithText(title).assertIsDisplayed()
                    screenshot("validation-cache.png")
                    // Wait for asynchronous Room writes before closing the Activity, not a flow retry.
                    sync.step("read/star/queue persisted before restart") {
                        sync.await {
                            runBlocking {
                                val cached = db.articleDao().getById(id)
                                cached?.isRead == true && cached.isStarred &&
                                    db.pendingActionDao().getAllOrdered().count { it.articleId == id && it.actionType != "LocalRevision" } == 2
                            }
                        }
                        runBlocking {
                            val cached = db.articleDao().getById(id)!!
                            assertTrue(cached.isRead)
                            assertTrue(cached.isStarred)
                            assertEquals(2, db.pendingActionDao().getAllOrdered().count { it.articleId == id && it.actionType != "LocalRevision" })
                            assertTrue(cached.contentHtml.contains(bodyText))
                        }
                    }
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val sync = DeviceFlowSync(compose, scenario)
                sync.step("restart preserves cached reader and star") {
                    sync.login()
                    sync.scrollAndClick("直接阅读")
                    sync.article(title, click = true)
                    sync.reader(title)
                    sync.body(bodyText)
                    sync.settled(compose.onNodeWithContentDescription("取消收藏"))
                    compose.onNodeWithContentDescription("取消收藏").assertIsDisplayed()
                }
            }
        } finally {
            db.pendingActionDao().getAllOrdered().filter { it.articleId == id }.forEach { db.pendingActionDao().deleteById(it.id) }
            db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE id = ?", arrayOf(id))
            db.close()
        }
    }
}
