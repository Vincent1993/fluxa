package com.fluxa.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.fluxa.app.data.directrss.*
import com.fluxa.app.domain.model.Subscription
import com.fluxa.app.ui.feedlist.*
import com.fluxa.app.ui.theme.FluxaTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** All callbacks and article titles here are synthetic UI fixtures, never app data. */
class LocalSubscriptionSheetsTest {
    @get:Rule val compose = createComposeRule()
    private val candidate = FeedCandidate("https://example.org/feed", "Fixture candidate")
    private val feed = RssFeed("Fixture source", listOf(RssItem("1", "Fixture actual article", 0, "", "", emptyList())))

    @Test fun candidateSelectionFetchesOnlyChosenSourceAndFailureRetainsInput() {
        var previews = 0; var confirms = 0; var fail = true
        val actions = LocalSubscriptionActions(discover = {
            FeedDiscovery(listOf(candidate, FeedCandidate("https://example.org/other", "Other fixture")))
        }, preview = { url ->
            assertEquals(candidate.url, url); previews++
            if (fail) error("Fixture preview failed")
            feed
        })
        compose.setContent { FluxaTheme { AddSubscriptionDialog(false, null, {}, {}, { url, daily ->
            assertEquals(candidate.url, url); assertFalse(daily); confirms++
        }, actions) } }
        compose.onNodeWithText("HTTP / HTTPS RSS 地址").performScrollTo().performTextInput("https://example.org/page")
        compose.onNodeWithContentDescription("发现订阅源").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, previews); assertEquals(0, confirms) }
        compose.onNodeWithContentDescription("确认订阅").assertIsNotEnabled()
        compose.onNodeWithContentDescription("预览 Fixture candidate").performScrollTo().performClick()
        compose.onNodeWithText("Fixture preview failed").performScrollTo().assertExists()
        compose.onNodeWithText("https://example.org/page").assertExists()
        compose.onNodeWithContentDescription("确认订阅").assertIsNotEnabled()
        compose.runOnIdle { fail = false }
        compose.onNodeWithContentDescription("预览 Fixture candidate").performScrollTo().performClick()
        compose.onNodeWithText("Fixture actual article").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("确认订阅").performClick()
        compose.runOnIdle { assertEquals(2, previews); assertEquals(1, confirms) }
    }

    @Test fun obsoleteDiscoveryCannotRestorePreviewAfterUrlEditEvenIfCallbackIgnoresCancellation() {
        val pending = CompletableDeferred<FeedDiscovery>()
        var started = false; var confirms = 0
        compose.setContent { FluxaTheme { AddSubscriptionDialog(false, null, {}, {}, { _, _ -> confirms++ },
            LocalSubscriptionActions(discover = {
                started = true
                withContext(NonCancellable) { pending.await() }
            })) } }
        compose.onNodeWithText("HTTP / HTTPS RSS 地址").performScrollTo().performTextInput("https://example.org/old")
        compose.onNodeWithContentDescription("发现订阅源").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(started) }
        compose.onNodeWithText("HTTP / HTTPS RSS 地址").performScrollTo().performTextReplacement("https://example.org/new")
        compose.runOnIdle { pending.complete(FeedDiscovery(listOf(candidate), feed)) }
        compose.waitForIdle()
        compose.onNodeWithText("Fixture actual article").assertDoesNotExist()
        compose.onNodeWithContentDescription("确认订阅").assertIsNotEnabled()
        compose.onNodeWithText("https://example.org/new").assertExists()
        compose.runOnIdle { assertEquals(0, confirms) }
    }

    @Test fun managerEditsMovePauseArchiveAndUndoWithoutRemoteControls() {
        var sources by mutableStateOf(listOf(Subscription("local", "Fixture local", candidate.url, isLocal = true),
            Subscription("remote", "Fixture remote", "https://remote.example/feed")))
        var folders by mutableStateOf(emptyList<DirectFolder>())
        val changes = mutableListOf<Subscription>()
        compose.setContent { FluxaTheme { LocalSubscriptionManager(sources, folders,
            LocalSubscriptionActions(createFolder = { name ->
                folders = listOf(DirectFolder("folder", name.trim())); "folder"
            }, organize = { source ->
                changes += source
                sources = sources.map { if (it.id == source.id) source.copy(title = source.alias?.takeIf { it.isNotBlank() } ?: "Fixture local") else it }
            }), {}) } }
        compose.onNodeWithContentDescription("编辑 Fixture remote").assertDoesNotExist()
        compose.onNodeWithText("新文件夹名称").performScrollTo().performTextInput("Reading")
        closeSoftKeyboard()
        clickManagerAction("创建文件夹")
        compose.waitUntil(10_000) { folders.singleOrNull()?.name == "Reading" }
        clickManagerAction("编辑 Fixture local")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("显示别名（留空恢复原标题）").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText("显示别名（留空恢复原标题）").performScrollTo().performTextInput("Alias")
        closeSoftKeyboard()
        clickManagerAction("移至 Reading")
        clickManagerAction("保存订阅设置")
        compose.runOnIdle { assertEquals("Alias", changes.last().alias); assertEquals("folder", changes.last().folderId) }
        clickManagerAction("暂停 Alias")
        compose.runOnIdle { assertTrue(changes.last().paused) }
        clickManagerAction("恢复 Alias")
        compose.runOnIdle { assertFalse(changes.last().paused) }
        clickManagerAction("取消订阅 Alias")
        compose.runOnIdle { assertTrue(changes.last().archived) }
        clickManagerAction("撤销取消订阅")
        compose.runOnIdle {
            assertFalse(changes.last().archived)
            assertEquals("local", changes.last().id)
            assertEquals("folder", changes.last().folderId)
            assertEquals("Alias", changes.last().alias)
        }
    }

    /** The sheet moves with the IME; Compose idling alone does not settle its screen position. */
    private fun clickManagerAction(label: String) {
        val matcher = hasContentDescription(label) and isEnabled()
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
        val node = compose.onNode(matcher).performScrollTo()
        var previous: androidx.compose.ui.geometry.Rect? = null
        compose.waitUntil(10_000) {
            val visible = try { node.assertIsDisplayed(); true } catch (_: AssertionError) { false }
            val target = node.fetchSemanticsNode()
            val bounds = androidx.compose.ui.geometry.Rect(target.positionOnScreen, target.boundsInWindow.size)
            val settled = visible && bounds.width > 0 && bounds.height > 0 && bounds == previous
            previous = bounds.takeIf { visible }
            settled
        }
        node.assertIsEnabled().performTouchInput { click() }
    }
}
