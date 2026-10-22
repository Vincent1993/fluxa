package com.fluxa.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.fluxa.app.data.repository.ReadChange
import com.fluxa.app.data.repository.ReadReceipt
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.feedlist.*
import com.fluxa.app.ui.theme.FluxaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Owned UI fixtures; checks the real bulk panel and real LazyColumn, not database/device acceptance. */
class BulkReadStage2UiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun explicitDateExcludesBoundaryAndFutureArrivalsWhileAllListAnchorDoesNotJump() {
        compose.activityRule.scenario.awaitStableHost()
        val boundary = LocalDate.of(2026, 10, 9).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val original = List(30) { index -> Article("bulk-$index", "Owned bulk $index", "Owned feed",
            if (index == 0) boundary else boundary.minusSeconds(index.toLong()), false, false, "<p>Owned body</p>") }
        var state by mutableStateOf(FeedListState(articles = original))
        var captured = emptyList<String>()
        compose.setContent { FluxaTheme {
            FeedListScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                onLocalSubscribe = { _, _ -> }, onBulkRead = { ids ->
                    captured = ids
                    state = state.copy(articles = state.articles.map { if (it.id in ids) it.copy(isRead = true) else it },
                        readReceipt = ReadReceipt(ids.map { ReadChange(it, false, "owned-ui") }))
                }, onUndoRead = {
                    state = state.copy(articles = state.articles.map { if (it.id in captured) it.copy(isRead = false) else it }, readReceipt = null)
                })
        } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(6)
        compose.waitForIdle()
        val title = hasTestTag("article-title") and hasText("Owned bulk 5")
        val before = compose.onNode(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithContentDescription("当前范围批量已读").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("2026-10-09")
        compose.runOnIdle {
            // A later arrival is appended, so it cannot itself shift this viewport.
            state = state.copy(articles = state.articles + Article("arrival", "Owned later arrival", "Owned feed",
                boundary.minusSeconds(1), false, false, "body"))
        }
        compose.onNodeWithContentDescription("确认当前范围批量已读").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(original.drop(1).map { it.id }, captured)
            assertFalse(state.articles.first().isRead)
            assertFalse(state.articles.last().isRead)
        }
        val after = compose.onNode(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, 2f)
        compose.onNodeWithContentDescription("撤销批量已读").performClick()
        compose.waitForIdle()
        assertEquals(before.top, compose.onNode(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top, 2f)
        compose.runOnIdle { assertTrue(state.articles.none { it.isRead }) }
    }
}
