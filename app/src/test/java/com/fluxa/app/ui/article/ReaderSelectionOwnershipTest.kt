package com.fluxa.app.ui.article

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.directrss.*
import com.fluxa.app.data.local.ReaderPreferences
import com.fluxa.app.data.repository.ArticleRepository
import com.fluxa.app.data.repository.CompositeArticleRepository
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.components.UiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ReaderSelectionOwnershipTest {
    /** Allows real Room/IO work to report to the controlled main dispatcher; no virtual deadline inflation. */
    private suspend fun TestScope.until(predicate: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            runCurrent()
            if (predicate()) return
            check(System.nanoTime() < end) { "Owned reader state did not arrive within five seconds" }
            withContext(Dispatchers.IO) { Thread.sleep(5) }
        }
    }
    private class Fixture(scope: CoroutineScope, readPage: (String) -> ByteArray = { error("No network in fixture") }) : AutoCloseable {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val name = "reader-selection-unit-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(key: String, mode: Int) = super.getSharedPreferences(name, mode)
        }
        val reader = ReaderPreferences(context, scope)
        val db = Room.inMemoryDatabaseBuilder(context, DirectRssDatabase::class.java).allowMainThreadQueries().build()
        val local = DirectRssRepository(db, DirectRssClient(readFeed = readPage))
        val emptyRemote = object : ArticleRepository {
            override fun getPagedArticles() = flowOf(emptyList<Article>())
            override suspend fun refresh() = error("No refresh")
            override suspend fun loadMore() = error("No pagination")
            override suspend fun markRead(id: String) = error("No remote mutation")
            override suspend fun toggleStar(id: String) = error("No remote mutation")
        }
        val repository = CompositeArticleRepository.forTest(emptyRemote, local) { false }
        val a = DirectArticle("direct-rss:owned-a", "a", "direct-rss:source-a", "A", 3,
            "<p>RSS A</p>", "https://example.org/a", isStarred = true)
        val b = DirectArticle("direct-rss:owned-b", "b", "direct-rss:source-b", "B", 2,
            "<p>RSS B</p>", "https://example.org/b")
        val c = b.copy(id = "direct-rss:owned-c", guid = "c", title = "C", published = 1)
        val store = ViewModelStore()
        suspend fun seed() {
            listOf(a.source, b.source).forEach { db.dao().putSubscription(DirectSubscription(it, it, "https://example.org/rss", false, paused = true)) }
            listOf(a, b, c).forEach { db.dao().putArticle(it) }
        }
        fun model(saved: SavedStateHandle, repo: ArticleRepository = repository, key: String = "reader") =
            ArticleViewModel(saved, repo, reader, local).also { store.put(key, it) }
        override fun close() { store.clear(); db.close(); base.deleteSharedPreferences(name) }
    }
    private fun ArticleViewModel.selected() = (state.value.article as? UiState.Success<Article>)?.data

    @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    @Test fun uncommittedReboundHostCannotWriteOutgoingGeometryIntoIncomingPosition() = runTest {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val name = "reader-uncommitted-${UUID.randomUUID()}"
        val owned = object : ContextWrapper(activity.get()) {
            override fun getSharedPreferences(key: String, mode: Int) = super.getSharedPreferences(name, mode)
        }
        val reader = ReaderPreferences(owned, backgroundScope)
        val container = android.widget.FrameLayout(activity.get())
        activity.get().setContentView(container)
        val web = ReaderWebView(activity.get())
        val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        try {
            runCurrent()
            reader.freeze(listOf("a", "b"), "a")
            val aPosition = com.fluxa.app.data.local.ReadingPosition(.5f, 500, 1000)
            val bPosition = com.fluxa.app.data.local.ReadingPosition(.3f, 900, 3000)
            reader.savePosition("a", aPosition); reader.savePosition("b", bPosition)
            container.addView(web)
            val a = ReaderPage("a", reader)
            val b = ReaderPage("b", reader)
            web.render(a, "<p>Outgoing A</p>", 100)
            looper.idle()
            web.render(b, "<p>Incoming B</p>", 100)
            a.release() // Late disposal must not remove B's ownership.
            looper.idleFor(java.time.Duration.ofSeconds(6))
            // Deliberately no Chromium visual callback in this scheduling-only fixture.
            assertEquals(0L, web.committedDocument)
            assertEquals(1f, web.alpha, 0f)
            assertTrue(web.documentCovered)
            val cover = web.foreground
            assertNotNull(cover)
            assertTrue(cover is android.graphics.drawable.ColorDrawable)
            assertEquals(255, android.graphics.Color.alpha((cover as android.graphics.drawable.ColorDrawable).color))
            assertEquals(aPosition, reader.position("a"))
            assertEquals(bPosition, reader.position("b"))
            assertTrue(web.owns(b))
            assertEquals(listOf("a", "b"), reader.session.value.ids)
        } finally {
            web.disposeReader(); container.removeAllViews()
            activity.get().deleteSharedPreferences(name)
            activity.pause().stop().destroy()
        }
    }

    @Test fun synchronousSelectionRejectsMissingAndNonQueueIdsAndLateActionsStayWithCapturedArticle() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fixture(backgroundScope)
        try {
            f.seed(); until { f.reader.ready.value }
            f.reader.freeze(listOf(f.a.id, f.b.id, "missing"), f.a.id)
            val later = CompletableDeferred<Unit>()
            val star = CompletableDeferred<Unit>()
            val read = CompletableDeferred<Unit>()
            val captured = mutableListOf<String>()
            val delayed = object : ArticleRepository by f.repository {
                override suspend fun markRead(id: String) {
                    captured += "read:$id"
                    if (id == f.a.id) read.await()
                    f.repository.markRead(id)
                }
                override suspend fun setReadLater(id: String, value: Boolean) {
                    captured += "later:$id:$value"; later.await(); f.repository.setReadLater(id, value)
                }
                override suspend fun toggleStar(id: String) {
                    captured += "star:$id"; star.await(); error("Old article operation failed")
                }
            }
            val saved = SavedStateHandle(mapOf("id" to f.a.id))
            val vm = f.model(saved, delayed)
            until { vm.selected()?.id == f.a.id }
            var retired = 0
            assertFalse(vm.selectAdjacent(f.c.id) { retired++; true })
            assertEquals(0, retired)
            val laterJob = vm.toggleReadLater()
            val starJob = vm.toggleStar()
            // Dispatch is intentionally not advanced until after selection.
            assertTrue(vm.selectAdjacent(f.b.id) { retired++; true })
            assertEquals(1, retired)
            assertEquals(f.b.id, vm.selected()!!.id)
            assertNull(vm.state.value.fullText); assertNull(vm.state.value.error)
            assertNull(vm.state.value.readLaterUndo); assertTrue(vm.state.value.notes.isEmpty())
            assertEquals(f.b.id, saved.get<String>(ArticleViewModel.SELECTED_ID))
            assertFalse(vm.selectAdjacent("missing") { retired++; true })
            assertEquals(1, retired)
            runCurrent()
            read.complete(Unit); later.complete(Unit); star.complete(Unit)
            until { laterJob.isCompleted && starJob.isCompleted && vm.selected()?.isRead == true }
            assertNull(vm.state.value.error); assertNull(vm.state.value.readLaterUndo)
            assertEquals(listOf("later:${f.a.id}:true"), captured.filter { it.startsWith("later:") })
            assertEquals(listOf("star:${f.a.id}"), captured.filter { it.startsWith("star:") })
            assertFalse(captured.any { it.endsWith(":missing") })
            assertTrue(f.db.dao().article(f.a.id)!!.readLater)
            assertFalse(f.db.dao().article(f.b.id)!!.readLater)
            assertTrue(f.db.dao().article(f.a.id)!!.isStarred)
            assertFalse(f.db.dao().article(f.b.id)!!.isStarred)
            assertFalse(f.db.dao().article(f.c.id)!!.isRead)
            assertEquals(listOf(f.a.id, f.b.id, "missing"), f.reader.session.value.ids)
            // Undo dispatched from A must still target A if its coroutine starts on B.
            assertTrue(vm.selectAdjacent(f.a.id))
            until { vm.selected()?.readLater == true }
            val remove = vm.toggleReadLater()
            until { remove.isCompleted && vm.selected()?.readLater == false && vm.state.value.readLaterUndo == false }
            val undo = vm.undoReadLater()
            assertTrue(vm.selectAdjacent(f.b.id))
            until { undo.isCompleted }
            assertTrue(f.db.dao().article(f.a.id)!!.readLater)
            assertFalse(f.db.dao().article(f.b.id)!!.readLater)
            assertNull(vm.state.value.readLaterUndo)
            // Typed SavedStateHandle snapshot simulates a new VM after process loss,
            // separately from the instrumentation test's real Activity recreation.
            val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            f.store.clear()
            val reopened = f.model(restored)
            until { reopened.selected()?.id == f.b.id }
            assertEquals(f.b.id, reopened.selected()!!.id)
            assertEquals(f.a.id, restored.get<String>("id"))
            // No valid cached selection means no markRead, even for the route's initial ID.
            val missingCalls = mutableListOf<String>()
            val missingRepo = object : ArticleRepository by f.repository {
                override suspend fun markRead(id: String) { missingCalls += id }
            }
            val missing = f.model(SavedStateHandle(mapOf("id" to "missing")), missingRepo, "missing")
            until { missing.state.value.article is UiState.Error }
            assertTrue(missingCalls.isEmpty())
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun nextUnreadRejectsAnObsoleteRequestAndRetirementRejectionDoesNotReplaceQueue() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fixture(backgroundScope)
        try {
            f.seed(); until { f.reader.ready.value }
            f.reader.freeze(listOf(f.a.id, f.b.id), f.a.id)
            val gate = CompletableDeferred<Unit>()
            val delayed = object : ArticleRepository by f.repository {
                override fun observeSubscriptions() = kotlinx.coroutines.flow.flow {
                    gate.await(); emit(emptyList<com.fluxa.app.domain.model.Subscription>())
                }
            }
            val vm = f.model(SavedStateHandle(mapOf("id" to f.a.id)), delayed)
            until { vm.selected()?.id == f.a.id && vm.selected()?.isRead == true }
            val pending = async { vm.nextUnread() }
            runCurrent()
            assertTrue(vm.selectAdjacent(f.b.id))
            gate.complete(Unit)
            until { pending.isCompleted && vm.selected()?.isRead == true }
            assertNull(pending.await())
            assertEquals(f.b.id, vm.selected()!!.id)
            assertEquals(listOf(f.a.id, f.b.id), f.reader.session.value.ids)
            assertFalse(f.db.dao().article(f.c.id)!!.isRead)
            val rejected = async { vm.nextUnread { false } }
            until { rejected.isCompleted }
            assertNull(rejected.await())
            assertEquals(listOf(f.a.id, f.b.id), f.reader.session.value.ids)
            assertFalse(f.db.dao().article(f.c.id)!!.isRead)
            val accepted = async { vm.nextUnread() }
            until { accepted.isCompleted && vm.selected()?.id == f.c.id && vm.selected()?.isRead == true }
            assertEquals(f.c.id, accepted.await())
            assertEquals(listOf(f.c.id), f.reader.session.value.ids)
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun outgoingLifecycleCannotDeactivateNewOwnerOrStrandNextUnreadFullBody() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fixture(backgroundScope)
        try {
            f.seed(); until { f.reader.ready.value }
            f.reader.freeze(listOf(f.a.id, f.b.id), f.a.id)
            f.local.setReadingMode(f.b.source, LocalReadingMode.FULL_TEXT)
            f.db.dao().putFullBody(DirectFullBody(f.b.id, "<p>FULL B</p>"))
            f.db.dao().putFullBody(DirectFullBody(f.c.id, "<p>FULL C</p>"))
            val saved = SavedStateHandle(mapOf("id" to f.a.id))
            val vm = f.model(saved)
            val outgoing = Any()
            vm.attachActiveOwner(outgoing, true)
            until { vm.selected()?.id == f.a.id && vm.selected()?.isRead == true }
            assertTrue(vm.selectAdjacent(f.b.id))
            until { vm.state.value.fullText == "<p>FULL B</p>" && vm.selected()?.isRead == true }
            vm.updateActiveOwner(outgoing, false)
            val incoming = Any()
            vm.attachActiveOwner(incoming, true)
            // Delayed old pause/disposal after the new route is already resumed.
            vm.updateActiveOwner(outgoing, false)
            vm.releaseActiveOwner(outgoing)
            val request = async { vm.nextUnread() }
            until { request.isCompleted && vm.state.value.fullText == "<p>FULL C</p>" && vm.selected()?.isRead == true }
            assertEquals(f.c.id, request.await())
            assertEquals(f.c.id, vm.selected()!!.id)
            assertEquals(f.a.id, saved.get<String>("id"))
            assertEquals(f.c.id, saved.get<String>(ArticleViewModel.SELECTED_ID))
            assertEquals(listOf(f.c.id), f.reader.session.value.ids)
            assertEquals(LocalReadingMode.FULL_TEXT, vm.state.value.mode)
            // A genuinely paused latest owner must defer FULL work until resume.
            vm.updateActiveOwner(incoming, false)
            val rss = vm.selectMode(f.c.source, LocalReadingMode.RSS)
            until { rss.isCompleted && vm.state.value.mode == LocalReadingMode.RSS }
            val full = vm.selectMode(f.c.source, LocalReadingMode.FULL_TEXT)
            until { full.isCompleted && vm.state.value.mode == LocalReadingMode.FULL_TEXT }
            assertNull(vm.state.value.fullText)
            vm.updateActiveOwner(incoming, true)
            // Back-to-back pause/resume must not strand an invalidated extractor,
            // even when the collector never observes the intermediate false.
            vm.updateActiveOwner(incoming, false)
            vm.updateActiveOwner(incoming, true)
            until { vm.state.value.fullText == "<p>FULL C</p>" }
            vm.releaseActiveOwner(incoming)
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun delayedExtractionAndNotesCannotCrossSelectionOrReturnVisitGeneration() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val f = Fixture(backgroundScope) {
            entered.countDown()
            try {
                check(release.await(5, TimeUnit.SECONDS))
                ("<html><head><title>Owned extraction</title></head><body><article>" +
                    (1..12).joinToString("") { "<p>Owned delayed paragraph $it with enough real words for the local extraction fixture.</p>" } +
                    "</article></body></html>").toByteArray()
            } finally { returned.countDown() }
        }
        try {
            f.seed(); until { f.reader.ready.value }
            f.reader.freeze(listOf(f.a.id, f.b.id), f.a.id)
            f.local.setReadingMode(f.a.source, LocalReadingMode.FULL_TEXT)
            f.local.setReadingMode(f.b.source, LocalReadingMode.FULL_TEXT)
            f.db.dao().putFullBody(DirectFullBody(f.b.id, "<p>FULL B</p>"))
            val noteA = DirectReaderNote("a-note", f.a.id, "RSS", "RSS A", stableHash("RSS A"), 0, 1, "A note")
            val noteB = DirectReaderNote("b-note", f.b.id, "FULL_TEXT", "FULL B", stableHash("FULL B"), 0, 1, "B note")
            f.local.saveNote(noteA); f.local.saveNote(noteB)
            val vm = f.model(SavedStateHandle(mapOf("id" to f.a.id)))
            vm.setActive(true)
            until { entered.count == 0L && vm.state.value.notes == listOf(noteA) }
            assertTrue(vm.selectAdjacent(f.b.id))
            // No Loading, old FULL or old notes between two cached documents.
            assertEquals(f.b.id, vm.selected()!!.id)
            assertNull(vm.state.value.fullText); assertTrue(vm.state.value.notes.isEmpty())
            until { vm.state.value.fullText == "<p>FULL B</p>" && vm.state.value.notes == listOf(noteB) }
            release.countDown()
            until { returned.count == 0L }
            runCurrent()
            assertEquals("<p>FULL B</p>", vm.state.value.fullText)
            assertNull(vm.state.value.extractionError)
            assertNull(f.db.dao().fullBody(f.a.id)) // Cancelled extraction never commits even to outgoing A.
            // An outgoing note update cannot publish into B's observed note list.
            f.local.saveNote(noteA.copy(note = "late A note"))
            runCurrent()
            assertEquals(listOf(noteB), vm.state.value.notes)
            try { vm.deleteNote(noteA.id, f.a.id); fail("Reject stale sheet ownership") }
            catch (_: IllegalArgumentException) { }
            assertNotNull(f.db.dao().note(noteA.id))
            // Return to A in RSS: the old generation must not fill it with delayed FULL.
            f.local.setReadingMode(f.a.source, LocalReadingMode.RSS)
            assertTrue(vm.selectAdjacent(f.a.id))
            until { vm.state.value.notes.singleOrNull()?.note == "late A note" }
            assertEquals(LocalReadingMode.RSS, vm.state.value.mode)
            assertNull(vm.state.value.fullText)
            assertEquals("<p>RSS A</p>", vm.selected()!!.contentHtml)
            assertEquals(listOf(f.a.id, f.b.id), f.reader.session.value.ids)
        } finally { release.countDown(); f.close(); Dispatchers.resetMain() }
    }
}
