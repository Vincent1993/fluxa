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
            val node = title(text).assertIsDisplayed().fetchSemanticsNode()
            val actual = compose.runOnIdle { node.boundsInRoot }
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
        // The expanded filter collection wraps over several lines. Measure the last
        // chip: a partial header must show its tail, not necessarily its first row.
        compose.onNodeWithContentDescription("收藏").assertIsSelected()
        val tailLabel = ArticleFilter.entries.last().label
        val initialFilter = compose.onNodeWithContentDescription(tailLabel).fetchSemanticsNode()
        val filterBeforeTop = compose.runOnIdle { initialFilter.positionInRoot.y }
        // Establish the partial-header anchor before looking up a possibly uncomposed row.
        // This is initial setup only; never scroll to repair the position on return.
        list.performScrollToIndex(1)
        compose.waitForIdle()
        val initialList = list.fetchSemanticsNode()
        val row = compose.onNode(hasTestTag("article-swipe") and hasText("owned 0"))
        // size is the layout's UNCLIPPED height, unlike boundsInRoot in a short window.
        val initialRow = row.fetchSemanticsNode()
        val (initialViewport, rowHeight) = compose.runOnIdle {
            initialList.boundsInRoot to initialRow.size.height.toFloat()
        }
        val margin = with(compose.density) { 4.dp.toPx() }
        val headerTail = minOf(with(compose.density) { 48.dp.toPx() },
            initialViewport.height - rowHeight - margin).toInt().toFloat()
        // The header ends with 12dp padding: require actual filter pixels above it.
        assertTrue("Viewport must fit the whole row and a visible filter tail",
            rowHeight > 0f && headerTail > with(compose.density) { 12.dp.toPx() } + 2f)
        list.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            assertTrue(scrollBy(0f, -headerTail))
        }
        compose.waitForIdle()
        val listNode = list.fetchSemanticsNode()
        val rowNode = row.assertIsDisplayed().fetchSemanticsNode()
        val titleNode = title("owned 0").assertIsDisplayed().fetchSemanticsNode()
        val sourceNode = compose.onAllNodesWithTag("article-source", useUnmergedTree = true)[0]
            .assertIsDisplayed().fetchSemanticsNode()
        val filterNode = compose.onNodeWithContentDescription(tailLabel)
            .assertIsDisplayed().fetchSemanticsNode()
        val dateNode = compose.onAllNodesWithTag("article-date", useUnmergedTree = true)[0]
            .assertIsDisplayed().fetchSemanticsNode()
        // SemanticsNode coordinates are live layout reads, not a snapshot at fetch time.
        // Sample parent/child geometry together on the UI thread after the initial scroll.
        // Comparing a pre-scroll viewport with independently sampled child frames can
        // diagnose impossible containment while the list is settling.
        val frame = compose.runOnIdle {
            AnchorFrame(listNode.boundsInRoot, rowNode.boundsInRoot, titleNode.boundsInRoot,
                sourceNode.boundsInRoot, filterNode.boundsInRoot, dateNode.boundsInRoot,
                rowNode.size.height.toFloat(), titleNode.size.height.toFloat())
        }
        val (viewport, rowBounds, before, source, filter, date) = frame
        // Positive scroll AND a still-visible piece of item 0 proves this is a
        // partial header anchor, not an article-index anchor or an unscrolled list.
        assertTrue("Header must have scrolled", filter.top < filterBeforeTop - 2f)
        assertTrue("Header remains in viewport", filter.bottom > viewport.top && filter.top < viewport.bottom)
        assertTrue("Article follows the visible header", date.top >= filter.bottom)
        assertTrue("Source follows the visible header", source.top >= filter.bottom)
        assertTrue("Entire article is in viewport",
            rowBounds.top >= viewport.top && rowBounds.bottom <= viewport.bottom)
        assertEquals("Article must not be clipped: $frame", frame.rowHeight, rowBounds.height, 0f)
        assertTrue("Title must fit viewport: $frame",
            before.top >= viewport.top && before.bottom <= viewport.bottom)
        assertEquals("Title must not be clipped: $frame", frame.titleHeight, before.height, 0f)
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
        val laterTitle = title("owned 3").assertIsDisplayed().fetchSemanticsNode()
        val laterBounds = compose.runOnIdle { laterTitle.boundsInRoot }
        val read = later.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "标记已读" }
        compose.runOnIdle { assertTrue(read.action()) }
        assertStableBounds("owned 3", laterBounds)
        compose.runOnIdle { assertTrue(state.articles[3].isRead) }
    }

    private data class AnchorFrame(
        val viewport: Rect, val row: Rect, val title: Rect, val source: Rect,
        val filter: Rect, val date: Rect, val rowHeight: Float, val titleHeight: Float
    )
}
