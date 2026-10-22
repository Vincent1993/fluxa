package com.fluxa.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.feedlist.*
import com.fluxa.app.ui.theme.FluxaTheme as AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class ArticleSwipeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @org.junit.Before fun awaitHostBeforeContent() {
        compose.activityRule.scenario.awaitStableHost()
    }
    private var leftEdge = 0f
    private var rightEdge = 0f
    private var touchSlop = 0f

    @Composable
    private fun FluxaTheme(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        leftEdge = maxOf(with(density) { 24.dp.toPx() },
            WindowInsets.systemGestures.getLeft(density, direction).toFloat())
        rightEdge = maxOf(with(density) { 24.dp.toPx() },
            WindowInsets.systemGestures.getRight(density, direction).toFloat())
        touchSlop = LocalViewConfiguration.current.touchSlop
        AppTheme(content = content)
    }

    private fun TouchInjectionScope.deepThreshold() =
        minOf(176.dp.toPx(), (width - leftEdge - rightEdge).coerceAtLeast(1f) / 2f)

    private fun TouchInjectionScope.interiorStart(left: Boolean): Offset {
        val margin = 8.dp.toPx()
        val x = if (left) width - rightEdge - margin else leftEdge + margin
        assertTrue("DOWN must be strictly inside actual protected edges",
            x > leftEdge && x < width - rightEdge)
        assertTrue("Interior must accommodate a deep gesture and slop",
            width - leftEdge - rightEdge - margin > deepThreshold() * 1.15f + touchSlop)
        return Offset(x, height / 2f)
    }
    @Test fun configuredDeepCommitsOnlyChosenActionAndRetractionStillCancels() {
        val actions = mutableListOf<SwipeAction>()
        var legacyCalls = 0
        compose.setContent { FluxaTheme {
            ArticleSwipe(false, false, { legacyCalls++ }, { legacyCalls++ },
                choice = SwipeChoice(left = SwipeAction.ToggleRead, deepLeft = SwipeAction.ReadLater),
                onAction = { actions += it }) {
                Box(Modifier.fillMaxWidth().height(120.dp))
            }
        } }
        val row = compose.onNodeWithTag("article-swipe")
        row.performTouchInput {
            val start = interiorStart(true); val distance = deepThreshold() * 1.15f + touchSlop
            down(start); moveTo(start - Offset(distance, 0f), 400); up()
        }
        compose.runOnIdle { assertEquals(listOf(SwipeAction.ReadLater), actions); assertEquals(0, legacyCalls) }
        row.performTouchInput {
            val start = interiorStart(true); val distance = deepThreshold() * 1.15f + touchSlop
            down(start); moveTo(start - Offset(distance, 0f), 400); moveTo(start - Offset(8f, 0f), 400); up()
        }
        row.performTouchInput { val start = interiorStart(true); down(start); moveTo(start - Offset(5f, 0f), 200); up() }
        row.performTouchInput { val start = interiorStart(true); down(start); moveTo(start + Offset(-20f, 180f), 300); up() }
        row.performTouchInput { down(Offset(1f, height / 2f)); moveTo(Offset(width * .8f, height / 2f), 400); up() }
        compose.runOnIdle { assertEquals(listOf(SwipeAction.ReadLater), actions); assertEquals(0, legacyCalls) }
        row.performTouchInput {
            val start = interiorStart(true); down(start)
            moveTo(start - Offset(deepThreshold() * .7f + touchSlop, 0f), 400); up()
        }
        compose.runOnIdle { assertEquals(listOf(SwipeAction.ReadLater, SwipeAction.ToggleRead), actions); assertEquals(0, legacyCalls) }
    }

    @Test fun rejectedStreamsNeverActOrOpenDescendant() {
        var opens = 0; var reads = 0; var stars = 0
        compose.setContent { FluxaTheme {
            ArticleSwipe(false, false, { reads++ }, { stars++ }) {
                androidx.compose.material3.TextButton(onClick = { opens++ },
                    modifier = Modifier.fillMaxWidth().height(120.dp)) {
                    androidx.compose.material3.Text("Descendant")
                }
            }
        } }
        val row = compose.onNodeWithTag("article-swipe")
        val cases = listOf("cancel", "reversal", "40dp subthreshold", "diagonal", "multi-pointer",
            "left edge", "right edge", "vertical without scroll parent")
        var caseIndex = 0
        fun unchanged() = compose.runOnIdle {
            val name = cases[caseIndex++]
            assertEquals("$name opens", 0, opens)
            assertEquals("$name reads", 0, reads)
            assertEquals("$name stars", 0, stars)
        }
        row.performTouchInput {
            down(Offset(width * .2f, height / 2f))
            moveTo(Offset(width * .8f, height / 2f), 300)
            cancel()
        }
        unchanged()
        // Deep preview then cross neutral: this entire stream must cancel.
        row.performTouchInput {
            down(Offset(width * .15f, height / 2f))
            moveTo(Offset(width * .9f, height / 2f), 300)
            moveTo(Offset(width * .1f, height / 2f), 200)
            up()
        }
        unchanged()
        row.performTouchInput {
            swipe(Offset(width * .3f, height / 2f), Offset(width * .3f + 40.dp.toPx(), height / 2f), 300)
        }
        unchanged()
        row.performTouchInput {
            swipe(Offset(width * .4f, height * .2f), Offset(width * .4f + 60.dp.toPx(), height * .85f), 300)
        }
        unchanged()
        row.performTouchInput {
            down(0, Offset(width * .2f, height * .4f))
            moveTo(0, Offset(width * .5f, height * .4f), 200)
            down(1, Offset(width * .5f, height * .7f))
            moveTo(0, Offset(width * .85f, height * .3f), 200)
            moveTo(1, Offset(width * .8f, height * .8f), 200)
            up(1); up(0)
        }
        unchanged()
        row.performTouchInput {
            val from = Offset(leftEdge - 1f, height / 2f)
            down(from)
            moveTo(from + Offset(deepThreshold() * 1.15f + touchSlop, 0f), 300)
        }
        compose.onNodeWithTag("article-swipe-preview", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已取消 · 抬手重试"))
        row.performTouchInput { up() }
        unchanged()
        row.performTouchInput {
            val from = Offset(width - rightEdge + 1f, height / 2f)
            down(from)
            moveTo(from - Offset(deepThreshold() * 1.15f + touchSlop, 0f), 300)
        }
        compose.onNodeWithTag("article-swipe-preview", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已取消 · 抬手重试"))
        row.performTouchInput { up() }
        unchanged()
        row.performTouchInput {
            swipe(Offset(width / 2f, height * .25f), Offset(width / 2f, height * .75f), 300)
        }
        unchanged()
        // A canceled stream must not poison the next real tap.
        compose.onNodeWithText("Descendant").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, opens); assertEquals(0, reads); assertEquals(0, stars) }
    }
    @Test fun fourStagesRetractionAndPreviewOnlyAtNarrowWidthLargeFont() {
        var reads = 0; var stars = 0; var opens = 0
        var slop = 0f
        compose.setContent { FluxaTheme {
            val density = LocalDensity.current
            slop = LocalViewConfiguration.current.touchSlop
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.width(260.dp)) {
                    ArticleSwipe(false, false, { reads++ }, { stars++ }, articleId = "stages", onOpen = { opens++ }) {
                        androidx.compose.material3.Text("Stages", Modifier.fillMaxWidth().height(120.dp))
                    }
                }
            }
        } }
        val row = compose.onNodeWithTag("article-swipe")
        fun gesture(left: Boolean, deep: Boolean, retract: Boolean = false) {
            val before = reads to stars
            row.performTouchInput {
                val sign = if (left) -1 else 1
                val from = interiorStart(left)
                val threshold = deepThreshold()
                down(from)
                moveTo(from + Offset(sign * (threshold * .75f + slop), 0f), 300)
            }
            compose.runOnIdle { assertEquals(before, reads to stars); assertEquals(0, opens) }
            val shallowAction = if (left) "标记已读" else "收藏"
            compose.onNodeWithTag("article-swipe-preview", useUnmergedTree = true)
                .assertContentDescriptionEquals(shallowAction)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                    "第一段·$shallowAction·松手执行，拖回取消"))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
            if (deep) row.performTouchInput {
                val sign = if (left) -1 else 1
                val from = interiorStart(left)
                val threshold = deepThreshold()
                moveTo(from + Offset(sign * (threshold * 1.15f + slop), 0f), 200)
            }
            // Down and crossing both thresholds only preview, including persisted callback counts.
            compose.waitForIdle()
            assertEquals(before, reads to stars)
            val heldAction = if (left != deep) "标记已读" else "收藏"
            compose.onNodeWithTag("article-swipe-preview", useUnmergedTree = true)
                .assertContentDescriptionEquals(heldAction)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                    "${if (deep) "第二段" else "第一段"}·$heldAction·松手执行，拖回取消"))
            row.performTouchInput {
                if (retract) {
                    val sign = if (left) -1 else 1
                    val from = interiorStart(left)
                    val threshold = deepThreshold()
                    moveTo(from + Offset(sign * (threshold * .75f + slop), 0f), 200)
                }
                up()
            }
            compose.waitForIdle()
        }
        gesture(left = true, deep = false)
        compose.runOnIdle { assertEquals(1 to 0, reads to stars) }
        gesture(left = true, deep = true)
        compose.runOnIdle { assertEquals(1 to 1, reads to stars) }
        gesture(left = false, deep = false)
        compose.runOnIdle { assertEquals(1 to 2, reads to stars) }
        gesture(left = false, deep = true)
        compose.runOnIdle { assertEquals(2 to 2, reads to stars) }
        gesture(left = true, deep = true, retract = true)
        compose.runOnIdle { assertEquals(3 to 2, reads to stars) }
        gesture(left = false, deep = true, retract = true)
        compose.runOnIdle { assertEquals(3 to 3, reads to stars); assertEquals(0, opens) }
    }

    @Test fun alreadyReadStagesAreNoOpsAndNextTapStillOpens() {
        var reads = 0; var stars = 0; var opens = 0
        var slop = 0f
        compose.setContent { FluxaTheme {
            slop = LocalViewConfiguration.current.touchSlop
            ArticleSwipe(true, false, { reads++ }, { stars++ }, articleId = "read", onOpen = { opens++ }) {
                androidx.compose.material3.Text("Read", Modifier.fillMaxWidth().height(120.dp))
            }
        } }
        val row = compose.onNodeWithTag("article-swipe")
        row.performTouchInput {
            val from = interiorStart(left = true)
            down(from)
            moveTo(from - Offset(deepThreshold() * .75f + slop, 0f), 300)
        }
        compose.onNodeWithTag("article-swipe-preview", useUnmergedTree = true)
            .assertContentDescriptionEquals("已读 / 无需操作")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "第一段·已读·无需操作"))
        compose.runOnIdle { assertEquals(0, reads); assertEquals(0, stars) }
        row.performTouchInput { up() }
        row.performTouchInput {
            val from = interiorStart(left = false)
            swipe(from, from + Offset(deepThreshold() * 1.15f + touchSlop, 0f), 300)
        }
        compose.runOnIdle { assertEquals(0, reads); assertEquals(0, stars); assertEquals(0, opens) }
        row.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun rapidResetAndIdentityChangeNeverCommitOldRow() {
        var id by mutableStateOf("old")
        var shown by mutableStateOf(true)
        val callbacks = mutableListOf<String>()
        compose.setContent { FluxaTheme {
            if (shown) ArticleSwipe(false, false, { callbacks += "$id:read" }, { callbacks += "$id:star" }, articleId = id) {
                androidx.compose.material3.Text("Identity", Modifier.fillMaxWidth().height(120.dp))
            }
        } }
        val row = compose.onNodeWithTag("article-swipe")
        row.performTouchInput {
            repeat(3) {
                val from = interiorStart(left = false)
                swipe(from, from + Offset(deepThreshold() * 1.15f + touchSlop, 0f), 100)
            }
        }
        compose.runOnIdle { assertEquals(listOf("old:read", "old:read", "old:read"), callbacks) }
        row.performTouchInput {
            val from = interiorStart(left = false)
            down(from)
            moveTo(from + Offset(deepThreshold() * 1.15f + touchSlop, 0f), 300)
        }
        compose.runOnIdle { id = "new" }
        row.performTouchInput { up() }
        compose.runOnIdle { assertEquals(3, callbacks.size) }
        row.performTouchInput {
            val from = interiorStart(left = false)
            down(from)
            moveTo(from + Offset(deepThreshold() * 1.15f + touchSlop, 0f), 300)
        }
        compose.runOnIdle { shown = false }
        compose.onRoot().performTouchInput { cancel() }
        compose.runOnIdle { assertEquals(3, callbacks.size) }
    }

    @Test fun heldDeepStarredPreviewNamesUnstarWithoutCallingIt() {
        var reads = 0; var stars = 0
        var slop = 0f
        compose.setContent { FluxaTheme {
            slop = LocalViewConfiguration.current.touchSlop
            ArticleSwipe(false, true, { reads++ }, { stars++ }) {
                androidx.compose.material3.Text("Starred", Modifier.fillMaxWidth().height(120.dp))
            }
        } }
        val row = compose.onNodeWithTag("article-swipe")
        row.performTouchInput {
            val from = interiorStart(left = true)
            down(from)
            moveTo(from - Offset(deepThreshold() * 1.15f + slop, 0f), 300)
        }
        compose.onNodeWithTag("article-swipe-preview", useUnmergedTree = true)
            .assertContentDescriptionEquals("取消收藏")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                "第二段·取消收藏·松手执行，拖回取消"))
        compose.runOnIdle { assertEquals(0, reads); assertEquals(0, stars) }
        row.performTouchInput { cancel() }
        compose.runOnIdle { assertEquals(0, reads); assertEquals(0, stars) }
    }

    @Test fun batchedInvalidCancelThenValidDownCannotCommitOldStream() {
        var reads = 0; var stars = 0
        compose.setContent { FluxaTheme {
            ArticleSwipe(false, false, { reads++ }, { stars++ }) {
                androidx.compose.material3.Text("Batched", Modifier.fillMaxWidth().height(120.dp))
            }
        } }
        val row = compose.onNodeWithTag("article-swipe")
        // One injection batch: no idle/collector opportunity between old Cancel and new DOWN.
        row.performTouchInput {
            down(Offset(1f, height / 2f))
            moveTo(Offset(width * .8f, height / 2f), 200)
            cancel()
            val from = interiorStart(left = false)
            down(from)
            moveTo(from + Offset(deepThreshold() * 1.15f + touchSlop, 0f), 200)
        }
        compose.runOnIdle { assertEquals(0, reads); assertEquals(0, stars) }
        row.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, reads); assertEquals(0, stars) }
    }

    @Test fun horizontalActionsCancelClickAndVerticalScrollStillWorks() {
        var opens = 0; var reads = 0; var stars = 0
        val articles = (0..25).map { Article("swipe-$it", "Swipe article $it", "Fixture", Instant.EPOCH,
            false, false, "<p>Offline body</p>") }
        compose.setContent { FluxaTheme {
            FeedListScreen(FeedListState(articles = articles), { opens++ }, {}, {}, {}, {}, {}, {},
                { reads++ }, { stars++ }, {}, {})
        } }
        val row = compose.onNode(hasTestTag("article-swipe") and hasText("Swipe article 0"))
        row.performScrollTo()
        row.performTouchInput {
            val from = interiorStart(left = true)
            val distance = deepThreshold() * .75f + touchSlop
            swipe(from, from - Offset(distance, 0f), 600)
        }
        compose.runOnIdle { assertEquals(0, opens); assertEquals(1, reads); assertEquals(0, stars) }
        row.performTouchInput {
            val from = interiorStart(left = false)
            val distance = deepThreshold() * .75f + touchSlop
            swipe(from, from + Offset(distance, 0f), 600)
        }
        compose.runOnIdle { assertEquals(0, opens); assertEquals(1, reads); assertEquals(1, stars) }
        row.performTouchInput {
            down(Offset(width * .3f, height / 2f))
            moveTo(Offset(width * .8f, height / 2f), 300)
            moveTo(Offset(width * .36f, height / 2f), 300)
            up()
        }
        compose.runOnIdle { assertEquals(0, opens); assertEquals(1, reads); assertEquals(1, stars) }
        row.performTouchInput {
            down(Offset(width * .2f, height / 2f))
            moveTo(Offset(width * .8f, height / 2f), 300)
            cancel()
        }
        compose.runOnIdle { assertEquals(0, opens); assertEquals(1, stars) }
        val actions = row.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single { it.label == "收藏文章" }.action()) }
        compose.runOnIdle { assertEquals(2, stars); assertEquals(0, opens) }
        compose.onAllNodesWithContentDescription("标记已读").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("收藏文章").assertCountEquals(0)
        compose.runOnIdle { assertTrue(actions.single { it.label == "标记已读" }.action()) }
        compose.runOnIdle { assertEquals(2, reads); assertEquals(0, opens) }
        val before = compose.onNodeWithText("Swipe article 0").fetchSemanticsNode().boundsInRoot.top
        compose.onNode(hasScrollToNodeAction()).performTouchInput { swipeUp(durationMillis = 600) }
        val after = compose.onAllNodesWithText("Swipe article 0").fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
        assertTrue("Vertical scroll must move or recycle the first row", after == null || after < before)
        compose.runOnIdle { assertEquals(0, opens); assertEquals(2, reads); assertEquals(2, stars) }
    }
}
