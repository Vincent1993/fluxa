package com.fluxa.app.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.local.ReaderPreferences
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.article.NextUnreadSelection
import com.fluxa.app.ui.feedlist.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NextUnreadStage3Test {
    @Test fun explicitSnapshotSurvivesReadRefreshRaceBeforePreferencesReadyAndEmptyDoesNotReplaceQueue() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("reader_private", Context.MODE_PRIVATE).edit().clear().commit()
        val prefsScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val reader = ReaderPreferences(context, prefsScope)
            fun article(id: String, source: String = id, read: Boolean = false) = Article(id, id, source, Instant.EPOCH, read, false, "<p>Body</p>", source)
            val rows = mutableListOf(article("current"), article("a", "first"), article("b", "second"), article("archived"), article("blocked"), article("read", read = true))
            val config = TriageConfig(rules = listOf(TriageRule("block", null, RuleKind.BlockKeyword, "blocked")))
            val selection = NextUnreadSelection.capture(rows, "current", setOf("archived"), config)
            val frozen = async(start = CoroutineStart.UNDISPATCHED) { selection.freeze(reader) }
            assertFalse(reader.ready.value); assertFalse(frozen.isCompleted)
            // Simulates target being marked read and a refresh replacing the live available list.
            rows[1] = rows[1].copy(isRead = true); rows.clear(); rows += article("new-arrival")
            runCurrent()
            assertEquals("a", frozen.await()); assertEquals(listOf("a", "b"), reader.session.value.ids)
            assertEquals("b", reader.session.value.adjacent("a", 1))
            val empty = NextUnreadSelection.capture(emptyList(), "a", emptySet(), config)
            assertNull(empty.freeze(reader))
            assertEquals(listOf("a", "b"), reader.session.value.ids)
            // Only a second explicit invocation admits the later arrival.
            assertEquals("new-arrival", NextUnreadSelection.capture(rows, "a", emptySet(), config).freeze(reader))
            assertEquals(listOf("new-arrival"), reader.session.value.ids)
        } finally { prefsScope.cancel() }
    }
}
