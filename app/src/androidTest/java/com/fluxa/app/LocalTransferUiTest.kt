package com.fluxa.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.directrss.*
import com.fluxa.app.data.local.ReaderPreferences
import com.fluxa.app.ui.feedlist.*
import com.fluxa.app.ui.theme.FluxaTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Owned in-memory Room and uniquely named preferences; never MainActivity/auth or the real library. */
class LocalTransferUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: DirectRssDatabase
    private lateinit var model: LocalTransferViewModel
    private lateinit var scope: CoroutineScope
    private val store = ViewModelStore()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferenceName = "transfer-ui-fixture-${UUID.randomUUID()}"
    private var requests = 0
    private var failSecond = true
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, DirectRssDatabase::class.java).addMigrations(DirectRssDatabase.MIGRATION_1_2, DirectRssDatabase.MIGRATION_2_3, DirectRssDatabase.MIGRATION_3_4, DirectRssDatabase.MIGRATION_4_5, DirectRssDatabase.MIGRATION_5_6).allowMainThreadQueries().build()
        val ownedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(preferenceName, mode)
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = DirectRssRepository(db, DirectRssClient(readFeed = { url ->
            synchronized(this) { requests++ }
            if (url.endsWith("second") && failSecond) error("Synthetic second failure")
            if (url.endsWith("page")) ("<title>Publisher article</title><article><p>" + "Actual publisher fixture prose. ".repeat(8) + "</p></article>").toByteArray()
            else "<rss><channel><title>Fixture feed</title><item><guid>one</guid><title>Fixture entry</title></item></channel></rss>".toByteArray()
        }))
        model = LocalTransferViewModel(repo, ReaderPreferences(ownedContext, scope))
        store.put("fixture", model)
    }
    @After fun cleanup() {
        compose.runOnIdle { store.clear() }
        scope.cancel(); db.close(); context.deleteSharedPreferences(preferenceName)
    }
    @Test fun previewSelectionPartialFailureAndRetryUseRealRepository() {
        compose.runOnIdle {
            model.openImport()
            model.readOpml { """<opml version="2.0"><body>
                <outline text="First" xmlUrl="https://example.org/first"/>
                <outline text="Second" xmlUrl="https://example.org/second"/>
                <outline text="Unsafe" xmlUrl="file:///private"/>
                </body></opml>""".byteInputStream() }
        }
        compose.setContent { FluxaTheme { LocalTransferSheet(model) {} } }
        compose.waitUntil(10000) { model.state.value.rows.size == 3 && !model.state.value.busy }
        assertEquals(0, requests)
        compose.onNodeWithContentDescription("选择 Second").performScrollTo().performClick()
        assertFalse(model.state.value.rows[1].selected)
        compose.onNodeWithContentDescription("选择 Second").performScrollTo().performClick()
        compose.onNodeWithContentDescription("确认导入所选订阅").performScrollTo().performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.rows[1].status == ImportStatus.Failed }
        assertEquals(ImportStatus.Done, model.state.value.rows[0].status)
        compose.runOnIdle { failSecond = false }
        compose.onNodeWithContentDescription("仅重试失败订阅").performScrollTo().performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.rows[1].status == ImportStatus.Done }
        assertEquals(3, requests)
        runBlocking { assertEquals(2, db.dao().subscriptions().size) }
    }
    @Test fun ambiguousShareRequiresChoiceAndPreviewBeforePersistentSave() {
        compose.runOnIdle { model.receive(sharedLinks("https://example.org/page https://example.org/feed")) }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FluxaTheme { LocalTransferSheet(model) {} } }
        compose.onNodeWithContentDescription("保存文章").assertIsNotEnabled()
        compose.onNodeWithContentDescription("订阅网站").assertIsNotEnabled()
        assertEquals(0, requests)
        compose.onNodeWithContentDescription("选择 https://example.org/page").performScrollTo().performClick()
        compose.onNodeWithContentDescription("保存文章").performScrollTo().performClick()
        compose.waitUntil(10000) { model.state.value.page != null && !model.state.value.busy }
        runBlocking { assertTrue(db.dao().articles().isEmpty()) }
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(1, requests)
        assertNotNull(model.state.value.page)
        compose.onNodeWithContentDescription("确认保存文章").performScrollTo().performClick()
        compose.waitUntil(10000) { model.state.value.savedId != null && !model.state.value.busy }
        runBlocking { assertEquals(1, db.dao().articles().size); assertTrue(db.dao().subscriptions().isEmpty()) }
        assertEquals(1, requests)
    }
}
