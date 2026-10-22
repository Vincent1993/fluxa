package com.fluxa.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real window/insets/keyboard checks; also run with external landscape/large-font overrides. */
class AdaptiveLayoutDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun loginRemainsReachableAboveKeyboardAndSystemBars() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.awaitStableHost()
            val sync = DeviceFlowSync(compose, scenario)
            sync.step("login keyboard and system-bar layout") {
                var usableHeight = 0
                var safeTop = 0
                var keyboardBottom = 0
                fun readInsets() {
                    scenario.onActivity { activity ->
                        val view = activity.window.decorView
                        val insets = ViewCompat.getRootWindowInsets(view)!!
                        safeTop = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                            WindowInsetsCompat.Type.displayCutout()).top
                        keyboardBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                        usableHeight = view.height - maxOf(keyboardBottom,
                            insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
                    }
                }
                sync.login()
                val titleNode = compose.onNodeWithText("Fluxa")
                titleNode.performScrollTo()
                sync.settled(titleNode)
                readInsets()
                val title = titleNode.fetchSemanticsNode().boundsInWindow
                assertTrue("Title must avoid the status bar: title=$title, safeTop=$safeTop", title.top >= safeTop)
                val username = compose.onNodeWithText("NewsBlur 用户名")
                username.performScrollTo()
                sync.settled(username)
                username.performTouchInput { click() }
                compose.onNodeWithText("NewsBlur 用户名").performTextInput("layout-fixture")
                sync.step("IME shown") { sync.await { readInsets(); keyboardBottom > 0 } }
                val cacheNode = compose.onNodeWithContentDescription("直接阅读")
                scrollControlIntoView(compose, scenario, cacheNode, dismissKeyboard = false)
                readInsets()
                assertTrue("Reachability must be checked with IME still visible", keyboardBottom > 0)
                cacheNode.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                val cache = cacheNode.fetchSemanticsNode().boundsInWindow
                val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                if (bitmap != null) {
                    File(context.filesDir, "validation-layout-api${Build.VERSION.SDK_INT}.png")
                        .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                assertTrue("Cache action bottom=${cache.bottom}, usableHeight=$usableHeight, IME=$keyboardBottom",
                    cache.bottom <= usableHeight + 1)
                // Exercise the same native dismissal + resize retry used by login navigation.
                val localRss = compose.onNodeWithContentDescription("本地 RSS · 无需账号")
                scrollControlIntoView(compose, scenario, localRss)
                sync.step("IME dismissed") { sync.await { readInsets(); keyboardBottom == 0 } }
                localRss.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                scrollControlIntoView(compose, scenario, cacheNode)
                cacheNode.assertIsDisplayed()
            }
        }
    }
}
