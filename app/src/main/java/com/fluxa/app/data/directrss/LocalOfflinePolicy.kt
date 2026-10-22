package com.fluxa.app.data.directrss

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Local RSS only. Never reads account/reader preferences. Unknown read ages remain protected. */
data class LocalOfflinePolicy(
    val capacityMiB: Int = 256,
    val imagesEnabled: Boolean = true,
    val imagesWifiOnly: Boolean = true,
    val retainedReadDays: Int = 30,
    val backgroundEnabled: Boolean = true
) {
    init {
        require(capacityMiB in listOf(64, 128, 256, 512))
        require(retainedReadDays in listOf(7, 30, 90))
    }
    val capacityBytes: Long get() = capacityMiB * 1024L * 1024L
}

@Singleton
class LocalOfflineSettings @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("direct_rss_offline", Context.MODE_PRIVATE)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    @Synchronized fun policy() = LocalOfflinePolicy(
        preferences.getInt("capacity", 256).takeIf { it in listOf(64, 128, 256, 512) } ?: 256,
        preferences.getBoolean("images", true), preferences.getBoolean("wifi", true),
        preferences.getInt("days", 30).takeIf { it in listOf(7, 30, 90) } ?: 30,
        preferences.getBoolean("background", true))
    @Synchronized fun update(value: LocalOfflinePolicy) {
        check(preferences.edit().putInt("capacity", value.capacityMiB).putBoolean("images", value.imagesEnabled)
            .putBoolean("wifi", value.imagesWifiOnly).putInt("days", value.retainedReadDays)
            .putBoolean("background", value.backgroundEnabled).commit())
    }
    fun canDownloadImages(): Boolean {
        val policy = policy()
        if (!policy.imagesEnabled) return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (!policy.imagesWifiOnly || (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !connectivity.isActiveNetworkMetered))
    }
    @Synchronized fun automaticRetryAllowed(id: String) = !preferences.getBoolean("invalid:$id", false)
    @Synchronized fun recordFailure(id: String, permanent: Boolean) {
        check(preferences.edit().putBoolean("invalid:$id", permanent).commit())
    }
    @Synchronized fun mergeBackupPins(articles: Set<String>, folders: Set<String>, policy: LocalOfflinePolicy) {
        require(articles.size <= 5000 && folders.size <= 500)
        val editor = preferences.edit()
        if (!preferences.contains("capacity")) editor.putInt("capacity", policy.capacityMiB)
        if (!preferences.contains("images")) editor.putBoolean("images", policy.imagesEnabled)
        if (!preferences.contains("wifi")) editor.putBoolean("wifi", policy.imagesWifiOnly)
        if (!preferences.contains("days")) editor.putInt("days", policy.retainedReadDays)
        // Background scheduling and notification opt-ins are never enabled by import.
        articles.forEach { if (!preferences.contains("article:$it")) editor.putBoolean("article:$it", true) }
        folders.forEach { if (!preferences.contains("folder:$it")) editor.putBoolean("folder:$it", true) }
        check(editor.commit())
    }
    @Synchronized fun articlePinned(id: String) = preferences.getBoolean("article:$id", false)
    @Synchronized fun folderPinned(id: String?) = id != null && preferences.getBoolean("folder:$id", false)
    @Synchronized fun pinArticle(id: String, pinned: Boolean) { check(preferences.edit().putBoolean("article:$id", pinned).commit()) }
    @Synchronized fun pinFolder(id: String, pinned: Boolean) { check(preferences.edit().putBoolean("folder:$id", pinned).commit()) }
    @Synchronized fun readAt(id: String): Long = preferences.getLong("read:$id", 0)
    @Synchronized fun recordRead(id: String, read: Boolean, now: Long) {
        check(preferences.edit().putLong("read:$id", if (read) now else 0).commit())
    }
}

data class OfflineUsage(val payloadBytes: Long, val limitBytes: Long, val protectedBytes: Long) {
    val excessBytes: Long get() = (payloadBytes - limitBytes).coerceAtLeast(0)
}

internal fun payloadBytes(body: String, fullBody: String = "") =
    body.toByteArray(Charsets.UTF_8).size.toLong() + fullBody.toByteArray(Charsets.UTF_8).size

/** Remove only embedded payload, restoring its original URL when available; retain alt and prose. */
internal fun withoutEmbeddedImages(body: String): String = Regex("<img\\b[^>]*>").replace(body) { match ->
    val tag = match.value
    val src = Regex("src=\"([^\"]*)\"")
    if (src.find(tag)?.groupValues?.get(1)?.startsWith("data:image/") != true) tag
    else {
        val original = Regex(" data-original=\"([^\"]*)\"")
        val url = original.find(tag)?.groupValues?.get(1).orEmpty()
        src.replace(original.replace(tag, "")) { "src=\"$url\"" }
    }
}
