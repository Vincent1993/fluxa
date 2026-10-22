package com.fluxa.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.fluxa.app.ui.components.IconAction
import com.fluxa.app.ui.components.UiState
import com.fluxa.app.ui.feedlist.*
import com.fluxa.app.ui.login.LoginScreen
import com.fluxa.app.ui.theme.FluxaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Real Compose accessibility contracts, not a TalkBack speech walkthrough. */
class IconActionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @org.junit.Before fun awaitHostBeforeContent() {
        compose.activityRule.scenario.awaitStableHost()
    }

    @Test fun nameRoleSelectionDisabledSizeAndNoTextChildren() {
        var calls = 0
        compose.setContent { FluxaTheme { Column {
            IconAction("确认", Icons.Outlined.Check, { calls++ }, selected = true)
            IconAction("不可用", Icons.Outlined.Check, { calls++ }, enabled = false, selected = false)
        } } }
        val confirm = compose.onNodeWithContentDescription("确认")
        confirm.assertIsEnabled().assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已选择"))
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
        compose.onAllNodesWithContentDescription("确认", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(hasAnyAncestor(hasContentDescription("确认")) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).assertCountEquals(0)
        confirm.performClick()
        val disabled = compose.onNodeWithContentDescription("不可用")
        disabled.assertIsNotEnabled().assertIsNotSelected()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        disabled.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun longPressShowsTooltipWithoutExecutingAction() {
        var calls = 0
        compose.setContent { FluxaTheme { IconAction("确认", Icons.Outlined.Check, { calls++ }) } }
        compose.onNodeWithText("确认").assertDoesNotExist()
        compose.onNodeWithContentDescription("确认").performTouchInput { longClick() }
        compose.onNodeWithText("确认").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun loginControlsHaveNamesButNoCaptionsAndPreserveCallbacks() {
        var cache = 0; var github = 0; var signup = 0
        compose.setContent { FluxaTheme {
            LoginScreen(UiState.Empty, { _, _ -> }, { cache++ }, { github++ }, { signup++ })
        } }
        compose.onNodeWithContentDescription("登录").performScrollTo().assertIsNotEnabled()
        listOf("本地 RSS · 无需账号", "直接阅读", "GitHub 收藏 · 无需登录", "在 NewsBlur 网站创建账号").forEach {
            compose.onNodeWithContentDescription(it).performScrollTo()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(2, cache); assertEquals(1, github); assertEquals(1, signup) }
    }

    @Test fun rssFilterAndDialogConfirmCancelAreAccessibleIconControls() {
        var filter: ArticleFilter? = null; var calls = 0
        compose.setContent { FluxaTheme {
            FeedListScreen(FeedListState(), {}, {}, {}, {}, {}, { filter = it }, {}, {}, {}, {}, {},
                onLocalSubscribe = { _, _ -> calls++ },
                // Synthetic preview fixture, isolated from application data/network.
                localActions = com.fluxa.app.ui.feedlist.LocalSubscriptionActions(discover = { url ->
                    com.fluxa.app.data.directrss.FeedDiscovery(
                        listOf(com.fluxa.app.data.directrss.FeedCandidate(url, "Fixture")),
                        com.fluxa.app.data.directrss.RssFeed("Fixture", listOf(
                            com.fluxa.app.data.directrss.RssItem("1", "Fixture article", 0, "", "", emptyList()))))
                }))
        } }
        compose.onNodeWithContentDescription("全部").assertIsSelected()
        compose.onNodeWithContentDescription("未读").performClick()
        compose.runOnIdle { assertEquals(ArticleFilter.Unread, filter) }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithContentDescription("添加订阅").performClick()
        compose.onNodeWithContentDescription("本地 RSS").assertIsSelected()
        compose.onNodeWithContentDescription("确认订阅").assertIsNotEnabled()
        compose.onNodeWithText("HTTP / HTTPS RSS 地址").performScrollTo().performTextInput("https://example.invalid/feed")
        compose.onNodeWithContentDescription("确认订阅").assertIsNotEnabled()
        compose.onNodeWithContentDescription("发现订阅源").performScrollTo().performClick()
        compose.onNodeWithContentDescription("确认订阅").assertIsEnabled()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("取消").performClick()
        compose.onNodeWithText("添加 RSS 订阅").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, calls) }
    }
}
