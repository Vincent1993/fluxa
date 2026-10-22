package com.fluxa.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.directrss.*
import com.fluxa.app.data.local.*
import com.fluxa.app.ui.feedlist.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import androidx.lifecycle.ViewModelStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LocalBackupTest {
    private val source = DirectSubscription("direct-rss:stage1a-owned-source", "Owned", "https://example.org/rss", false, paused = true)
    private val article = DirectArticle("direct-rss:stage1a-owned-a", "legacy-guid", source.id, "Owned", 123,
        "<p>Exact RSS</p>", "https://example.org/a", true, true, summary = "Metadata", readLater = true)
    private fun payload() = BackupPayload(emptyList(), listOf(source), listOf(article), mapOf(article.id to stableHash(article.body)),
        listOf(DirectFullBody(article.id, "<p>Distinct full text</p>")), listOf(DirectReadingMode(source.id, "FULL_TEXT")),
        listOf(DirectReaderNote("note", article.id, "RSS", "Exact RSS", stableHash("Exact RSS"), 0, 1, "Private note")),
        setOf(article.id), emptySet(), TriageConfig(), ReaderSnapshot(ReaderSettings(), mapOf(article.id to ReadingPosition(.5f, 50, 100)),
            ReadingSession(listOf(article.id))), true, false)
    @Test fun typedRoundTripKeepsLegacyIdentityAndSeparatePayloads() {
        val p = payload(); val bytes = BackupJson.encode(p)
        assertEquals(p, BackupJson.decode(bytes))
        val text = bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains("Cookie")); assertFalse(text.contains("token")); assertFalse(text.contains("reader_private"))
    }
    @Test fun rejectAllInvalidPayloadBeforeStorage() {
        fun rejected(change: (JSONObject) -> Unit) {
            val o = JSONObject(BackupJson.encode(payload()).toString(Charsets.UTF_8)); change(o)
            try { BackupJson.decode(o.toString().toByteArray()); fail("must reject") } catch (_: IllegalArgumentException) { }
        }
        rejected { it.put("version", 999) }
        rejected { it.getJSONArray("articles").put(it.getJSONArray("articles").getJSONObject(0)) }
        rejected { it.getJSONArray("sources").getJSONObject(0).put("url", "https://user:password@example.org/rss") }
        rejected { it.getJSONArray("articles").getJSONObject(0).put("source", "missing") }
        rejected { it.getJSONObject("reader").getJSONArray("positions").getJSONObject(0).put("fraction", "NaN") }
        rejected { it.getJSONObject("reader").getJSONArray("session").put("unknown") }
        rejected { it.getJSONArray("notes").getJSONObject(0).put("hash", "0".repeat(64)) }
        rejected { it.getJSONArray("full").getJSONObject(0).put("body", "<img src=\"data:image/svg+xml;base64,PHN2Zz4=\">") }
        rejected { it.put("credentials", "must not accept blobs") }
        try { BackupJson.decode(ByteArray(BackupJson.MAX_BYTES + 1)); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun versionAndFormatErrorsAreStrictLocalizedAndPayloadFree() {
        fun message(bytes: ByteArray): String {
            try { BackupJson.decode(bytes); fail("must reject") }
            catch (e: BackupValidationException) { return e.message!! }
            error("unreachable")
        }
        fun version(value: Any): ByteArray = JSONObject(BackupJson.encode(payload()).toString(Charsets.UTF_8))
            .put("version", value).toString().toByteArray()
        assertEquals("不支持此备份版本", message(version(999)))
        for (bad in listOf("1", 1.5, JSONObject.NULL, "NaN", "Infinity")) {
            assertEquals("备份格式不正确", message(version(bad)))
        }
        assertEquals("备份格式不正确", message("{secret-payload".toByteArray()))
        assertEquals("备份格式不正确", message(byteArrayOf(0xc3.toByte(), 0x28)))
        val wrongFormat = JSONObject(BackupJson.encode(payload()).toString(Charsets.UTF_8)).put("format", "secret-payload")
        assertEquals("备份格式不正确", message(wrongFormat.toString().toByteArray()))
        val duplicate = JSONObject(BackupJson.encode(payload()).toString(Charsets.UTF_8))
        duplicate.getJSONArray("articles").put(duplicate.getJSONArray("articles").getJSONObject(0))
        assertEquals("备份含重复记录", message(duplicate.toString().toByteArray()))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun realPreviewUiRejectsBadFilesWithoutWritesOrExceptionLeaksAndDistinguishesIo() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = ViewModelStore()
        val db = Room.inMemoryDatabaseBuilder(context, DirectRssDatabase::class.java).allowMainThreadQueries().build()
        try {
            val service = LocalBackup(db, ReaderPreferences(context, scope), LocalOfflineSettings(context), TriageSettings(context))
            service.restore(service.preview(BackupJson.encode(payload())))
            suspend fun snapshot() = listOf(db.dao().folders(), db.dao().subscriptions(), db.dao().articles(),
                db.dao().allFullBodies(), db.dao().allModes(), db.dao().allNotes(), db.dao().revision(article.id))
            val before = snapshot()
            val model = LocalBackupViewModel(service).also { store.put("backup", it) }
            suspend fun idle(): BackupUiState = withTimeout(10000) { model.state.first { !it.busy } }
            val badVersion = JSONObject(BackupJson.encode(payload()).toString(Charsets.UTF_8)).put("version", 999).toString()
            for ((text, expected) in listOf(badVersion to "不支持此备份版本", "{secret-payload" to "备份格式不正确")) {
                model.read { BackupJson.encode(payload()).inputStream() }; assertNotNull(idle().preview)
                model.read { text.byteInputStream() }
                val state = idle()
                assertNull(state.preview); assertNull(state.pendingPreferences)
                assertTrue(state.message.contains(expected)); assertTrue(state.message.contains("原有数据未改变"))
                assertTrue(state.message.contains("重新选择")); assertFalse(state.message.contains("secret-payload"))
                assertFalse(state.message.contains("Failed requirement")); assertEquals(before, snapshot())
            }
            for (failure in listOf(java.io.IOException("secret-path"), SecurityException("secret-permission"))) {
                model.read { throw failure }
                val state = idle()
                assertTrue(state.message.contains("读取权限")); assertFalse(state.message.contains("格式不正确"))
                assertFalse(state.message.contains("secret")); assertEquals(before, snapshot())
            }
            model.read { object : java.io.InputStream() { override fun read(): Int = throw java.io.IOException("secret-read") } }
            assertFalse(idle().message.contains("secret-read"))
            model.export(false, false) { null }
            assertTrue(idle().message.contains("未能完成导出"))
            model.export(false, false) { object : java.io.OutputStream() { override fun write(b: Int) { throw java.io.IOException("secret-disk") } } }
            val exportFailure = idle().message
            assertTrue(exportFailure.contains("目标文件可能不完整")); assertFalse(exportFailure.contains("secret-disk"))
            assertFalse(exportFailure.contains("格式不正确")); assertEquals(before, snapshot())
            val previousMessage = model.state.value.message
            model.read { throw CancellationException("secret-cancellation") }
            assertEquals(previousMessage, idle().message) // Cancellation is not converted to a failure message.
            assertEquals(before, snapshot())
        } finally { store.clear(); scope.cancel(); db.close(); Dispatchers.resetMain() }
    }

    @Test fun previewIsReadOnlyAtomicMergeRejectsStaleConfirmAndKeepsExistingFlags() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reader = ReaderPreferences(context, scope)
        val db = Room.inMemoryDatabaseBuilder(context, DirectRssDatabase::class.java).allowMainThreadQueries().build()
        try {
            val service = LocalBackup(db, reader, LocalOfflineSettings(context), TriageSettings(context))
            val bytes = BackupJson.encode(payload())
            val readerBefore = reader.snapshot(setOf(article.id), setOf(article.id))
            val first = service.preview(bytes)
            assertEquals(readerBefore, reader.snapshot(setOf(article.id), setOf(article.id)))
            assertTrue(db.dao().articles().isEmpty()); assertEquals(1, first.newArticles)
            db.dao().putFolder(DirectFolder("concurrent", "Concurrent"))
            try { service.restore(first); fail("stale confirm") } catch (_: IllegalStateException) { }
            assertTrue(db.dao().articles().isEmpty()); assertTrue(db.dao().subscriptions().isEmpty())
            service.restore(service.preview(bytes))
            assertEquals(article.body, db.dao().article(article.id)!!.body)
            assertEquals("<p>Distinct full text</p>", db.dao().fullBody(article.id)!!.body)
            db.dao().setReadState(article.id, false)
            service.restore(service.preview(bytes))
            assertFalse(db.dao().article(article.id)!!.isRead)
            assertEquals(1, db.dao().allNotes().size)
            val old = db.dao().articles()
            val conflict = payload().copy(articles = listOf(article.copy(guid = "different")))
            try { service.preview(BackupJson.encode(conflict)); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(old, db.dao().articles())
            val exported = BackupJson.decode(service.export())
            assertFalse(exported.bodies); assertFalse(exported.images)
            assertEquals("", exported.articles.single().body); assertTrue(exported.full.all { it.body.isEmpty() })
            assertEquals(stableHash(article.body), exported.hashes[article.id])
        } finally { db.close(); scope.cancel() }
    }
    @Test fun midTransactionStorageFailureRollsBackSubscriptionsArticlesAndNotes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, DirectRssDatabase::class.java).allowMainThreadQueries().build()
        try {
            val service = LocalBackup(db, ReaderPreferences(context, scope), LocalOfflineSettings(context), TriageSettings(context))
            val preview = service.preview(BackupJson.encode(payload()))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER owned_abort_note BEFORE INSERT ON reader_notes BEGIN SELECT RAISE(ABORT, 'owned storage failure'); END")
            try { service.restore(preview); fail() } catch (_: android.database.sqlite.SQLiteException) { }
            assertTrue(db.dao().subscriptions().isEmpty()); assertTrue(db.dao().articles().isEmpty())
            assertTrue(db.dao().allNotes().isEmpty()); assertTrue(db.dao().allFullBodies().isEmpty())
        } finally { db.close(); scope.cancel() }
    }

    @Test fun absoluteFlagReceiptsNeverUndoLaterReadStarOrReadLaterChanges() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, DirectRssDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.dao().putSubscription(source); db.dao().putArticle(article)
            val repo = DirectRssRepository(db)
            val read = requireNotNull(repo.changeFlag(article.id, com.fluxa.app.data.repository.ArticleFlag.Read, false))
            assertTrue(repo.undoFlag(read)); assertFalse(repo.undoFlag(read)); assertTrue(db.dao().article(article.id)!!.isRead)
            val star = requireNotNull(repo.changeFlag(article.id, com.fluxa.app.data.repository.ArticleFlag.Star, false))
            repo.setReadState(article.id, false)
            assertFalse(repo.undoFlag(star)); assertFalse(db.dao().article(article.id)!!.isStarred)
            val later = requireNotNull(repo.changeFlag(article.id, com.fluxa.app.data.repository.ArticleFlag.ReadLater, false))
            repo.setReadLater(article.id, true); repo.setReadLater(article.id, false)
            assertFalse(repo.undoFlag(later)) // ABA also protected by the durable revision
            val latest = requireNotNull(repo.changeFlag(article.id, com.fluxa.app.data.repository.ArticleFlag.Star, true))
            assertTrue(repo.undoFlag(latest)); assertFalse(db.dao().article(article.id)!!.isStarred)
        } finally { db.close() }
    }

    @Test fun metadataOnlyImportCreatesExplicitEmptyBodyWithoutFetch() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, DirectRssDatabase::class.java).allowMainThreadQueries().build()
        try {
            val service = LocalBackup(db, ReaderPreferences(context, scope), LocalOfflineSettings(context), TriageSettings(context))
            val p = payload().copy(bodies = false, full = emptyList(), articles = listOf(article.copy(body = "")))
            val preview = service.preview(BackupJson.encode(p)); service.restore(preview)
            val row = db.dao().article(article.id)!!
            assertEquals("", row.body); assertEquals("Metadata", row.summary); assertTrue(row.readLater)
            assertNull(db.dao().fullBody(article.id))
        } finally { db.close(); scope.cancel() }
    }
}
