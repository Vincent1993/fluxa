package com.fluxa.app

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxa.app.data.local.ReaderPreferences
import com.fluxa.app.data.repository.ArticleRepository
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.article.ReadingSessionViewModel
import com.fluxa.app.ui.feedlist.*
import com.fluxa.app.ui.theme.FluxaTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/** Production route + real ViewModel debounce/search projection, owned synthetic rows only. */
class RapidQueryStage4Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun rapidImeEditsStayExactWhileRealProjectionDebouncesAndSavedScopeClears() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val owner = "fluxa-stage4-owned-api36"
        check(InstrumentationRegistry.getArguments().getString("stage4Owner") == owner)
        check(android.os.Build.VERSION.SDK_INT == 36)
        check(File(context.filesDir, "$owner.marker").readText().trim() == owner)
        val name = "owned-query-${java.util.UUID.randomUUID()}"
        val wrapped = object : ContextWrapper(context) {
            override fun getSharedPreferences(n: String, mode: Int) = super.getSharedPreferences(if (n == "reader_private") name else n, mode)
        }
        val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = ViewModelStore()
        val repository = object : ArticleRepository {
            val rows = MutableStateFlow(listOf(
                Article("direct-rss:query-one", "Correction headline", "Owned", Instant.EPOCH, false, false, "<p>Other</p>", "direct-rss:query"),
                Article("direct-rss:query-two", "Reddit owned", "Owned", Instant.EPOCH, false, false, "<p>Correction in body</p>", "direct-rss:query")))
            override fun getPagedArticles() = rows
            override suspend fun refresh() = Unit
            override suspend fun loadMore() = Unit
            override suspend fun markRead(id: String) = Unit
            override suspend fun toggleStar(id: String) = Unit
        }
        val saved = SavedStateHandle()
        lateinit var vm: FeedListViewModel
        lateinit var reading: ReadingSessionViewModel
        try {
            compose.activityRule.scenario.awaitStableHost()
            compose.runOnIdle {
                vm = FeedListViewModel(repository, saved)
                reading = ReadingSessionViewModel(ReaderPreferences(wrapped, io))
                store.put("feed", vm); store.put("reader", reading)
            }
            compose.setContent { FluxaTheme { FeedListRoute({}, {}, viewModel = vm, reading = reading) } }
            compose.onNodeWithContentDescription("搜索", useUnmergedTree = true).performClick()
            val field = compose.onNode(hasSetTextAction())
            // One batch of IME edit commands, no artificial human-rate sleeps or callback-only test.
            field.performTextInput("Correction")
            field.assertTextContains("Correction")
            compose.runOnIdle { assertEquals("Correction", vm.editorQuery.value); assertEquals("Correction", saved.get<String>("query")) }
            compose.waitUntil(5_000) { !vm.state.value.searchPending && vm.state.value.articles.size == 2 && vm.state.value.query == "Correction" }
            field.performTextReplacement("Cor")
            field.performTextInput("rection")
            field.assertTextContains("Correction")
            compose.runOnIdle { vm.applyScope(SearchScope(query = "Correction", source = "direct-rss:query")) }
            field.assertTextContains("Correction")
            compose.onNodeWithContentDescription("清空搜索").performClick()
            field.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString("")))
            compose.runOnIdle {
                assertEquals("", vm.editorQuery.value); assertEquals("", saved.get<String>("query"))
                assertEquals("direct-rss:query", saved.get<String>("source"))
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            io.cancel(); context.deleteSharedPreferences(name)
        }
    }
}
