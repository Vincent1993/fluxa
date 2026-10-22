package com.fluxa.app.data.directrss

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.fluxa.app.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Clock
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

internal fun notificationQuiet(clock: Clock): Boolean {
    val time = LocalTime.now(clock)
    return time >= LocalTime.of(22, 0) || time < LocalTime.of(8, 0)
}
internal data class NotificationItem(val id: String, val title: String)
internal fun mergeNotificationItems(old: List<NotificationItem>, new: List<NotificationItem>) =
    (old + new).distinctBy { it.id }.take(200)

/** Only anonymous local success paths call this. No alarm, authentication or first-import notification. */
@Singleton
class LocalFeedNotifications @Inject constructor(@ApplicationContext private val context: Context) {
    companion object {
        const val CHANNEL = "local_rss_summary_v1"
        const val ACTION_SOURCE = "com.fluxa.app.OPEN_LOCAL_SOURCE"
        const val EXTRA_SOURCE = "local_source"
    }
    private val prefs = context.getSharedPreferences("local_feed_notifications_v1", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun permissionGranted() = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun channelEnabled() = manager.areNotificationsEnabled() && (manager.getNotificationChannel(CHANNEL)?.importance ?: NotificationManager.IMPORTANCE_DEFAULT) != NotificationManager.IMPORTANCE_NONE
    fun ensureChannel() {
        // The OS/user owns channel importance after first creation; never delete/recreate to override it.
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "本地 RSS 来源摘要", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "仅明确启用的公开订阅的新文章摘要；22:00–08:00 安静时段"
        })
    }
    @Synchronized fun enabled(source: String) = prefs.getBoolean("enabled:$source", false)
    @Synchronized fun enable(source: String, enabled: Boolean) {
        require(source.startsWith(LOCAL_PREFIX) && source != SAVED_PAGE_SOURCE && source.length <= 200)
        if (enabled) { check(permissionGranted()) { "系统通知权限未授予" }; ensureChannel() }
        val sources = prefs.getStringSet("sources", emptySet()).orEmpty().toMutableSet()
        if (enabled) { require(source in sources || sources.size < 100) { "最多启用 100 个来源通知" }; sources += source } else sources -= source
        val editor = prefs.edit().putBoolean("enabled:$source", enabled).putStringSet("sources", sources)
        if (!enabled) editor.remove("pending:$source")
        check(editor.commit())
        if (!enabled) manager.cancel(source, 1)
    }
    @Synchronized fun detail(source: String): String {
        val waiting = pending(source)
        return if (waiting.isEmpty()) prefs.getString("detail:$source", "尚无新文章通知").orEmpty()
        else "待发送摘要 ${waiting.size} 篇（最多 200 篇）\n" + waiting.take(5).joinToString("\n") { it.title }
    }
    private fun pending(source: String): List<NotificationItem> = runCatching {
        val raw = prefs.getString("pending:$source", "[]").orEmpty()
        require(raw.length <= 100_000)
        val array = JSONArray(raw)
        (0 until minOf(array.length(), 200)).map { array.getJSONObject(it).let { item -> NotificationItem(item.getString("id"), item.getString("title")) } }
    }.getOrDefault(emptyList())
    @Synchronized internal fun successful(feed: DirectSubscription, new: List<DirectArticle>, eligible: List<DirectSubscription>) {
        if (feed.lastSuccess > 0 && !feed.paused && !feed.archived && enabled(feed.id)) {
            val addition = new.filter { it.id.length <= 200 }.take(200).map { NotificationItem(it.id, it.title.take(120)) }
            val merged = mergeNotificationItems(pending(feed.id), addition)
            if (merged.isNotEmpty()) {
                val array = JSONArray().apply { merged.forEach { put(JSONObject().put("id", it.id).put("title", it.title)) } }
                check(prefs.edit().putString("pending:${feed.id}", array.toString()).commit())
            }
        }
        release(eligible)
    }
    @Synchronized fun release(eligible: List<DirectSubscription>, clock: Clock = Clock.systemDefaultZone()) {
        if (notificationQuiet(clock) || !permissionGranted() || !channelEnabled()) return
        ensureChannel()
        for (feed in eligible.filter { !it.archived && !it.paused && it.id != SAVED_PAGE_SOURCE }.take(100)) {
            if (!enabled(feed.id)) continue
            val pending = pending(feed.id)
            if (pending.isEmpty()) continue
            val titles = pending.take(5).joinToString("\n") { it.title }
            val summary = "${pending.size} 篇新文章${if (pending.size == 200) "（摘要上限 200 篇）" else ""}"
            val intent = Intent(context, MainActivity::class.java).setAction(ACTION_SOURCE)
                .setData(Uri.Builder().scheme("fluxa").authority("local-source").appendPath(feed.id).build())
                .putExtra(EXTRA_SOURCE, feed.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val click = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle(feed.alias ?: feed.title).setContentText(summary)
                .setStyle(Notification.BigTextStyle().bigText("$summary\n$titles"))
                .setCategory(Notification.CATEGORY_SOCIAL).setOnlyAlertOnce(true).setAutoCancel(true).setContentIntent(click).build()
            // Commit before emission: a process death may lose a best-effort notification,
            // but cannot replay it on every refresh/retry. No exactly-once delivery claim.
            check(prefs.edit().remove("pending:${feed.id}").putString("detail:${feed.id}", "$summary\n$titles").commit())
            try { manager.notify(feed.id, 1, notification) } catch (_: SecurityException) { /* OS permission can be revoked between checks. */ }
        }
    }
}
