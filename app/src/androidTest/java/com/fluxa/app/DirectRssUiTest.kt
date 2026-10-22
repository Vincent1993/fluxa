package com.fluxa.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.fluxa.app.ui.feedlist.FeedListScreen
import com.fluxa.app.ui.feedlist.FeedListState
import com.fluxa.app.ui.feedlist.LocalSubscriptionActions
import com.fluxa.app.data.directrss.*
import com.fluxa.app.ui.theme.FluxaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Offline-only UI contract tests; no app database, credentials or network. */
class DirectRssUiTest {
    @get:Rule val compose = createComposeRule()
    // Explicit synthetic callback fixture; no production bypass of preview.
    private val fixture = RssFeed("Fixture source", listOf(RssItem("fixture", "Fixture article", 0, "", "", listOf("早报"))))
    private val actions = LocalSubscriptionActions(discover = { url -> FeedDiscovery(listOf(FeedCandidate(url, fixture.title)), fixture) })
    @Test fun localIsDefaultFailureKeepsInputAndSuccessfulRetryClosesDialog() {
        var state by mutableStateOf(FeedListState())
        var localCalls = 0
        var remoteCalls = 0
        var submitted = ""
        compose.setContent {
            FluxaTheme {
                FeedListScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, { remoteCalls++ }, {},
                    localActions = actions,
                    onLocalSubscribe = { url, daily ->
                        assertFalse(daily)
                        submitted = url; localCalls++
                        state = state.copy(busy = true, message = null)
                    })
            }
        }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithContentDescription("添加订阅").performClick()
        compose.onNodeWithText("HTTP / HTTPS RSS 地址").performScrollTo().performTextInput("https://example.org/feed")
        compose.onNodeWithContentDescription("发现订阅源").performScrollTo().performClick()
        compose.onNodeWithText("Fixture article").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("确认订阅", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("确认订阅").assertIsNotEnabled()
        compose.onNodeWithText("正在订阅…").assertExists()
        compose.runOnIdle { state = state.copy(busy = false, message = "离线，请重试") }
        compose.onAllNodesWithText("离线，请重试").onLast().assertExists()
        compose.onAllNodesWithText("https://example.org/feed").onFirst().assertExists()
        compose.onNodeWithContentDescription("确认订阅", useUnmergedTree = true).performClick()
        compose.runOnIdle { state = state.copy(busy = false, subscriptionSuccess = 1, message = "订阅成功") }
        compose.onNodeWithText("添加 RSS 订阅").assertDoesNotExist()
        assertEquals(2, localCalls); assertEquals(0, remoteCalls); assertEquals("https://example.org/feed", submitted)
    }
    @Test fun shortcutExplicitlyRequestsDailyRatherThanAllSite() {
        var daily: Boolean? = null
        var url = ""
        compose.setContent { FluxaTheme {
            FeedListScreen(FeedListState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                localActions = actions,
                onLocalSubscribe = { value, only -> url = value; daily = only })
        } }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithContentDescription("添加订阅").performClick()
        compose.onNodeWithContentDescription("订阅爱范儿早报").performScrollTo().performClick()
        compose.onNodeWithText("Fixture article").performScrollTo().assertExists()
        assertNull(daily)
        compose.onNodeWithContentDescription("确认订阅").performClick()
        assertEquals(true, daily); assertEquals("https://www.ifanr.com/feed", url)
    }
}
