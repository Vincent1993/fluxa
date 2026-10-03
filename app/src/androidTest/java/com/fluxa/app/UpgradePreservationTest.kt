package com.fluxa.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxa.app.data.local.*
import com.fluxa.app.data.sync.BackgroundSyncSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Run only on an isolated emulator. `seed` then `verify` straddles adb install -r.
 * Normal execution checks the same storage contracts and removes its own fixtures.
 * The cookie is deliberately fictional; no server authentication is attempted.
 */
class UpgradePreservationTest {
    @Test fun databaseSettingsQueueAndEncryptedSessionSurviveReplacement(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.fluxa.app.debug")
        val mode = InstrumentationRegistry.getArguments().getString("fluxa.upgrade")
        check(mode == null || mode == "seed" || mode == "verify")
        val marker = context.getSharedPreferences("fluxa_upgrade_fixture", Context.MODE_PRIVATE)
        val store = SecureTokenStore(context)
        val settings = BackgroundSyncSettings(context)
        val db = Room.databaseBuilder(context, FluxaDatabase::class.java, "newsblur.db")
            .addMigrations(DatabaseMigrations.FROM_1, DatabaseMigrations.FROM_2).build()
        val id = "999998:upgrade-fixture"
        val account = "fluxa-upgrade-fixture"
        val cookie = "newsblur_sessionid=upgrade-fixture"
        try {
            if (mode != "verify") {
                // Refuse to replace an existing account, even on an accidentally selected device.
                check(store.getNewsBlurAccount() == null)
                check(db.articleDao().getById(id) == null)
                marker.edit().putBoolean("original_sync", settings.enabled).commit()
                db.articleDao().upsertAll(listOf(ArticleEntity(id, "Upgrade fixture", "Fixture feed",
                    1700000000, true, true, "<p>Preserved offline body</p>", "999998", "fixture")))
                db.subscriptionDao().upsertAll(listOf(SubscriptionEntity("999998", "Fixture feed", "https://example.test/rss")))
                db.syncStateDao().upsert(SyncStateEntity("999998", "2", 1700000000000, "fixture-token"))
                db.pendingActionDao().insert(PendingActionEntity(articleId = id, actionType = "MarkRead", createdAtEpochMillis = 1700000000001))
                db.pendingActionDao().insert(PendingActionEntity(articleId = id, actionType = "ToggleStar", payload = "true", createdAtEpochMillis = 1700000000002))
                store.saveNewsBlurSession(account, cookie, System.currentTimeMillis() + 86400000)
                settings.setEnabled(true)
                // Instrumentation may terminate its process immediately. Finish the old
                // app's asynchronous preference write before the installer force-stops it.
                check(context.getSharedPreferences("fluxa_sync_preferences", Context.MODE_PRIVATE)
                    .edit().putBoolean("enabled", true).commit())
                check(marker.edit().putBoolean("seeded", true).commit())
            }
            assertTrue(marker.getBoolean("seeded", false))
            val article = db.articleDao().getById(id)!!
            assertTrue(article.isRead)
            assertTrue(article.isStarred)
            assertEquals("<p>Preserved offline body</p>", article.contentHtml)
            assertEquals("fixture", article.tags)
            assertEquals("https://example.test/rss", db.subscriptionDao().getAll().single { it.id == "999998" }.url)
            assertEquals("2", db.syncStateDao().getBySource("999998")!!.cursor)
            assertEquals("fixture-token", db.syncStateDao().getBySource("999998")!!.syncToken)
            val pending = db.pendingActionDao().getAllOrdered().filter { it.articleId == id }
            assertEquals(listOf("MarkRead", "ToggleStar"), pending.map { it.actionType })
            assertEquals("true", pending.last().payload)
            assertEquals(listOf(1700000000001, 1700000000002), pending.map { it.createdAtEpochMillis })
            assertEquals(account, store.getNewsBlurAccount())
            assertEquals(cookie, store.getNewsBlurSession())
            assertTrue(settings.enabled)
            assertEquals(3, db.openHelper.readableDatabase.version)
        } finally {
            if (mode != "seed" && marker.getBoolean("seeded", false)) {
                db.pendingActionDao().getAllOrdered().filter { it.articleId == id }
                    .forEach { db.pendingActionDao().deleteById(it.id) }
                db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE id = ?", arrayOf(id))
                db.openHelper.writableDatabase.execSQL("DELETE FROM subscriptions WHERE id = ?", arrayOf("999998"))
                db.openHelper.writableDatabase.execSQL("DELETE FROM sync_state WHERE source = ?", arrayOf("999998"))
                if (store.getNewsBlurAccount() == account) store.clear()
                settings.setEnabled(marker.getBoolean("original_sync", false))
                marker.edit().clear().commit()
            }
            db.close()
        }
    }
}
