package com.fluxa.app

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxa.app.data.directrss.*
import com.fluxa.app.data.local.*
import com.fluxa.app.ui.feedlist.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real backup service/Room/typed prefs. SAF provider selection itself remains a root native check. */
class LocalBackupStage4UiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun previewRequiresAccessibleConfirmAndMetadataRestoreDoesNotInventBody() {
        val i = InstrumentationRegistry.getInstrumentation(); val context = i.targetContext
        val owner = "fluxa-stage4-owned-api36"
        check(InstrumentationRegistry.getArguments().getString("stage4Owner") == owner)
        check(android.os.Build.VERSION.SDK_INT == 36)
        check(File(context.filesDir, "$owner.marker").readText().trim() == owner)
        val prefix = "owned-backup-${java.util.UUID.randomUUID()}"
        val owned = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(owned, DirectRssDatabase::class.java).build()
        val store = ViewModelStore()
        val source = DirectSubscription("direct-rss:owned-backup", "Owned backup", "https://example.org/rss", false, paused = true)
        val row = DirectArticle("direct-rss:owned-backup-a", "guid", source.id, "Owned restored title", 123, "", "https://example.org/a", readLater = true)
        val payload = BackupPayload(emptyList(), listOf(source), listOf(row), mapOf(row.id to stableHash("<p>Not exported</p>")),
            emptyList(), emptyList(), emptyList(), emptySet(), emptySet(), TriageConfig(),
            ReaderSnapshot(ReaderSettings(), emptyMap(), ReadingSession(listOf(row.id))), false, false)
        lateinit var model: LocalBackupViewModel
        try {
            compose.activityRule.scenario.awaitStableHost()
            compose.runOnIdle {
                model = LocalBackupViewModel(LocalBackup(db, ReaderPreferences(owned, scope), LocalOfflineSettings(owned), TriageSettings(owned)))
                store.put("backup", model)
            }
            compose.setContent { MaterialTheme { LocalBackupSheet({}, {}, model) } }
            fun scopeRow(label: String): SemanticsNodeInteraction {
                val named = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(label))
                // Name, role, checked state and action must belong to ONE actual unmerged node.
                val action = named and isToggleable() and hasClickAction() and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
                compose.onAllNodes(named, useUnmergedTree = true).assertCountEquals(1)
                compose.onAllNodes(action, useUnmergedTree = true).assertCountEquals(1)
                compose.onAllNodes(hasAnyAncestor(named) and (hasClickAction() or isToggleable()),
                    useUnmergedTree = true).assertCountEquals(0)
                return compose.onNode(action, useUnmergedTree = true).performScrollTo()
                    .assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
            }
            val bodiesLabel = "包含 RSS 与已提取全文"
            val imagesLabel = "另含已保存图片"
            // One real named toggle per row, with no second actionable child Switch.
            compose.onAllNodes(isToggleable(), useUnmergedTree = true).assertCountEquals(2)
            scopeRow(bodiesLabel).assertIsOff().assertIsEnabled()
            scopeRow(imagesLabel).assertIsOff().assertIsNotEnabled()
            scopeRow(bodiesLabel).performTouchInput { click() }
            scopeRow(bodiesLabel).assertIsOn()
            scopeRow(imagesLabel).assertIsEnabled().performClick()
            scopeRow(imagesLabel).assertIsOn()
            scopeRow(bodiesLabel).performClick()
            scopeRow(bodiesLabel).assertIsOff()
            scopeRow(imagesLabel).assertIsOff().assertIsNotEnabled()
            // Exercise both the touch path and the real semantics callback; disabled actions refuse changes.
            scopeRow(imagesLabel).performSemanticsAction(SemanticsActions.OnClick) { assertFalse(it()) }
            scopeRow(imagesLabel).assertIsOff().assertIsNotEnabled()
            scopeRow(bodiesLabel).performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
            scopeRow(imagesLabel).assertIsEnabled().performTouchInput { click() }
            scopeRow(bodiesLabel).assertIsOn()
            scopeRow(imagesLabel).assertIsOn()
            val releaseRead = java.util.concurrent.CountDownLatch(1)
            try {
                compose.runOnIdle { model.read {
                    // Bounded safety deadline, with enough room for all busy-state UI assertions.
                    check(releaseRead.await(30, java.util.concurrent.TimeUnit.SECONDS))
                    BackupJson.encode(payload).inputStream()
                } }
                compose.waitUntil(5_000) { model.state.value.busy }
                listOf(bodiesLabel, imagesLabel).forEach { label ->
                    scopeRow(label).assertIsOn().assertIsNotEnabled()
                        .performSemanticsAction(SemanticsActions.OnClick) { assertFalse(it()) }
                    scopeRow(label).performTouchInput { click() }
                    scopeRow(label).assertIsOn().assertIsNotEnabled()
                }
                listOf("关闭备份", "导出本地备份", "选择备份预览").forEach { label ->
                    compose.onNodeWithContentDescription(label).assertIsNotEnabled()
                }
                compose.onAllNodes(isToggleable(), useUnmergedTree = true).assertCountEquals(2)
                compose.runOnIdle { assertTrue("Read must remain held during busy assertions", model.state.value.busy) }
                runBlocking { assertTrue(db.dao().articles().isEmpty()) }
            } finally { releaseRead.countDown() }
            compose.waitUntil(5_000) { model.state.value.preview != null && !model.state.value.busy }
            scopeRow(bodiesLabel).assertIsOn().assertIsEnabled()
                .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
            scopeRow(bodiesLabel).assertIsOff().assertIsEnabled()
            scopeRow(imagesLabel).assertIsOff().assertIsNotEnabled()
            runBlocking { assertTrue(db.dao().articles().isEmpty()) }
            compose.onNodeWithContentDescription("确认合并备份").performScrollTo().assertWidthIsAtLeast(androidx.compose.ui.unit.Dp(48f))
                .assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f)).performClick()
            compose.waitUntil(5_000) { !model.state.value.busy && model.state.value.preview == null }
            runBlocking { val restored = requireNotNull(db.dao().article(row.id)); assertEquals("", restored.body); assertTrue(restored.readLater) }
            assertNull(model.state.value.pendingPreferences)
        } finally {
            i.runOnMainSync { store.clear() }; scope.cancel(); db.close()
            listOf("reader_private", "direct_rss_offline", "rss_triage_v1").forEach { context.deleteSharedPreferences("$prefix-$it") }
        }
    }
}
