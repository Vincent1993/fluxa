package com.fluxa.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
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
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("NewsBlur 用户名").fetchSemanticsNodes().isNotEmpty()
            }
            readInsets()
            val title = compose.onNodeWithText("Fluxa").fetchSemanticsNode().boundsInRoot
            assertTrue("Title must avoid the status bar", title.top >= safeTop)
            compose.onNodeWithText("NewsBlur 用户名").performClick().performTextInput("layout-fixture")
            compose.waitUntil(10000) { readInsets(); keyboardBottom > 0 }
            compose.onNodeWithText("阅读本地缓存").performScrollTo().assertIsDisplayed()
            readInsets()
            val cache = compose.onNodeWithText("阅读本地缓存").fetchSemanticsNode().boundsInRoot
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (bitmap != null) {
                File(context.filesDir, "validation-layout-api${Build.VERSION.SDK_INT}.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            assertTrue("Cache action bottom=${cache.bottom}, usableHeight=$usableHeight, IME=$keyboardBottom",
                cache.bottom <= usableHeight + 1)
            pressBack()
            compose.waitUntil(10000) { readInsets(); keyboardBottom == 0 }
            compose.onNodeWithText("阅读本地缓存").performScrollTo().assertIsDisplayed()
        }
    }
}
