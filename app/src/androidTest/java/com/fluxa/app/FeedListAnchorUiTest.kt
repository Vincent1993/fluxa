package com.fluxa.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.feedlist.*
import com.fluxa.app.ui.theme.FluxaTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

class FeedListAnchorUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun awaitHostBeforeContent() {
        compose.activityRule.scenario.awaitStableHost()
    }

    private fun title(text: String) = compose.onNode(
        hasTestTag("article-title") and hasText(text), useUnmergedTree = true)

    private fun assertStableBounds(text: String, expected: Rect) {
        repeat(3) {
            compose.waitForIdle()
            val actual = title(text).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Expected $expected, got $actual", listOf(
                actual.left - expected.left, actual.top - expected.top,
                actual.right - expected.right, actual.bottom - expected.bottom
            ).all { abs(it) <= 2f })
            compose.mainClock.advanceTimeByFrame()
        }
    }

    @Test fun partialSearchHeaderSurvivesEmptyReentryAndReadRecomposition() {
        // The caller retains the fixture, as a NavHost-scoped view model does.
        val articles = List(20) { index ->
            Article("anchor-$index", "owned $index", "Anchor feed", Instant.EPOCH,
                false, true, "<p>Cached</p>")
        }
        var state by mutableStateOf(FeedListState(
            articles = articles, query = "owned", filter = ArticleFilter.Starred))
        var inReader by mutableStateOf(false)
        var opened: String? = null
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            FluxaTheme {
                // A short, narrow viewport exercises landscape geometry without replacing
                // LocalDensity/fontScale or changing the externally configured activity.
                Box(Modifier.width(320.dp).height(320.dp)) {
                    if (!inReader) holder.SaveableStateProvider("feed-entry") {
                        FeedListScreen(state, { opened = it; inReader = true }, {}, {}, {},
                            { state = state.copy(query = it) },
                            { state = state.copy(filter = it) }, {},
                            { id -> state = state.copy(articles = state.articles.map {
                                if (it.id == id) it.copy(isRead = true) else it
                            }) }, {}, {}, {})
                    }
                }
            }
        }
        compose.waitForIdle()
        val list = compose.onNode(hasScrollToIndexAction())
        // The context title is fixed in TopAppBar; measure the scrolling item 0 filter.
        val filterBefore = compose.onNodeWithContentDescription("收藏")
            .assertIsSelected().fetchSemanticsNode().boundsInRoot
        // Establish the partial-header anchor before looking up a possibly uncomposed row.
        // This is initial setup only; never scroll to repair the position on return.
        list.performScrollToIndex(1)
        compose.waitForIdle()
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val row = compose.onNode(hasTestTag("article-swipe") and hasText("owned 0"))
        // size is the layout's UNCLIPPED height, unlike boundsInRoot in a short window.
        val rowHeight = row.fetchSemanticsNode().size.height.toFloat()
        val margin = with(compose.density) { 4.dp.toPx() }
        val headerTail = minOf(with(compose.density) { 48.dp.toPx() },
            viewport.height - rowHeight - margin).toInt().toFloat()
        // The header ends with 12dp padding: require actual filter pixels above it.
        assertTrue("Viewport must fit the whole row and a visible filter tail",
            rowHeight > 0f && headerTail > with(compose.density) { 12.dp.toPx() } + 2f)
        list.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            assertTrue(scrollBy(0f, -headerTail))
        }
        compose.waitForIdle()
        val rowNode = row.assertIsDisplayed().fetchSemanticsNode()
        val rowBounds = rowNode.boundsInRoot
        val titleNode = title("owned 0").assertIsDisplayed().fetchSemanticsNode()
        val before = titleNode.boundsInRoot
        val source = compose.onAllNodesWithTag("article-source", useUnmergedTree = true)[0]
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val filter = compose.onNodeWithContentDescription("收藏")
            .assertIsSelected().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val date = compose.onAllNodesWithTag("article-date", useUnmergedTree = true)[0]
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        // Positive scroll AND a still-visible piece of item 0 proves this is a
        // partial header anchor, not an article-index anchor or an unscrolled list.
        assertTrue("Header must have scrolled", filter.top < filterBefore.top - 2f)
        assertTrue("Header remains in viewport", filter.bottom > viewport.top && filter.top < viewport.bottom)
        assertTrue("Article follows the visible header", date.top >= filter.bottom)
        assertTrue("Source follows the visible header", source.top >= filter.bottom)
        assertTrue("Entire article is in viewport",
            rowBounds.top >= viewport.top && rowBounds.bottom <= viewport.bottom)
        assertEquals("Article must not be clipped", rowNode.size.height.toFloat(), rowBounds.height, 0f)
        assertTrue(before.top >= viewport.top && before.bottom <= viewport.bottom)
        assertEquals("Title must not be clipped", titleNode.size.height.toFloat(), before.height, 0f)
        assertStableBounds("owned 0", before)
        row.performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(articles[0].id, opened); assertTrue(inReader) }
        compose.onAllNodesWithTag("article-title", useUnmergedTree = true).assertCountEquals(0)

        // Dispose/re-enter the same saveable entry with the transient empty emission.
        compose.runOnIdle { state = state.copy(articles = emptyList()); inReader = false }
        compose.waitForIdle()
        compose.onAllNodesWithTag("article-title", useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { state = state.copy(articles = articles) }
        // No scroll, gesture or click on return.
        assertStableBounds("owned 0", before)
        compose.onNode(hasSetTextAction() and hasText("owned"), useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("收藏").assertIsSelected()
        compose.runOnIdle {
            assertEquals("owned", state.query)
            assertEquals(ArticleFilter.Starred, state.filter)
        }

        // A later ordinary read must not replay the captured open position.
        list.performScrollToIndex(4)
        compose.waitForIdle()
        val later = compose.onNode(hasTestTag("article-swipe") and hasText("owned 3"))
        val laterBounds = title("owned 3").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val read = later.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "标记已读" }
        compose.runOnIdle { assertTrue(read.action()) }
        assertStableBounds("owned 3", laterBounds)
        compose.runOnIdle { assertTrue(state.articles[3].isRead) }
    }
}
