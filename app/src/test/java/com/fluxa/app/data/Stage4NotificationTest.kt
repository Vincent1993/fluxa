package com.fluxa.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.directrss.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class Stage4NotificationTest {
    @Test fun quietWrapsMidnightAndUsesLocalDstClock() {
        fun quiet(at: String, zone: String) = notificationQuiet(Clock.fixed(Instant.parse(at), ZoneId.of(zone)))
        assertTrue(quiet("2026-10-09T14:00:00Z", "Asia/Shanghai")) // 22:00
        assertTrue(quiet("2026-10-09T23:59:59Z", "Asia/Shanghai"))
        assertFalse(quiet("2026-10-10T00:00:00Z", "Asia/Shanghai")) // 08:00
        assertFalse(quiet("2026-10-09T13:59:59Z", "Asia/Shanghai"))
        assertTrue(quiet("2026-11-01T05:30:00Z", "America/New_York")) // both folds of 01:30
        assertTrue(quiet("2026-11-01T06:30:00Z", "America/New_York"))
        assertFalse(quiet("2026-03-08T12:00:00Z", "America/New_York")) // DST 08:00
    }
    @Test @Config(sdk = [33]) fun deniedRuntimePermissionCannotPersistFakeEnabledState() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        org.robolectric.Shadows.shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notices = LocalFeedNotifications(context)
        val id = LOCAL_PREFIX + "denied-owned"
        assertFalse(notices.permissionGranted())
        try { notices.enable(id, true); fail("Denied permission") } catch (_: IllegalStateException) { }
        assertFalse(LocalFeedNotifications(context).enabled(id))
    }
    @Test fun pendingSummaryDeduplicatesActualIdsAndBoundsTitlesList() {
        val old = listOf(NotificationItem("id", "title"))
        assertEquals(old, mergeNotificationItems(old, old))
        assertEquals(200, mergeNotificationItems(old, (0..1000).map { NotificationItem("id-$it", "title") }).size)
        assertEquals(2, mergeNotificationItems(old, listOf(NotificationItem("new-id", "title"))).size)
    }
    @Test fun defaultOffFirstImportAndRepeatedRefreshDoNotNotifyButActualNewIdDoes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notices = LocalFeedNotifications(context)
        val db = Room.inMemoryDatabaseBuilder(context, DirectRssDatabase::class.java).allowMainThreadQueries().build()
        var key = "one"
        val client = DirectRssClient(readFeed = { "<rss><channel><title>Owned</title><item><guid>$key</guid><title>New $key</title><link>https://example.org/$key</link><description>text</description></item></channel></rss>".toByteArray() })
        try {
            val id = LOCAL_PREFIX + "notification-owned"
            val source = DirectSubscription(id, "Owned", "https://example.org/feed", false)
            db.dao().putSubscription(source)
            assertFalse(notices.enabled(id))
            // Delivery behavior is policy/Room coverage, not an actual OS/provider claim.
            val repo = DirectRssRepository(db, client, DirectImageCache(downloadAllowed = { false }), notifications = notices)
            notices.enable(id, true)
            repo.refresh(id)
            assertEquals("尚无新文章通知", notices.detail(id))
            repo.refresh(id)
            assertEquals("尚无新文章通知", notices.detail(id))
            key = "two"; repo.refresh(id)
            val daytime = Clock.fixed(Instant.parse("2026-10-09T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
            notices.release(db.dao().subscriptions(), daytime)
            assertTrue(notices.detail(id).contains("New two"))
            val detail = notices.detail(id)
            repo.refresh(id); notices.release(db.dao().subscriptions(), daytime)
            assertEquals(detail, notices.detail(id))
            notices.enable(id, false); assertFalse(notices.enabled(id))
        } finally { db.close() }
    }
}
