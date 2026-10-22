package com.fluxa.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.fluxa.app.ui.feedlist.*
import com.fluxa.app.ui.theme.FluxaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FeedFilterLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun allFiveFiltersRemainUnclippedAndClickableAtNarrowWidthAndLargeFont() {
        var fontScale by mutableFloatStateOf(1f)
        var state by mutableStateOf(FeedListState())
        val selected = mutableListOf<ArticleFilter>()
        compose.activityRule.scenario.awaitStableHost()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                FluxaTheme {
                    Box(Modifier.width(260.dp).testTag("narrow-list")) {
                        FeedListScreen(state, {}, {}, {}, {}, {}, { filter ->
                            selected += filter
                            state = state.copy(filter = filter)
                        }, {}, {}, {}, {}, {}, onLocalSubscribe = { _, _ -> }, onTriage = {})
                    }
                }
            }
        }
        for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            for (filter in ArticleFilter.entries) {
                val control = compose.onNodeWithContentDescription(filter.label)
                control.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                val node = control.fetchSemanticsNode()
                val viewport = compose.onNodeWithTag("narrow-list").fetchSemanticsNode().boundsInRoot
                // Compare clipped bounds with actual layout size: a partly visible crescent fails.
                assertEquals(node.size.width.toFloat(), node.boundsInRoot.width, 1f)
                assertEquals(node.size.height.toFloat(), node.boundsInRoot.height, 1f)
                assertTrue(node.boundsInRoot.left >= viewport.left)
                assertTrue(node.boundsInRoot.right <= viewport.right)
                control.performClick().assertIsSelected()
                compose.runOnIdle { assertEquals(filter, selected.last()) }
            }
        }
        compose.runOnIdle { assertEquals(ArticleFilter.entries.toList() + ArticleFilter.entries.toList(), selected) }
    }
}
