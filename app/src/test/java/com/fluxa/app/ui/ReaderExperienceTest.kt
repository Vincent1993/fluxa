package com.fluxa.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelStore
import com.fluxa.app.ui.article.ReadingSessionViewModel
import com.fluxa.app.ui.feedlist.FeedListState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.fluxa.app.data.local.*
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.article.readerHtml
import com.fluxa.app.ui.article.NativeAnchors
import com.fluxa.app.ui.article.NativeBlock
import com.fluxa.app.ui.article.NativeDocumentSnapshot
import org.json.JSONObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReaderExperienceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Before fun cleanReaderOnly() {
        context.getSharedPreferences("reader_private", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun recreatedStoreRetainsGlobalSettingsIndependentPositionsAndFrozenOrder() = runTest {
        val first = ReaderPreferences(context, backgroundScope)
        runCurrent()
        first.update(ReaderSettings(24, 2.1f, ReadingTheme.Sepia))
        val filtered = mutableListOf("unread-c", "unread-a", "unread-b")
        assertTrue(first.freeze(filtered, "unread-a"))
        filtered.remove("unread-a") // opening marks it read in the live query
        first.savePosition("unread-a", ReadingPosition(.42f, 420, 1000))
        first.savePosition("unread-b", ReadingPosition(.8f, 1600, 2000))
        advanceTimeBy(650); runCurrent()

        val reopened = ReaderPreferences(context, backgroundScope)
        runCurrent()
        assertTrue(reopened.ready.value)
        assertEquals(ReaderSettings(24, 2.1f, ReadingTheme.Sepia), reopened.settings.value)
        assertEquals(420, reopened.position("unread-a").offset(1000))
        assertEquals(840, reopened.position("unread-a").offset(2000))
        assertEquals(1600, reopened.position("unread-b").offset(2000))
        assertEquals(ReadingPosition(), reopened.position("never-opened"))
        assertEquals("unread-c", reopened.session.value.adjacent("unread-a", -1))
        assertEquals("unread-b", reopened.session.value.adjacent("unread-a", 1))
        assertNull(reopened.session.value.adjacent("unread-c", -1))
        assertNull(reopened.session.value.adjacent("unread-b", 1))
        assertNull(reopened.session.value.adjacent("absent", 1))
        assertFalse(reopened.freeze(listOf("other"), "absent"))
        assertEquals(listOf("unread-c", "unread-a", "unread-b"), reopened.session.value.ids)
    }

    @Test fun earlyTapWaitsAndFreezesBeforeTheLiveListChanges() = runTest {
        val reader = ReaderPreferences(context, backgroundScope)
        val live = mutableListOf("a", "b", "c")
        var opened = false
        val tap = launch(start = CoroutineStart.UNDISPATCHED) {
            opened = reader.awaitAndFreeze(live, "b")
        }
        assertFalse(opened)
        live.clear()
        runCurrent()
        tap.join()
        assertTrue(opened)
        assertEquals(listOf("a", "b", "c"), reader.session.value.ids)
    }

    @Test fun longReaderVisitKeepsTheReturnListProjectionFreshUntilEntryIsCleared() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val source = MutableStateFlow(FeedListState(query = "before"))
            val projected = source.stateIn(backgroundScope, SharingStarted.WhileSubscribed(5000), FeedListState())
            val session = ReadingSessionViewModel(ReaderPreferences(context, backgroundScope))
            owner.put("reading", session)
            session.retainList(projected)
            runCurrent()
            advanceTimeBy(10_000); runCurrent()
            source.value = FeedListState(query = "after unread removals")
            runCurrent()
            assertEquals("after unread removals", projected.value.query)
            assertEquals(1, source.subscriptionCount.value)
            owner.clear()
            advanceTimeBy(5001); runCurrent()
            assertEquals(0, source.subscriptionCount.value)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun returnAnchorUsesOriginalNeighborsNotTheNewNumericIndex() {
        val session = ReadingSession(listOf("a", "b", "c", "d", "e", "f"))
        assertEquals("d", session.nearestSurvivor("c", setOf("d", "e", "f")))
        assertEquals("b", session.nearestSurvivor("c", setOf("a", "b", "f")))
        assertEquals("c", session.nearestSurvivor("c", setOf("c", "f")))
        assertNull(session.nearestSurvivor("c", setOf("new")))
        assertNull(session.nearestSurvivor("unknown", setOf("a")))
    }

    @Test fun corruptPersistedValuesAreBoundedAndMalformedFileFallsBack() = runTest {
        val prefs = context.getSharedPreferences("reader_private", Context.MODE_PRIVATE)
        prefs.edit().putString("state", """{"font":999,"spacing":-20,"theme":"alien","positions":{"a":{"fraction":4,"pixels":-9,"range":-1}},"session":["a","a",""]}""").commit()
        val bounded = ReaderPreferences(context, backgroundScope)
        runCurrent()
        assertEquals(ReaderSettings(30, 1.3f, ReadingTheme.System), bounded.settings.value)
        assertEquals(ReadingPosition(1f, 0, 0), bounded.position("a"))
        assertEquals(listOf("a"), bounded.session.value.ids)
        assertEquals(0f, ReadingPosition(Float.NaN).bounded().fraction, 0f)
        assertEquals(1.8f, ReaderSettings(lineSpacing = Float.POSITIVE_INFINITY).bounded().lineSpacing, 0f)
        prefs.edit().putString("state", "not json").commit()
        val fallback = ReaderPreferences(context, backgroundScope)
        runCurrent()
        assertTrue(fallback.ready.value)
        assertEquals(ReaderSettings(), fallback.settings.value)
        assertEquals(ReadingPosition(), fallback.position("a"))
    }

    @Test fun delayedCallbacksCannotRestoreOrWriteAfterNavigationOrReflow() {
        val guard = PageRestoreGuard()
        val a = guard.begin("a")
        assertFalse(guard.canWrite("a", a))
        val b = guard.begin("b")
        assertFalse(guard.rendered("a", a))
        assertFalse(guard.canWrite("a", a))
        assertFalse(guard.canWrite("b", b))
        assertTrue(guard.rendered("b", b))
        assertTrue(guard.canWrite("b", b))
        val reflow = guard.begin("b")
        assertFalse(guard.rendered("b", b))
        assertFalse(guard.canWrite("b", reflow))
        assertTrue(guard.rendered("b", reflow))
        assertTrue(guard.canWrite("b", reflow))
    }

    @Test fun contentAnchorJsonRoundtripAndLegacyPositions() = runTest {
        val legacy = """{"positions":{"old":{"fraction":0.5,"pixels":500,"range":1000}}}"""
        context.getSharedPreferences("reader_private", Context.MODE_PRIVATE).edit().putString("state", legacy).commit()
        val first = ReaderPreferences(context, backgroundScope)
        runCurrent()
        assertEquals(ReadingPosition(.5f, 500, 1000), first.position("old"))
        val anchor = ContentAnchor(NativeAnchors.digest("private prose"), "text", "/0/4", 2, -.25f, 600, 400)
        val position = ReadingPosition(.5f, 500, 1000, anchor)
        first.savePosition("new", position)
        advanceTimeBy(650); runCurrent()
        val stored = context.getSharedPreferences("reader_private", Context.MODE_PRIVATE).getString("state", "")!!
        assertFalse(stored.contains("private prose"))
        val reopened = ReaderPreferences(context, backgroundScope)
        runCurrent()
        assertEquals(position, reopened.position("new"))
        assertEquals(first.position("old"), reopened.position("old"))
    }

    @Test fun malformedAndHugeAnchorsAreDroppedWithoutLosingLegacyFallback() {
        val good = ContentAnchor(NativeAnchors.digest("text"), "text", "/0", 0, .5f, 100, 200)
        assertEquals(good, ContentAnchor.fromJson(good.json()))
        for (bad in listOf(good.copy(key = "x".repeat(10_000)), good.copy(kind = "x".repeat(81)),
            good.copy(path = "/".repeat(513)), good.copy(occurrence = -1), good.copy(occurrence = Int.MAX_VALUE),
            good.copy(relative = Float.NaN), good.copy(relative = Float.POSITIVE_INFINITY),
            good.copy(relative = -1001f), good.copy(height = 0), good.copy(top = -1))) {
            assertNull(bad.bounded())
            assertEquals(ReadingPosition(.5f, 50, 100), ReadingPosition(.5f, 50, 100, bad).bounded())
        }
        assertNull(ContentAnchor.fromJson(JSONObject("""{"key":"bad"}""")))
        assertNull(ContentAnchor.fromJson(null))
    }

    @Test fun repeatedParagraphOccurrenceSurvivesReflowAndKeepsExactUnchangedPixels() {
        val key = NativeAnchors.digest("identical paragraph")
        val old = listOf(NativeBlock(key, "text", "/0", 0, 100, 200), NativeBlock(key, "text", "/1", 1, 400, 200))
        val anchor = NativeAnchors.capture(old, 451)!!
        assertEquals(1, anchor.occurrence)
        val position = ReadingPosition(.451f, 451, 1000, anchor)
        assertEquals(451, NativeAnchors.resolve(position, old, 1000))
        val reflow = old.map { it.copy(top = it.top * 2, height = it.height * 3) }
        assertEquals(953, NativeAnchors.resolve(position, reflow, 3000))
        // Matching content wins even when the overall range happens to be unchanged.
        assertEquals(553, NativeAnchors.resolve(position, old.map { it.copy(height = 600) }, 1000))
    }

    @Test fun overlappingTableCellsKeepSelectedInteriorThroughSequentialAutomaticReflows() {
        val place = NativeBlock(NativeAnchors.digest("Place"), "td", "/0/0", 0, 100, 200)
        val observation = NativeBlock(NativeAnchors.digest("Observation"), "td", "/0/1", 0, 100, 400)
        val original = NativeAnchors.capture(listOf(place, observation), 150)!!
        assertEquals(place.key, original.key)
        assertEquals(.25f, original.relative, 0f)

        val first = listOf(place.copy(top = 400, height = 600, path = "/1/0"),
            observation.copy(top = 450, height = 200))
        val firstY = NativeAnchors.resolve(ReadingPosition(.15f, 150, 1000, original), first, 2000)
        assertEquals(550, firstY)
        val automatic = NativeAnchors.capture(first, firstY, preferred = original)!!
        assertEquals(place.key, automatic.key)
        assertEquals(first[0].path, automatic.path)
        assertEquals(400, automatic.top)
        assertEquals(600, automatic.height)
        assertEquals(.25f, automatic.relative, 0f)
        // Preference retains identity, not stale coordinates or the old relative offset.
        assertEquals(.3f, NativeAnchors.capture(first, 580, preferred = original)!!.relative, 0f)

        val second = listOf(place.copy(top = 1000, height = 800),
            observation.copy(top = 1100, height = 240))
        val secondY = NativeAnchors.resolve(
            ReadingPosition(firstY / 2000f, firstY, 2000, automatic), second, 3000)
        assertEquals(1200, secondY)
        val recaptured = NativeAnchors.capture(second, secondY, preferred = automatic)!!
        assertEquals(place.key, recaptured.key)
        assertEquals(.25f, recaptured.relative, 0f)
        assertEquals(1000, recaptured.top)
        assertEquals(800, recaptured.height)

        // ReaderPage omits the preference after a gesture: the shorter adjacent cell can win.
        val fresh = NativeAnchors.capture(first, firstY)!!
        assertEquals(observation.key, fresh.key)
        assertEquals(1220, NativeAnchors.resolve(
            ReadingPosition(firstY / 2000f, firstY, 2000, fresh), second, 3000))
        for (missing in listOf(original.copy(key = NativeAnchors.digest("missing")),
            original.copy(kind = "table"), original.copy(occurrence = 1))) {
            assertEquals(fresh, NativeAnchors.capture(first, firstY, preferred = missing))
        }
        // Outside the preferred cell (including its exclusive bottom), keep normal gap fallback.
        for (outside in listOf(350, 1000, 1050)) {
            assertEquals(NativeAnchors.capture(first, outside),
                NativeAnchors.capture(first, outside, preferred = original))
        }
        assertNull(NativeAnchors.capture(emptyList(), firstY, preferred = original))
    }

    @Test fun nativeImageCodeQuoteTableAndSignedNearestGapAreIdentifiable() {
        val blocks = listOf("image", "pre", "blockquote", "table").mapIndexed { i, kind ->
            NativeBlock(NativeAnchors.digest(kind), kind, "/$i", 0, 100 + i * 300, 200)
        }
        blocks.forEach { block ->
            assertEquals(block.key, NativeAnchors.capture(blocks, block.top + 50)?.key)
        }
        val above = NativeAnchors.capture(blocks, 50)!!
        assertEquals(-.5f, above.relative, 0f)
        val position = ReadingPosition(.05f, 50, 1000, above)
        assertEquals(100, NativeAnchors.resolve(position, blocks.map { it.copy(top = it.top * 2, height = it.height * 2) }, 2500))
        assertNull(NativeAnchors.capture(emptyList(), 123))
        val below = NativeAnchors.capture(listOf(blocks.first()), 350)!!
        assertEquals(1.25f, below.relative, 0f)
    }

    @Test fun missingContentUsesPixelsOnlyForSameRangeOtherwiseRatio() {
        val anchor = ContentAnchor(NativeAnchors.digest("removed"), "text", "/0", 0, .5f, 100, 200)
        val position = ReadingPosition(.4f, 401, 1000, anchor)
        val other = listOf(NativeBlock(NativeAnchors.digest("new"), "text", "/0", 0, 100, 300))
        assertEquals(401, NativeAnchors.resolve(position, other, 1000))
        assertEquals(800, NativeAnchors.resolve(position, other, 2000))
        assertEquals(0, NativeAnchors.resolve(position, emptyList(), 0))
    }

    @Test fun productionSinkDeduplicatesContainersAndIgnoresOuterOrigin() {
        var completed = false
        val sink = NativeDocumentSnapshot { completed = true }
        sink.setDimens(9, 80, 0, 700, 400, 600)
        sink.setChildCount(1)
        val document = sink.asyncNewChild(0)
        document.setDimens(0, 0, 0, 0, 400, 2000)
        document.setChildCount(3)
        repeat(2) { index ->
            val parent = document.newChild(index)
            parent.setDimens(0, 100 + index * 300, 0, 0, 400, 200)
            parent.setText("Duplicate paragraph")
            parent.setChildCount(1)
            val leaf = parent.newChild(0)
            leaf.setDimens(0, 0, 0, 0, 400, 200)
            leaf.setText("Duplicate paragraph")
        }
        val image = document.newChild(2)
        image.setClassName("android.widget.ImageView")
        image.setContentDescription("Illustration")
        image.setDimens(0, 700, 0, 0, 400, 200)
        assertFalse(completed)
        document.asyncCommit()
        assertTrue(completed)
        val blocks = sink.blocks()
        assertEquals(3, blocks.size)
        assertEquals(listOf(100, 400, 700), blocks.map { it.top })
        assertEquals(listOf(0, 1, 0), blocks.map { it.occurrence })
        assertEquals("image", NativeAnchors.capture(blocks, 750)?.kind)
    }

    @Test fun unlabeledImageAndEmptyContentBlockUseStableNativePaths() {
        val sink = NativeDocumentSnapshot { }
        sink.setChildCount(1)
        val document = sink.asyncNewChild(0)
        document.setChildCount(2)
        val image = document.newChild(0)
        image.setClassName("android.widget.ImageView")
        image.setDimens(0, 100, 0, 0, 300, 200)
        val table = document.newChild(1)
        table.setHtmlInfo(checkNotNull(checkNotNull(table.newHtmlInfoBuilder("table")).build()))
        table.setDimens(0, 400, 0, 0, 300, 200)
        document.asyncCommit()
        val blocks = sink.blocks()
        assertEquals(listOf("image", "table"), blocks.map { it.kind })
        assertNotEquals(blocks[0].key, blocks[1].key)
        assertEquals("image", NativeAnchors.capture(blocks, 150)?.kind)
        assertEquals("table", NativeAnchors.capture(blocks, 450)?.kind)
        assertEquals(blocks, sink.blocks())
    }

    @Test fun canceledNativeSnapshotNeverDeliversCompletion() {
        var callbacks = 0
        val sink = NativeDocumentSnapshot { callbacks++ }
        sink.setChildCount(1)
        val document = sink.asyncNewChild(0)
        sink.cancel()
        document.asyncCommit()
        assertEquals(0, callbacks)
    }

    @Test fun readerIncludesFullEscapedMetadataAndRestrictivePolicy() {
        val title = "中文 & English <title> " + "long ".repeat(100)
        val article = Article("a", title, "<source>", Instant.EPOCH, false, false, "<p>正文</p>")
        val html = readerHtml(article, ReaderSettings(), false)
        assertTrue(html.contains("中文 &amp; English &lt;title&gt; " + "long ".repeat(100)))
        assertTrue(html.contains("<title>中文 &amp; English &lt;title&gt; " + "long ".repeat(100) + "</title>"))
        assertTrue(html.contains("&lt;source&gt;"))
        assertTrue(html.contains("<p>正文</p>"))
        assertTrue(html.contains("default-src 'none'"))
        assertTrue(html.contains("form-action 'none'"))
        assertFalse(html.contains("<script"))
        assertEquals(html, readerHtml(article.copy(isRead = true, isStarred = true), ReaderSettings(), false))
    }
}
