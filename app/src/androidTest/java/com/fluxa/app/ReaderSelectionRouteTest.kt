package com.fluxa.app

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxa.app.data.directrss.*
import com.fluxa.app.data.local.ReaderPreferences
import com.fluxa.app.data.repository.ArticleRepository
import com.fluxa.app.data.repository.CompositeArticleRepository
import com.fluxa.app.domain.model.Article
import com.fluxa.app.navigation.Routes
import com.fluxa.app.ui.article.ArticleRoute
import com.fluxa.app.ui.article.ArticleViewModel
import com.fluxa.app.ui.article.ReaderWebView
import com.fluxa.app.ui.components.UiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Route + entry-owned VM + Composite/Room and typed prefs. No account, downloads or app DB.
 * Complements (does not replace) strict native paragraph/focus and real MainActivity checks. */
class ReaderSelectionRouteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun queueTurnsRetainRouteAndChromiumWhileSelectionModesNotesFlagsAndBackRemainOwned() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val owner = "fluxa-stage4-owned-api36"
        check(InstrumentationRegistry.getArguments().getString("stage4Owner") == owner)
        check(android.os.Build.VERSION.SDK_INT == 36)
        check(File(context.filesDir, "$owner.marker").readText().trim() == owner)
        val prefix = "owned-reader-selection-${UUID.randomUUID()}"
        val owned = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
        }
        val preferencesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reader = ReaderPreferences(owned, preferencesScope)
        val db = Room.inMemoryDatabaseBuilder(owned, DirectRssDatabase::class.java).build()
        val local = DirectRssRepository(db, DirectRssClient(readFeed = { error("Fixture must use cached bodies only") }))
        val remote = object : ArticleRepository {
            override fun getPagedArticles() = flowOf(emptyList<Article>())
            override suspend fun refresh() = error("No fixture refresh")
            override suspend fun loadMore() = error("No fixture pagination")
            override suspend fun markRead(id: String) = error("No remote mutation")
            override suspend fun toggleStar(id: String) = error("No remote mutation")
        }
        val repository = CompositeArticleRepository.forTest(remote, local) { false }
        val a = DirectArticle("direct-rss:owned-selection-a", "a", "direct-rss:owned-source-a", "Owned selection A", 3,
            "<p>Owned RSS paragraph A.</p>", "https://example.org/a", isStarred = true)
        val b = DirectArticle("direct-rss:owned-selection-b", "b", "direct-rss:owned-source-b", "Owned selection B", 2,
            "<p>Owned RSS paragraph B.</p>", "https://example.org/b", readLater = true)
        val c = b.copy(id = "direct-rss:owned-selection-c", guid = "c", title = "Owned selection C", published = 1,
            body = "<p>Owned RSS paragraph C.</p>", originalUrl = "https://example.org/c", readLater = false)
        fun note(row: DirectArticle, mode: String, quote: String) = DirectReaderNote("note-${row.guid}", row.id,
            mode, quote, stableHash(quote), 0, 1, "Owned note ${row.guid}")
        val noteA = note(a, "RSS", "Owned RSS paragraph A.")
        val noteB = note(b, "FULL_TEXT", "Owned FULL paragraph B.")
        lateinit var nav: NavHostController
        lateinit var model: ArticleViewModel
        var modelReady = false
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                ArticleViewModel(extras.createSavedStateHandle(), repository, reader, local) as T
        }
        fun install(activity: ComponentActivity) {
            activity.setContent {
                MaterialTheme {
                    nav = rememberNavController()
                    NavHost(nav, startDestination = Routes.FeedList) {
                        composable(Routes.FeedList) {
                            // A retained list scope/anchor sentinel, not a second reader owner.
                            val anchor = rememberSaveable { "Owned source A / list anchor 7" }
                            Text(anchor)
                        }
                        composable(Routes.Article, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                            val current = remember(entry) { ViewModelProvider(entry, factory)[ArticleViewModel::class.java] }
                            SideEffect { model = current; modelReady = true }
                            ArticleRoute(onBack = { nav.popBackStack() }, viewModel = current)
                        }
                    }
                }
            }
        }
        fun selected() = if (modelReady) (model.state.value.article as? UiState.Success<Article>)?.data else null
        var lastReadyHost: ReaderWebView? = null
        var lastReadyDocument = 0L
        fun awaitArticle(row: DirectArticle, full: String? = null) {
            try {
                compose.waitUntil(5_000) { selected()?.id == row.id && model.state.value.fullText == full }
                compose.waitUntil(5_000) {
                    var ready = false
                    compose.activityRule.scenario.onActivity { activity ->
                        ready = views(activity.window.decorView).filterIsInstance<ReaderWebView>().singleOrNull()?.let {
                            it.title == row.title && !it.documentCovered && it.committedDocument > 0 &&
                                (it !== lastReadyHost || it.committedDocument > lastReadyDocument) &&
                                it.alpha == 1f && it.progress == 100
                        } == true
                    }
                    ready
                }
                compose.activityRule.scenario.onActivity { activity ->
                    lastReadyHost = views(activity.window.decorView).filterIsInstance<ReaderWebView>().single()
                    lastReadyDocument = checkNotNull(lastReadyHost).committedDocument
                }
            } catch (failure: Throwable) {
                // Only this isolated, typed fixture's IDs/hashes; never raw prefs or body text.
                runCatching {
                    compose.activityRule.scenario.onActivity { activity ->
                        val pages = views(activity.window.decorView).filterIsInstance<ReaderWebView>().map {
                            "host=${System.identityHashCode(it)} document=${it.committedDocument} " +
                                "title=${it.title} progress=${it.progress} alpha=${it.alpha} covered=${it.documentCovered} " +
                                "attached=${it.isAttachedToWindow} shown=${it.isShown} y=${it.scrollY} range=${it.nativeMaxScrollOffset}"
                        }.toList()
                        android.util.Log.e("ReaderSelectionOwned", "await failed expected=${row.id} " +
                            "expectedFullHash=${full?.let(::stableHash)} activityState=${activity.lifecycle.currentState} " +
                            "entryState=${nav.currentBackStackEntry?.lifecycle?.currentState} " +
                            "argument=${nav.currentBackStackEntry?.arguments?.getString("id")} " +
                            "${if (modelReady) model.diagnosticState() else "model not attached"} pages=$pages")
                    }
                }.onFailure { failure.addSuppressed(it) }
                throw failure
            }
        }
        try {
            runBlocking {
                reader.ready.first { it }
                for (source in listOf(a.source, b.source)) db.dao().putSubscription(DirectSubscription(source, source,
                    "https://example.org/feed", false, paused = true))
                listOf(a, b, c).forEach { db.dao().putArticle(it) }
                db.dao().putMode(DirectReadingMode(b.source, "FULL_TEXT"))
                db.dao().putFullBody(DirectFullBody(b.id, "<p>Owned FULL paragraph B.</p>"))
                db.dao().putFullBody(DirectFullBody(c.id, "<p>Owned FULL paragraph C.</p>"))
                local.saveNote(noteA); local.saveNote(noteB)
                check(reader.freeze(listOf(a.id, b.id), a.id))
            }
            compose.activityRule.scenario.awaitStableHost()
            compose.activityRule.scenario.onActivity(::install)
            compose.runOnIdle { nav.navigate(Routes.article(a.id)) }
            awaitArticle(a)
            compose.waitUntil(5_000) { model.state.value.notes == listOf(noteA) }
            lateinit var host: ReaderWebView
            lateinit var entry: androidx.navigation.NavBackStackEntry
            lateinit var originalModel: ArticleViewModel
            var document = 0L
            compose.runOnIdle {
                entry = nav.currentBackStackEntry!!; originalModel = model
                host = views(compose.activity.window.decorView).filterIsInstance<ReaderWebView>().single()
                document = host.committedDocument
                assertFalse(model.selectAdjacent(c.id)) // Cached, but not in this frozen queue.
                assertFalse(model.selectAdjacent("missing"))
                assertEquals(a.id, selected()!!.id)
            }
            compose.onNodeWithContentDescription("下一篇").performClick()
            awaitArticle(b, "<p>Owned FULL paragraph B.</p>")
            compose.waitUntil(5_000) { model.state.value.notes == listOf(noteB) }
            compose.runOnIdle {
                assertSame(entry, nav.currentBackStackEntry); assertSame(originalModel, model)
                assertSame(host, views(compose.activity.window.decorView).filterIsInstance<ReaderWebView>().single())
                assertTrue(host.committedDocument > document)
                assertEquals(a.id, entry.arguments!!.getString("id")) // Original route, selected ID is separate.
                assertEquals(LocalReadingMode.FULL_TEXT, model.state.value.mode)
                assertEquals(b.originalUrl, selected()!!.originalUrl)
                assertNull(model.state.value.readLaterUndo)
            }
            compose.onNodeWithContentDescription("正文模式").performClick()
            compose.onNodeWithContentDescription("RSS 正文").performClick()
            awaitArticle(b)
            compose.runOnIdle { assertEquals(LocalReadingMode.RSS, model.state.value.mode) }
            compose.onNodeWithContentDescription("正文模式").performClick()
            compose.onNodeWithContentDescription("提取全文").performClick()
            awaitArticle(b, "<p>Owned FULL paragraph B.</p>")
            compose.runOnIdle {
                assertSame(host, views(compose.activity.window.decorView).filterIsInstance<ReaderWebView>().single())
                assertEquals(listOf(noteB), model.state.value.notes)
            }
            runBlocking {
                assertNull(db.dao().mode(a.source))
                assertEquals("FULL_TEXT", db.dao().mode(b.source)!!.mode)
            }
            compose.onNodeWithContentDescription("更多阅读工具").performClick()
            compose.onNodeWithContentDescription("移出稍后读").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f)).performClick()
            compose.waitUntil(5_000) { selected()?.readLater == false && model.state.value.readLaterUndo == false }
            compose.onNodeWithContentDescription("撤销稍后读更改").performClick()
            compose.waitUntil(5_000) { selected()?.readLater == true && model.state.value.readLaterUndo == null }
            compose.onNodeWithContentDescription("关闭阅读工具").performClick()
            compose.onNodeWithContentDescription("收藏").performClick()
            compose.waitUntil(5_000) { selected()?.isStarred == true }
            compose.onNodeWithContentDescription("上一篇").performClick()
            awaitArticle(a)
            compose.waitUntil(5_000) { model.state.value.notes == listOf(noteA) }
            compose.runOnIdle {
                assertSame(host, views(compose.activity.window.decorView).filterIsInstance<ReaderWebView>().single())
                assertEquals(LocalReadingMode.RSS, model.state.value.mode)
                assertEquals(listOf(a.id, b.id), reader.session.value.ids)
            }
            // Actual Activity recreation restores selected B, not the original route argument A.
            compose.onNodeWithContentDescription("下一篇").performClick()
            awaitArticle(b, "<p>Owned FULL paragraph B.</p>")
            modelReady = false
            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity(::install)
            awaitArticle(b, "<p>Owned FULL paragraph B.</p>")
            compose.runOnIdle {
                assertNotSame(host, views(compose.activity.window.decorView).filterIsInstance<ReaderWebView>().single())
                assertEquals(Routes.FeedList, nav.previousBackStackEntry!!.destination.route)
            }
            // Marking A/B read must not shrink the old queue. Explicit next-unread admits C.
            compose.onNodeWithContentDescription("跨来源下一未读").performClick()
            awaitArticle(c, "<p>Owned FULL paragraph C.</p>")
            compose.runOnIdle { assertEquals(listOf(c.id), reader.session.value.ids) }
            compose.onNodeWithContentDescription("返回文章列表").performClick()
            compose.onNodeWithText("Owned source A / list anchor 7").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(Routes.FeedList, nav.currentBackStackEntry!!.destination.route)
                assertTrue(views(compose.activity.window.decorView).filterIsInstance<ReaderWebView>().none())
            }
            runBlocking {
                val rows = db.dao().articles().associateBy { it.id }
                assertTrue(rows.values.all { it.isRead })
                assertEquals(a.copy(isRead = true), rows[a.id])
                assertEquals(b.copy(isRead = true, isStarred = true), rows[b.id])
                assertEquals(c.copy(isRead = true), rows[c.id])
                assertEquals(setOf(noteA, noteB), db.dao().allNotes().toSet())
            }
        } finally {
            compose.activityRule.scenario.onActivity { it.setContent { } }
            runBlocking { preferencesScope.coroutineContext[Job]?.cancelAndJoin() }
            db.close()
            context.deleteSharedPreferences("$prefix-reader_private")
        }
    }
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
}
