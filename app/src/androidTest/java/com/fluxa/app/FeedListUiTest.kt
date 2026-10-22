package com.fluxa.app

import androidx.compose.ui.test.*
import androidx.compose.runtime.*
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.feedlist.*
import com.fluxa.app.ui.theme.FluxaTheme
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

class FeedListUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun awaitHostBeforeContent() {
        compose.activityRule.scenario.awaitStableHost()
    }

    // After native popup teardown, API 26 can report stale accessibility windows.
    // For the activity-owned topbar only, trust the current scenario's native host.
    // Popup controls still require a focused UiAutomation window. Never retry a click.
    private fun touchWhenSettled(description: String) {
        val node = compose.onNode(hasContentDescription(description) and hasClickAction())
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = automation.serviceInfo
        val originalFlags = info.flags
        data class HostSample(
            val activity: ComponentActivity,
            val decor: android.view.View,
            val frame: Rect,
            val visibleFrame: Rect,
            val orientation: Int,
            val fontScale: Float,
            val widthDp: Int,
            val heightDp: Int,
            val bounds: ComposeRect
        )
        val activityOwned = description == "更多"
        var targetDetails = "not sampled"
        var decorDetails = "not sampled"
        var windowDetails = "not sampled"
        var stabilityDetails = "not sampled"
        fun sample(): Any? {
            targetDetails = "sampling display state"
            val displayed = try { node.assertIsDisplayed(); true } catch (_: AssertionError) { false }
            val bounds = if (displayed) {
                val target = node.fetchSemanticsNode()
                ComposeRect(target.positionOnScreen, target.boundsInWindow.size)
            } else null
            targetDetails = "displayed=$displayed bounds=$bounds"
            fun contains(frame: Rect): Boolean = bounds != null &&
                bounds.left.isFinite() && bounds.top.isFinite() &&
                bounds.right.isFinite() && bounds.bottom.isFinite() &&
                bounds.width.isFinite() && bounds.height.isFinite() &&
                bounds.width > 0 && bounds.height > 0 &&
                bounds.left >= frame.left && bounds.top >= frame.top &&
                bounds.right <= frame.right && bounds.bottom <= frame.bottom

            var host: HostSample? = null
            compose.activityRule.scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val location = IntArray(2).also(decor::getLocationOnScreen)
                val frame = Rect(location[0], location[1],
                    location[0] + decor.width, location[1] + decor.height)
                val visible = Rect().also(decor::getWindowVisibleDisplayFrame)
                val config = activity.resources.configuration
                val resumed = activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
                val ready = resumed && !activity.isFinishing && !activity.isDestroyed &&
                    decor.hasWindowFocus() && decor.isAttachedToWindow &&
                    decor.isShown && decor.isLaidOut && !decor.isLayoutRequested &&
                    contains(frame) && contains(visible)
                decorDetails = "activity=${System.identityHashCode(activity)} " +
                    "decor=${System.identityHashCode(decor)} lifecycle=${activity.lifecycle.currentState} " +
                    "finishing=${activity.isFinishing} destroyed=${activity.isDestroyed} " +
                    "focus=${decor.hasWindowFocus()} attached=${decor.isAttachedToWindow} " +
                    "shown=${decor.isShown} laidOut=${decor.isLaidOut} " +
                    "layoutRequested=${decor.isLayoutRequested} frame=$frame visibleFrame=$visible " +
                    "config=$config ready=$ready"
                if (ready && bounds != null) {
                    host = HostSample(activity, decor, frame, visible, config.orientation,
                        config.fontScale, config.screenWidthDp, config.screenHeightDp, bounds)
                }
            }
            var focused: Int? = null
            val details = mutableListOf<String>()
            val windows = automation.windows
            try {
                windows.forEach { window ->
                    val frame = Rect().also(window::getBoundsInScreen)
                    val root = window.root
                    try {
                        val packageName = root?.packageName?.toString()
                        details += "id=${window.id} type=${window.type} focused=${window.isFocused} " +
                            "active=${window.isActive} package=$packageName frame=$frame"
                        if (focused == null && window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                            window.isFocused && packageName ==
                            InstrumentationRegistry.getInstrumentation().targetContext.packageName && contains(frame)) {
                            focused = window.id
                        }
                    } finally {
                        @Suppress("DEPRECATION")
                        root?.recycle()
                    }
                }
            } finally {
                windowDetails = details.joinToString(prefix = "[", postfix = "]")
                windows.forEach { @Suppress("DEPRECATION") it.recycle() }
            }
            return if (activityOwned) host else focused?.let { it to bounds }
        }
        try {
            info.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            var previous: Any? = null
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                val current = sample()
                val stable = current != null && current == previous
                stabilityDetails = "ready=${current != null} matchesPrevious=$stable"
                previous = current
                stable
            }
            node.assertIsDisplayed()
            if (activityOwned) {
                // Recheck the current instance/focus/frame after the wait, just before injection.
                val current = sample()
                val stable = current != null && current == previous
                stabilityDetails = "preInjectionReady=${current != null} matchesPrevious=$stable"
                check(stable) { "Activity host changed before touch injection" }
            }
            node.performTouchInput { click() }
            compose.waitForIdle()
        } catch (failure: Throwable) {
            failure.addSuppressed(AssertionError(
                "touchWhenSettled target='$description' activityOwned=$activityOwned; " +
                    "readiness failed or touch flow failed (no click retry). " +
                    "API 26 accessibility-window state can lag native popup teardown; " +
                    "activity-owned readiness uses the current native host, not that cache. " +
                    "target=[$targetDetails] stability=[$stabilityDetails] " +
                    "decor=[$decorDetails] windows=$windowDetails"
            ))
            throw failure
        } finally {
            val restored = automation.serviceInfo
            restored.flags = originalFlags
            automation.serviceInfo = restored
        }
    }

    @Test fun cacheRemainsReadableDuringOfflineErrorAndActionsAreAccessible() {
        var opened: String? = null
        var starred: String? = null
        var read: String? = null
        val article = Article("tag:google.com,2005:reader/item/123", "离线缓存正文", "我的订阅",
            Instant.EPOCH, false, false, "<p>Cached</p>")
        compose.setContent {
            FluxaTheme {
                FeedListScreen(FeedListState(articles = listOf(article), message = "离线，可阅读缓存", pendingCount = 7),
                    { opened = it }, {}, {}, {}, {}, {}, {}, { read = it }, { starred = it }, {}, {})
            }
        }
        compose.onNodeWithText("离线缓存正文").assertIsDisplayed()
        compose.onNodeWithText("离线，可阅读缓存").assertDoesNotExist()
        compose.onNodeWithText("7 项操作待同步").assertDoesNotExist()
        touchWhenSettled("更多")
        touchWhenSettled("同步详情")
        compose.onNodeWithText("离线，可阅读缓存").assertIsDisplayed()
        compose.onNodeWithText("7 项操作待同步").assertIsDisplayed()
        touchWhenSettled("关闭")
        val row = compose.onNodeWithTag("article-swipe")
        val actions = row.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertTrue(actions.single { it.label == "收藏文章" }.action())
            assertTrue(actions.single { it.label == "标记已读" }.action())
        }
        compose.onAllNodesWithContentDescription("收藏文章").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("标记已读").assertCountEquals(0)
        assertEquals(article.id, read)
        assertNull(opened)
        assertEquals(article.id, starred)
        compose.onNodeWithText("离线缓存正文").performClick()
        assertEquals(article.id, opened)
    }

    @Test fun emptyCacheOffersRefreshSubscriptionAndLogin() {
        compose.setContent {
            FluxaTheme {
                FeedListScreen(FeedListState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithContentDescription("刷新").assertIsDisplayed()
        touchWhenSettled("更多")
        touchWhenSettled("添加订阅")
        compose.onNodeWithText("添加 RSS 订阅").assertIsDisplayed()
        compose.onNodeWithContentDescription("取消").performClick()
        touchWhenSettled("更多")
        touchWhenSettled("账户与同步设置")
        compose.onNodeWithContentDescription("登录 NewsBlur").assertIsDisplayed()
    }

    @Test fun backgroundRefreshCanBeEnabledAndDisabledFromSettings() {
        var changed: Boolean? = null
        compose.setContent {
            var enabled by remember { mutableStateOf(false) }
            FluxaTheme {
                FeedListScreen(FeedListState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                    backgroundEnabled = enabled, onBackgroundSync = { enabled = it; changed = it })
            }
        }
        touchWhenSettled("更多")
        touchWhenSettled("账户与同步设置")
        val toggle = compose.onNodeWithContentDescription("NewsBlur 后台刷新")
        toggle.assertIsNotSelected().performClick().assertIsSelected()
        assertEquals(true, changed)
        toggle.performClick().assertIsNotSelected()
        assertEquals(false, changed)
    }
}
