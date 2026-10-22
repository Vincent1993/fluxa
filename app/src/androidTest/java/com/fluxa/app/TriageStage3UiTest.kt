package com.fluxa.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxa.app.domain.model.Subscription
import com.fluxa.app.ui.feedlist.*
import com.fluxa.app.ui.theme.FluxaTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in owned-device Compose callback test. No real network, Room, browser, or native-reader claim. */
class TriageStage3UiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun reachableFilterSheetPersistsFullCallbackConfigurationAndDoesNotBroadenMissingSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val owner = "fluxa-stage3-owned-api36"
        assumeTrue(InstrumentationRegistry.getArguments().getString("stage3Owner") == owner)
        assumeTrue(File(instrumentation.targetContext.filesDir, "$owner.marker").let { it.isFile && it.readText().trim() == owner })
        compose.activityRule.scenario.awaitStableHost()
        var state by mutableStateOf(FeedListState(subscriptions = listOf(Subscription("direct-rss:one", "Owned source", "https://example.org/feed", isLocal = true))))
        var opened by mutableStateOf(false)
        compose.setContent { FluxaTheme {
            FeedListScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                onLocalSubscribe = { _, _ -> }, onTriage = { opened = true })
            if (opened) TriageSheet(state, { scope -> state = state.copy(scope = scope, query = scope.query, source = scope.source, filter = scope.filter) },
                { state = state.copy(triage = it) }, { emptyList() }, { opened = false })
        } }
        compose.onNodeWithContentDescription("筛选与规则").performClick()
        compose.onNodeWithContentDescription("筛选来源 Owned source").performScrollTo().performClick()
        compose.onNodeWithContentDescription("此来源隐藏已读").performScrollTo().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertTrue(state.triage.sources.getValue("direct-rss:one").hideRead) }
        compose.onAllNodesWithContentDescription("24 小时").onLast().performScrollTo().performClick()
        compose.onNodeWithText("搜索名称").performScrollTo().performTextInput("Owned saved filter")
        compose.onNodeWithContentDescription("保存当前搜索").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(ArticleFilter.Last24Hours, state.triage.saved.single().scope.filter)
            assertEquals("direct-rss:one", state.triage.saved.single().scope.source)
            state = state.copy(subscriptions = emptyList(), source = null, scope = SearchScope())
        }
        compose.onNodeWithContentDescription("应用搜索 Owned saved filter").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("direct-rss:one", state.source) }
        compose.onNodeWithContentDescription("删除搜索 Owned saved filter").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(state.triage.saved.isEmpty()) }
    }
}
