package com.fluxa.app.data

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.directrss.*
import com.fluxa.app.data.local.ReaderPreferences
import com.fluxa.app.data.local.ReadingPosition
import com.fluxa.app.data.repository.ArticleRepository
import com.fluxa.app.data.repository.CompositeArticleRepository
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.feedlist.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalTransferPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: DirectRssDatabase
    private lateinit var prefs: ReaderPreferences
    private lateinit var scope: CoroutineScope
    private val store = ViewModelStore()
    private val xml = "<rss><channel><title>Publisher</title><item><guid>stable</guid><title>Actual fixture</title><description>Body</description></item></channel></rss>".toByteArray()
    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = Room.inMemoryDatabaseBuilder(context, DirectRssDatabase::class.java).addMigrations(DirectRssDatabase.MIGRATION_1_2, DirectRssDatabase.MIGRATION_2_3, DirectRssDatabase.MIGRATION_3_4, DirectRssDatabase.MIGRATION_4_5, DirectRssDatabase.MIGRATION_5_6).allowMainThreadQueries().build()
        context.getSharedPreferences("reader_private", Context.MODE_PRIVATE).edit().clear().commit()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        prefs = ReaderPreferences(context, scope)
    }
    @After fun teardown() { store.clear(); scope.cancel(); db.close(); Dispatchers.resetMain() }
    private fun model(repo: DirectRssRepository) = LocalTransferViewModel(repo, prefs).also { store.put("transfer", it) }
    private suspend fun idle(vm: LocalTransferViewModel) = withTimeout(10000) { vm.state.first { !it.busy } }
    private fun opml(vararg urls: String) = ("<opml version=\"2.0\"><body><outline text=\"Folder\">" +
        urls.joinToString("") { "<outline text=\"Imported\" xmlUrl=\"$it\"/>" } + "</outline></body></opml>").byteInputStream()

    @Test fun perFeedFailureRetrySkipsSuccessAndExistingSettingsAndFlags() = runBlocking {
        val requests = mutableListOf<String>(); var failing = true
        val repo = DirectRssRepository(db, DirectRssClient(readFeed = { url ->
            synchronized(requests) { requests += url }
            if (url.endsWith("bad") && failing) error("fixture failure")
            xml
        }))
        repo.addSubscription("https://example.org/existing")
        val existing = db.dao().subscriptions().single()
        val article = db.dao().articles().single()
        repo.organize(existing.id, "Keep alias", null, true, true)
        repo.markRead(article.id); repo.toggleStar(article.id)
        val original = db.dao().subscription(existing.id)
        val vm = model(repo); vm.openImport()
        vm.readOpml { opml("https://example.org/good", "https://example.org/bad", existing.url) }; idle(vm)
        assertEquals(listOf(ImportStatus.New, ImportStatus.New, ImportStatus.Archived), vm.state.value.rows.map { it.status })
        vm.importSelected(); idle(vm)
        assertEquals(listOf(ImportStatus.Done, ImportStatus.Failed, ImportStatus.Archived), vm.state.value.rows.map { it.status })
        failing = false
        vm.importSelected(true); idle(vm)
        assertEquals(ImportStatus.Done, vm.state.value.rows[1].status)
        assertEquals(1, requests.count { it.endsWith("good") }); assertEquals(2, requests.count { it.endsWith("bad") })
        assertEquals(1, requests.count { it.endsWith("existing") })
        assertEquals(original, db.dao().subscription(existing.id))
        assertEquals(article.copy(isRead = true, isStarred = true), db.dao().article(article.id))
        assertFalse(repo.importRecord(OpmlRecord("Overwrite?", "https://example.org/good", "Other")))
        assertEquals(listOf("Folder"), db.dao().folders().map { it.name })
        repo.organize(existing.id, "Active paused", null, true, false)
        val active = db.dao().subscription(existing.id)
        assertFalse(repo.importRecord(OpmlRecord("Do not reset", existing.url, "Other")))
        assertEquals(active, db.dao().subscription(existing.id))
        assertEquals(1, requests.count { it.endsWith("existing") })
    }

    @Test fun stopImportPreventsLateCommitAndFutureRequestsAndCreatesNoOrphanFolder() = runBlocking {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        var requests = 0
        val repo = DirectRssRepository(db, DirectRssClient(readFeed = {
            requests++; started.countDown(); check(release.await(10, TimeUnit.SECONDS)); xml
        }))
        val vm = model(repo); vm.openImport(); vm.readOpml { opml("https://example.org/one", "https://example.org/two") }; idle(vm)
        try {
            vm.importSelected(); assertTrue(started.await(10, TimeUnit.SECONDS)); vm.stopImport()
        } finally { release.countDown() }
        // Acquire repository mutex after cancelled work has unwound; no sleeps/network.
        repo.refresh()
        assertEquals(1, requests)
        assertTrue(db.dao().subscriptions().isEmpty()); assertTrue(db.dao().articles().isEmpty()); assertTrue(db.dao().folders().isEmpty())
        assertEquals(ImportStatus.Failed, vm.state.value.rows[0].status)
        assertEquals(ImportStatus.New, vm.state.value.rows[1].status)
    }

    @Test fun newShareInvalidatesPendingExtractionDocumentResultAndOldReaderOpen() = runBlocking {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        val repo = DirectRssRepository(db, DirectRssClient(readFeed = {
            started.countDown()
            try { check(release.await(10, TimeUnit.SECONDS)); pageHtml() } finally { finished.countDown() }
        }))
        val vm = model(repo); vm.openImport(); val ticket = vm.documentTicket()
        vm.receive(sharedLinks("https://example.org/old")); assertFalse(vm.acceptsDocument(ticket))
        try {
            vm.extract(); assertTrue(started.await(10, TimeUnit.SECONDS))
            vm.receive(sharedLinks("https://example.org/new"))
        } finally { release.countDown() }
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        assertEquals("https://example.org/new", vm.state.value.url)
        assertNull(vm.state.value.page); assertFalse(vm.state.value.busy)
        assertFalse(vm.prepareReader("missing")); assertTrue(db.dao().articles().isEmpty())
    }

    @Test fun saveUsesStableIdentityFreezesSessionAndPreservesReadingPosition() = runBlocking {
        val repo = DirectRssRepository(db, DirectRssClient(readFeed = { pageHtml() }))
        val vm = model(repo)
        prefs.awaitAndFreeze(listOf("old"), "old")
        vm.receive(sharedLinks("https://example.org/article"))
        assertTrue(db.dao().articles().isEmpty())
        vm.extract(); idle(vm); assertNotNull(vm.state.value.page); assertTrue(db.dao().articles().isEmpty())
        vm.save(); idle(vm); val id = vm.state.value.savedId!!
        repo.markRead(id); repo.toggleStar(id)
        val position = ReadingPosition(.5f, 50, 100); prefs.savePosition(id, position)
        assertTrue(vm.prepareReader(id)); assertEquals(listOf(id), prefs.session.value.ids)
        assertEquals(position, prefs.position(id))
        assertEquals(id, repo.savePage(vm.state.value.page!!.copy(title = "Updated")))
        assertEquals(1, db.dao().articles().size)
        assertTrue(db.dao().article(id)!!.isRead && db.dao().article(id)!!.isStarred)
        assertTrue(repo.articles().first().single().readLater)
        assertTrue(repo.subscriptions().first().isEmpty()); assertTrue(vm.hasLocalContent())
        vm.close(); assertFalse(vm.prepareReader(id))
    }

    @Test fun failedPagePreviewRetainsEditableUrlAndWebsitePathWithoutPersistence() = runBlocking {
        val repo = DirectRssRepository(db, DirectRssClient(readFeed = { "<title>Paywall</title><p>Sign in</p>".toByteArray() }))
        val vm = model(repo); vm.receive(sharedLinks("https://example.org/paywall"))
        vm.extract(); idle(vm)
        assertNotNull(vm.state.value.message); assertNull(vm.state.value.page)
        assertEquals("https://example.org/paywall", vm.state.value.url)
        vm.website(true); vm.editWebsiteUrl("https://example.org/edited"); vm.website(false)
        assertEquals("https://example.org/edited", vm.state.value.url)
        assertTrue(db.dao().articles().isEmpty()); assertTrue(db.dao().subscriptions().isEmpty())
    }

    @Test fun savedCopyDoesNotReplaceFeedCopyOfSameUrl() = runBlocking {
        val repo = DirectRssRepository(db, DirectRssClient(readFeed = {
            String(xml).replace("</item>", "<link>https://example.org/article</link></item>").toByteArray()
        }))
        repo.addSubscription("https://example.org/feed")
        val original = db.dao().articles().single()
        val saved = repo.savePage(SavedPage(original.originalUrl, "Page title", "<p>Publisher body</p>"))
        assertNotEquals(original.id, saved)
        repo.markRead(saved); repo.toggleStar(saved)
        assertEquals(original, db.dao().article(original.id))
        assertEquals(2, db.dao().articles().size)
        assertFalse(db.dao().subscriptions().single().archived)
    }

    @Test fun savedOnlyDiskLibraryReopensAndNeverRoutesSignedOutRefreshToRemote() = runBlocking {
        val name = "saved-pages-fixture-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, DirectRssDatabase::class.java, name)
            .addMigrations(DirectRssDatabase.MIGRATION_1_2, DirectRssDatabase.MIGRATION_2_3, DirectRssDatabase.MIGRATION_3_4, DirectRssDatabase.MIGRATION_4_5, DirectRssDatabase.MIGRATION_5_6).allowMainThreadQueries().build()
        try {
            val first = open()
            val id = try {
                val repo = DirectRssRepository(first)
                repo.savePage(SavedPage("https://example.org/a", "Saved", "<p>Actual body</p>")).also { repo.markRead(it); repo.toggleStar(it) }
            } finally { first.close() }
            val reopened = open()
            try {
                val local = DirectRssRepository(reopened)
                val remote = CountingRemote()
                val signedOut = CompositeArticleRepository.forTest(remote, local) { false }
                signedOut.refresh(); signedOut.selectSource(SAVED_PAGE_SOURCE); signedOut.selectSource(null)
                try { signedOut.loadMore(); fail() } catch (_: IllegalStateException) { }
                assertEquals(0, remote.calls)
                val row = signedOut.getPagedArticles().first().single()
                assertEquals(id, row.id); assertTrue(row.isRead && row.isStarred); assertTrue(row.readLater)
                val signedIn = CompositeArticleRepository.forTest(remote, local) { true }
                signedIn.refresh(); assertEquals(1, remote.calls)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun initializeRetainsLocalErrorButStillInitializesOnlyAuthorizedRemote() = runBlocking {
        db.dao().putSubscription(DirectSubscription("direct-rss:failed", "Failed", "https://example.org/failed", false))
        val local = DirectRssRepository(db, DirectRssClient(readFeed = { throw IllegalArgumentException("owned invalid feed") }))
        val remote = CountingRemote()
        try { CompositeArticleRepository.forTest(remote, local) { true }.initialize(null); fail("must retain local error") }
        catch (error: IllegalStateException) { assertTrue(error.message.orEmpty().contains("owned invalid feed")) }
        assertEquals(1, remote.calls)
        // Fresh repository avoids the foreground attempt dedup gate from masking this assertion.
        val signedOutLocal = DirectRssRepository(db, DirectRssClient(readFeed = { throw IllegalArgumentException("owned invalid feed") }))
        runCatching { CompositeArticleRepository.forTest(remote, signedOutLocal) { false }.initialize(null) }
        assertEquals(1, remote.calls)
    }

    @Test fun publisherBodyDivKeepsInternalHeadingImageAndLinkButNotTitleBlock() {
        val html = "<title>Owned page</title><article><div class='titlediv'><h1>Publisher duplicate</h1><h3>Publisher author</h3></div>" +
            "<div class='bodydiv'><h2>Legitimate section</h2><p>" + "Actual readable publisher prose. ".repeat(10) +
            "</p><img src='/image.png'><a href='/reference'>Reference</a></div></article>"
        val page = WebPageExtractor.parse(html.toByteArray(), "https://example.org/page")
        assertFalse(page.body.contains("Publisher duplicate")); assertFalse(page.body.contains("Publisher author"))
        assertTrue(page.body.contains("<h2>Legitimate section</h2>"))
        assertTrue(page.body.contains("https://example.org/image.png")); assertTrue(page.body.contains("https://example.org/reference"))
    }

    private fun pageHtml() = ("<title>Actual fixture page</title><article><p>" + "Publisher sentence with actual content. ".repeat(8) + "</p></article>").toByteArray()
    private class CountingRemote : ArticleRepository {
        var calls = 0
        override fun getPagedArticles() = flowOf(emptyList<Article>())
        override suspend fun refresh() { calls++ }
        override suspend fun loadMore() { calls++ }
        override suspend fun markRead(id: String) { calls++ }
        override suspend fun toggleStar(id: String) { calls++ }
        override suspend fun addSubscription(url: String) { calls++ }
    }
}
