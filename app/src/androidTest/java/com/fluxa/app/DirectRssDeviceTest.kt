package com.fluxa.app

import android.content.Context
import android.os.Process
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxa.app.data.directrss.*
import com.fluxa.app.di.DirectRssAccess
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** No arguments: skips before resolving Hilt, opening databases, or making requests.
 * directIfanrReal=1 directRssPhase=subscribe: real official feed only; retains all records.
 * directRssPhase=verify: independent-process, offline-only verification of the saved evidence.
 * This is repository evidence, NOT a replacement for root's actual UI/screenshot acceptance.
 */
class DirectRssDeviceTest {
    @Test fun optedInOfficialFeedAndOfflinePersistence(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("directRssPhase")
        assumeTrue("Opt in to subscribe or offline verify", phase in listOf("subscribe", "verify"))
        if (phase == "subscribe") assumeTrue("Real network requires directIfanrReal=1", args.getString("directIfanrReal") == "1")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entry = EntryPointAccessors.fromApplication(context, DirectRssAccess::class.java)
        val dao = entry.directDatabase().dao()
        val marker = File(context.filesDir, "direct-rss-ifanr-evidence.json")
        val beforeHash = remoteHash(entry)
        if (phase == "subscribe") {
            check(!marker.exists()) { "Archive existing evidence before another subscribe phase; never overwrite a baseline" }
            val baseline = JSONObject().put("owner", "DirectRssDeviceTest/v1")
                .put("pid", Process.myPid()).put("processStart", Process.getStartElapsedRealtime())
                .put("newsBlurHash", beforeHash).put("hasSession", entry.authRepository().hasSession())
            atomic(marker, baseline)
            val repo = entry.directRepository()
            repo.addSubscription(IFANR_FEED, true)
            val feed = dao.subscriptions().single { it.dailyOnly && it.url == IFANR_FEED }
            val rows = dao.articles().filter { it.source == feed.id }
            check(rows.isNotEmpty())
            check(rows.all { "早报" in it.categories.split('\n') || (it.categories.isEmpty() && Regex("^早报(?:[｜|丨:：/\\s\\p{Z}]|$)").containsMatchIn(it.title)) })
            val latest = rows.maxBy { it.published }
            check(latest.published > 0 && latest.originalUrl.startsWith("https://www.ifanr.com/"))
            args.getString("directIfanrLatestLink")?.let { expected ->
                check(latest.originalUrl.substringBefore('?') == expected.substringBefore('?')) { "Latest source link differs from root's probe" }
            }
            check(Regex("<h[1-6](?:>|\\s)").findAll(latest.body).count() >= 2)
            check(Regex("<img\\b").findAll(latest.body).count() > 1)
            check(latest.totalImages > 1 && latest.cachedImages in 0..latest.totalImages)
            repo.markRead(latest.id)
            if (!dao.article(latest.id)!!.isStarred) repo.toggleStar(latest.id)
            // Idempotent shortcut plus explicit refresh: flags and existing cached images survive both.
            repo.addSubscription(IFANR_FEED, true)
            repo.refresh(feed.id)
            val after = dao.articles().filter { it.source == feed.id }
            check(after.map { it.id }.distinct().size == after.size)
            check(after.map { it.guid }.distinct().size == after.size)
            check(dao.subscriptions().count { it.id == feed.id } == 1)
            val saved = dao.article(latest.id)!!
            check(saved.isRead && saved.isStarred)
            check(remoteHash(entry) == beforeHash) { "NewsBlur records/pending changed" }
            atomic(marker, baseline.put("complete", true).put("id", saved.id).put("source", feed.id)
                .put("title", saved.title).put("link", saved.originalUrl).put("bodyHash", stableHash(saved.body))
                .put("cachedImages", saved.cachedImages).put("totalImages", saved.totalImages)
                .put("count", after.size).put("lastSuccess", dao.subscription(feed.id)!!.lastSuccess))
        } else {
            check(marker.exists())
            val saved = JSONObject(marker.readText())
            check(saved.getString("owner") == "DirectRssDeviceTest/v1" && saved.getBoolean("complete"))
            check(saved.getInt("pid") != Process.myPid() || saved.getLong("processStart") != Process.getStartElapsedRealtime()) { "Verify must run in a separate process" }
            check(saved.getString("newsBlurHash") == beforeHash)
            val row = checkNotNull(dao.article(saved.getString("id")))
            check(row.isRead && row.isStarred)
            check(row.title == saved.getString("title") && row.originalUrl == saved.getString("link"))
            check(stableHash(row.body) == saved.getString("bodyHash"))
            check(row.cachedImages == saved.getInt("cachedImages") && row.totalImages == saved.getInt("totalImages"))
            val rows = dao.articles().filter { it.source == row.source }
            check(rows.size == saved.getInt("count") && rows.distinctBy { it.guid }.size == rows.size)
            check(dao.subscription(row.source)!!.lastSuccess == saved.getLong("lastSuccess"))
            check(entry.authRepository().hasSession() == saved.getBoolean("hasSession"))
        }
    }
    private suspend fun remoteHash(entry: DirectRssAccess): String {
        val db = entry.newsBlurDatabase()
        val pending = db.pendingActionDao().getAllOrdered().toString()
        val records = buildString {
            // Includes every pre-existing NewsBlur table, without logging any row or secret.
            val sql = db.openHelper.readableDatabase
            val tables = mutableListOf<String>()
            sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            for (table in tables) {
                append(table)
                val rows = mutableListOf<String>()
                sql.query("SELECT * FROM `" + table.replace("`", "``") + "`").use { cursor ->
                    while (cursor.moveToNext()) rows += (0 until cursor.columnCount).joinToString("|") { index ->
                        if (cursor.isNull(index)) "null" else stableHash(cursor.getString(index))
                    }
                }
                append(rows.sorted().joinToString("\n"))
            }
        }
        return stableHash(pending + records)
    }
    private fun atomic(file: File, value: JSONObject) {
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(value.toString(2).toByteArray()); atomic.finishWrite(stream) }
        catch (e: Throwable) { atomic.failWrite(stream); throw e }
    }
}
