package com.fluxa.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.fluxa.app.ui.feedlist.FeedListScreen
import com.fluxa.app.ui.feedlist.FeedListState
import com.fluxa.app.ui.theme.FluxaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** In-memory screen state only: no account, database or preference mutations. */
class ReadingRedesignUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun externallyAppliedSearchBecomesVisibleAndCanBeCleared() {
        val state = mutableStateOf(FeedListState())
        compose.activityRule.scenario.awaitStableHost()
        compose.setContent { FluxaTheme {
            FeedListScreen(state.value, {}, {}, {}, {},
                { query -> state.value = state.value.copy(query = query) }, {}, {}, {}, {}, {}, {})
        } }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        // Reproduces applying a saved search after the screen is already composed.
        compose.runOnIdle { state.value = state.value.copy(query = "saved search") }
        compose.onNode(hasSetTextAction()).performScrollTo().assertIsDisplayed().assertTextContains("saved search")
        compose.onAllNodesWithContentDescription("清空搜索").assertCountEquals(1)
        compose.onNodeWithContentDescription("清空搜索").performClick()
        compose.runOnIdle { assertEquals("", state.value.query) }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("again")
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.runOnIdle { assertEquals("", state.value.query) }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test fun pendingSyncStatusOpensDetailsAndMenuRetainsItsAction() {
        var github = 0
        compose.activityRule.scenario.awaitStableHost()
        compose.setContent { FluxaTheme {
            FeedListScreen(FeedListState(pendingCount = 2), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                onOpenGitHub = { github++ })
        } }
        compose.onNodeWithContentDescription("2 项操作待同步").performScrollTo().performClick()
        compose.onNodeWithText("同步状态").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithContentDescription("GitHub 收藏").performClick()
        compose.runOnIdle { assertEquals(1, github) }
    }
}
