package com.fluxa.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.github.*
import com.fluxa.app.domain.GitHubRules
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GitHubLibraryTest {
    private lateinit var db: GitHubDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: GitHubClient
    private lateinit var repository: GitHubRepository
    private var now = 1_800_000_000_000L
    private var date = LocalDate.of(2026, 10, 2)
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GitHubDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer(); server.start(); client = GitHubClient(server.url("/"))
        repository = GitHubRepository(db, client, { now }, { date })
    }
    @After fun close() { server.shutdown(); db.close() }
    private fun page(body: String, next: String? = null): MockResponse = MockResponse().setBody(body).apply { if (next != null) addHeader("Link", "<$next>; rel=\"next\"") }
    @Test fun numericCanonicalPaginationDeduplicatesAndAcceptsMissingMetadata() = runBlocking {
        server.enqueue(page("""[{"id":1,"full_name":"one","topics":["ai"]},{"id":1},{"id":2,"description":null}]""", server.url("/user/7663934/starred?per_page=100&page=2").toString()))
        server.enqueue(page("""[{"id":3,"full_name":"three"}]"""))
        repository.importStars("Vincent1993")
        assertEquals(listOf(1L, 2L, 3L), db.dao().repos("vincent1993").map { it.id }.sorted())
        assertEquals("", db.dao().repo("vincent1993", 2)!!.description)
        assertNull(db.dao().profile("vincent1993")!!.cursor)
        assertEquals(2, db.dao().profile("vincent1993")!!.pages)
        server.takeRequest(); val second = server.takeRequest()
        assertEquals("/user/7663934/starred", second.requestUrl!!.encodedPath)
        assertNull(second.getHeader("Authorization")); assertNull(second.getHeader("Cookie"))
    }
    @Test fun failureDoesNotAdvanceAndResumeSurvivesRepositoryRecreation() = runBlocking {
        val next = server.url("/users/alice/starred?page=2&per_page=100").toString()
        server.enqueue(page("""[{"id":1,"full_name":"old"}]""", next))
        server.enqueue(MockResponse().setResponseCode(500))
        repository.importStars("alice")
        val saved = db.dao().profile("alice")!!
        assertEquals(1, saved.pages); assertNotNull(saved.cursor); assertTrue(saved.status.contains("未完成"))
        repository.editTags("alice", 1, emptyList())
        server.enqueue(page("""[{"id":1,"full_name":"updated"},{"id":2}]"""))
        GitHubRepository(db, client, { now }, { date }).importStars("ALICE")
        assertEquals("", db.dao().repo("alice", 1)!!.manualTags)
        assertEquals("updated", db.dao().repo("alice", 1)!!.name)
        assertEquals(2, db.dao().profile("alice")!!.pages)
        assertEquals(2, db.dao().repos("alice").size)
    }
    @Test fun cooldownIsGlobalPersistedAndCannotBeBypassedByProfileSwitch() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "120").addHeader("X-RateLimit-Reset", (now / 1000 + 180).toString()))
        repository.importStars("alice")
        assertEquals(now + 180_000, db.dao().settings()!!.cooldownUntil)
        repository.select("bob")
        GitHubRepository(db, client, { now }, { date }).importStars("bob")
        assertEquals(1, server.requestCount)
        now += 180_001
        server.enqueue(page("[]")); repository.importStars("bob")
        assertEquals(2, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(403).addHeader("Retry-After", "bogus"))
        repository.importStars("bob")
        assertEquals(now + 60_000, db.dao().settings()!!.cooldownUntil)
    }
    @Test fun invalidAndCyclicLinksAndMissingIdDoNotCommitBadPages() = runBlocking {
        for (link in listOf("https://evil.example/users/alice/starred?page=2", server.url("/repos/alice/x?page=2").toString(), client.first("alice"))) {
            server.enqueue(page("""[{"id":1}]""", link)); repository.importStars("alice")
            assertEquals(0, db.dao().repos("alice").size)
            assertEquals(0, db.dao().profile("alice")!!.pages)
        }
        server.enqueue(page("""[{"description":"missing id"}]""")); repository.importStars("alice")
        assertEquals(0, db.dao().repos("alice").size)
    }
    @Test fun safeLinkValidationRejectsCredentialsFragmentsQueriesAndCrossProfileEndpoints() {
        val allowed = server.url("/user/7663934/starred?page=2&per_page=100").toString()
        assertNotNull(client.next("<$allowed>; rel=\"next\", <${client.first("alice")}>; rel=\"prev\"", "alice"))
        val bad = listOf(
            server.url("/users/bob/starred?page=2").toString(),
            server.url("/user/notnumeric/starred?page=2").toString(),
            server.url("/users/alice/starred?page=2&page=3").toString(),
            server.url("/users/alice/starred?page=2&token=secret").toString(),
            server.url("/users/alice/starred?page=-1").toString(),
            server.url("/users/alice/starred?page=2&per_page=101").toString(),
            allowed + "#fragment", allowed.replace("http://", "http://user:password@"),
            allowed.replace("http://", "https://"))
        bad.forEach { assertTrue(it, runCatching { client.validate(it, "alice") }.isFailure) }
        assertTrue(runCatching { client.next("<$allowed>; rel=\"next\", <$allowed>; rel=\"next\"", "alice") }.isFailure)
        assertTrue(runCatching { client.next("broken; rel=\"next\"", "alice") }.isFailure)
    }
    @Test fun numericCanonicalIdCannotSwitchMidImport() = runBlocking {
        server.enqueue(page("""[{"id":1}]""", server.url("/user/10/starred?page=2").toString()))
        server.enqueue(page("""[{"id":2}]""", server.url("/user/11/starred?page=3").toString()))
        repository.importStars("alice")
        assertEquals(listOf(1L), db.dao().repos("alice").map { it.id })
        assertEquals(1, db.dao().profile("alice")!!.pages)
        assertTrue(db.dao().profile("alice")!!.status.contains("ID"))
    }
    @Test fun transportFailureKeepsRetryCursorAndExistingCache() = runBlocking {
        db.dao().putRepo(GitHubRepo("alice", 99, "offline", manualTags = "自定义"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        // Avoid OkHttp's transparent retry hanging on an empty MockWebServer queue.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        repository.importStars("alice")
        assertNotNull(db.dao().profile("alice")!!.cursor)
        assertEquals(0, db.dao().profile("alice")!!.pages)
        assertEquals("自定义", db.dao().repo("alice", 99)!!.manualTags)
    }
    @Test fun cap404AndIncrementalNonDeletionAreExplicit() = runBlocking {
        db.dao().putRepo(GitHubRepo("alice", 9, "kept", manualTags = "custom"))
        server.enqueue(page("""[{"id":1}]""", server.url("/user/10/starred?page=2").toString()))
        repository.importStars("alice", maxPages = 1)
        assertTrue(db.dao().profile("alice")!!.status.contains("上限"))
        server.enqueue(MockResponse().setResponseCode(404)); repository.importStars("alice")
        assertTrue(db.dao().profile("alice")!!.status.contains("404"))
        assertEquals(1, db.dao().profile("alice")!!.pages)
        assertEquals("custom", db.dao().repo("alice", 9)!!.manualTags)
        server.enqueue(page("[]")); repository.importStars("alice")
        assertEquals(2, db.dao().repos("alice").size)
    }
    @Test fun inFlightImportCannotOverwriteEditsOrWriteToNewProfile() = runBlocking {
        db.dao().putRepo(GitHubRepo("alice", 1, "before"))
        val received = CountDownLatch(1); val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received.countDown(); check(release.await(10, TimeUnit.SECONDS))
                return page("""[{"id":1,"full_name":"after","topics":["ai"]}]""")
            }
        }
        val job = launch(Dispatchers.IO) { repository.importStars("alice") }
        try {
            assertTrue(received.await(10, TimeUnit.SECONDS))
            repository.editTags("alice", 1, listOf("我的用途")); repository.select("bob")
        } finally { release.countDown() }
        job.join()
        assertEquals("我的用途", db.dao().repo("alice", 1)!!.manualTags)
        assertEquals("after", db.dao().repo("alice", 1)!!.name)
        assertTrue(db.dao().repos("bob").isEmpty()); assertEquals("bob", db.dao().settings()!!.active)
        repository.editTags("alice", 1, emptyList())
        assertTrue(GitHubRules.tags(db.dao().repo("alice", 1)!!).isEmpty())
        repository.editTags("alice", 1, null)
        assertEquals(listOf("AI"), GitHubRules.tags(db.dao().repo("alice", 1)!!))
    }
    @Test fun dailyStableAcrossAdditionsDaysChangesAndSmallLibraries() = runBlocking {
        assertNull(repository.daily("alice"))
        (1L..9L).forEach { db.dao().putRepo(GitHubRepo("alice", it, "r$it", manualTags = listOf("AI", "前端", "安全")[(it % 3).toInt()])) }
        val first = repository.daily("alice")!!
        val ids = first.ids.split(',').map(String::toLong)
        assertEquals(3, ids.map { db.dao().repo("alice", it)!!.manualTags }.distinct().size)
        db.dao().putRepo(GitHubRepo("alice", 10, "new"))
        assertEquals(first, GitHubRepository(db, client, { now }, { date }).daily("alice"))
        val changed = repository.daily("alice", true)!!
        assertTrue(changed.ids.split(',').toSet().intersect(first.ids.split(',').toSet()).isEmpty())
        date = date.plusDays(1)
        val tomorrow = repository.daily("alice")!!
        assertTrue(tomorrow.ids.split(',').toSet().intersect((first.ids + "," + changed.ids).split(',').toSet()).isEmpty())
        for (count in 0..3) {
            val profile = "small$count"
            repeat(count) { db.dao().putRepo(GitHubRepo(profile, it.toLong(), "small")) }
            val daily = repository.daily(profile)
            assertEquals(count, daily?.ids?.split(',')?.size ?: 0)
            if (count > 0) assertEquals(count, repository.daily(profile, true)!!.ids.split(',').distinct().size)
        }
    }
    @Test fun sevenDayHistoryBoundaryAndProfileIsolation() = runBlocking {
        (1L..6L).forEach { db.dao().putRepo(GitHubRepo("alice", it, "r$it", manualTags = "AI")) }
        (1L..3L).forEach { db.dao().putHistory(GitHubHistory("alice", date.minusDays(7).toString(), it)) }
        (4L..6L).forEach {
            db.dao().putHistory(GitHubHistory("alice", date.minusDays(8).toString(), it))
            db.dao().putHistory(GitHubHistory("bob", date.toString(), it))
        }
        assertEquals(setOf("4", "5", "6"), repository.daily("alice")!!.ids.split(',').toSet())
        assertNull(repository.daily("bob"))
    }
    @Test fun fileDatabaseReopenPreservesIsolationOverridesCursorCooldownAndDaily() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "github-unit-${System.nanoTime()}.db"
        fun open() = Room.databaseBuilder(context, GitHubDatabase::class.java, name).build()
        var disk = open()
        try {
            disk.dao().putRepo(GitHubRepo("alice", 1, "alice", manualTags = ""))
            disk.dao().putRepo(GitHubRepo("bob", 1, "bob", manualTags = "custom"))
            disk.dao().putProfile(GitHubProfile("alice", client.first("alice"), pages = 2))
            disk.dao().putSettings(GitHubSettings(active = "bob", cooldownUntil = now + 60_000))
            val daily = GitHubRepository(disk, client, { now }, { date }).daily("alice")
            disk.close(); disk = open()
            assertEquals("", disk.dao().repo("alice", 1)!!.manualTags)
            assertEquals("custom", disk.dao().repo("bob", 1)!!.manualTags)
            assertEquals(daily, disk.dao().daily("alice", date.toString()))
            assertEquals(listOf(1L), disk.dao().recent("alice", date.minusDays(7).toString(), date.toString()))
            assertEquals(2, disk.dao().profile("alice")!!.pages)
            assertEquals(now + 60_000, disk.dao().settings()!!.cooldownUntil)
        } finally { disk.close(); context.deleteDatabase(name) }
    }
}
