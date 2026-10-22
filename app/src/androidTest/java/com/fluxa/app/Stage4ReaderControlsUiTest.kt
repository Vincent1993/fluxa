package com.fluxa.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.feedlist.ArticleCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/** Owned Compose fixture; does not claim real engine/translator/MainActivity acceptance. */
class Stage4ReaderControlsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun requireOwner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val owner = "fluxa-stage4-owned-api36"
        check(InstrumentationRegistry.getArguments().getString("stage4Owner") == owner)
        check(android.os.Build.VERSION.SDK_INT == 36)
        check(File(instrumentation.targetContext.filesDir, "$owner.marker").readText().trim() == owner)
    }
    @Test fun actualRowDensitySummaryAndThumbnailSwitchesRetainReadSemanticsWithoutButtons() {
        requireOwner()
        val comfortable = mutableStateOf(false)
        val summary = mutableStateOf(true)
        val article = Article("owned-stage4", "Owned title", "Owned source", Instant.EPOCH, true, false,
            "<p>Owned body</p>", source = "direct-rss:owned", summary = "Owned summary")
        compose.activityRule.scenario.awaitStableHost()
        compose.setContent { MaterialTheme {
            ArticleCard(article, {}, {}, {}, comfortable = comfortable.value, showSummary = summary.value, showThumbnail = false)
        } }
        val row = compose.onNodeWithTag("article-swipe")
        compose.onNodeWithTag("article-summary", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("article-thumbnail", useUnmergedTree = true).assertDoesNotExist()
        val compact = row.fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { comfortable.value = true }
        assertTrue(row.fetchSemanticsNode().boundsInRoot.height > compact)
        compose.runOnIdle { summary.value = false }
        compose.onNodeWithTag("article-summary", useUnmergedTree = true).assertDoesNotExist()
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已读 · 未收藏"))
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button), useUnmergedTree = true).assertCountEquals(0)
    }
}
