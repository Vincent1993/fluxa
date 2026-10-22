package com.fluxa.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.directrss.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DirectV4Stage3MigrationTest {
    @Test fun v4AppendAuthorKeepsHistoricalRowsCategoriesFlagsFolderModeBodyAndRevisionAcrossReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "owned-v4-${UUID.randomUUID()}.db"
        val path = "schemas/com.fluxa.app.data.directrss.DirectRssDatabase/4.json"
        val schema = JSONObject(sequenceOf(File(path), File("app/$path")).first { it.exists() }.readText()).getJSONObject("database")
        val pins = LocalOfflineSettings(context)
        pins.pinArticle("owned-stage3", true); pins.pinFolder("owned-folder-stage3", true)
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sql ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
                    sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    entity.optJSONArray("indices")?.let { indices -> for (j in 0 until indices.length())
                        sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
                sql.execSQL("INSERT INTO folders VALUES ('owned-folder-stage3','Folder')")
                sql.execSQL("INSERT INTO subscriptions (id,title,url,dailyOnly,lastSuccess,status,alias,folderId,paused,archived) VALUES ('source','Publisher','https://example.org/feed',1,123,'ok','Alias','owned-folder-stage3',1,1)")
                sql.execSQL("INSERT INTO articles VALUES ('owned-stage3','guid','source','Title',123,'<p>RSS 汉字</p>','https://example.org/a',1,1,0,0,'早报','summary',4)")
                sql.execSQL("INSERT INTO reading_modes VALUES ('source','FULL_TEXT')")
                sql.execSQL("INSERT INTO full_bodies VALUES ('owned-stage3','<p>Unchanged full</p>')")
                sql.execSQL("INSERT INTO local_revisions VALUES ('owned-stage3','unchanged-revision')")
                sql.version = 4
            }
            fun open() = Room.databaseBuilder(context, DirectRssDatabase::class.java, name)
                .addMigrations(DirectRssDatabase.MIGRATION_1_2, DirectRssDatabase.MIGRATION_2_3, DirectRssDatabase.MIGRATION_3_4, DirectRssDatabase.MIGRATION_4_5, DirectRssDatabase.MIGRATION_5_6)
                .allowMainThreadQueries().build()
            repeat(2) {
                val db = open()
                try {
                    val row = db.dao().article("owned-stage3")!!
                    assertEquals("", row.author); assertEquals("早报", row.categories)
                    assertEquals("<p>RSS 汉字</p>", row.body); assertTrue(row.isRead && row.isStarred)
                    assertEquals("<p>Unchanged full</p>", db.dao().fullBody(row.id)!!.body)
                    assertEquals("FULL_TEXT", db.dao().mode("source")!!.mode)
                    assertEquals("unchanged-revision", db.dao().revision(row.id)!!.revision)
                    val source = db.dao().subscription("source")!!
                    assertEquals("Alias", source.alias); assertEquals("owned-folder-stage3", source.folderId)
                    assertTrue(source.paused && source.archived)
                    assertTrue(LocalOfflineSettings(context).articlePinned(row.id)); assertTrue(LocalOfflineSettings(context).folderPinned(source.folderId))
                    assertEquals(1, db.dao().articles().size)
                } finally { db.close() }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun pinAndUnpinArePersistentAndUnpinDoesNotRemoveBody() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = LocalOfflineSettings(context)
        val db = Room.inMemoryDatabaseBuilder(context, DirectRssDatabase::class.java).allowMainThreadQueries().build()
        try {
            val row = DirectArticle("direct-rss:stage3-pin", "guid", "source", "Title", 1, "<p>Keep all prose</p>", "https://example.org/a")
            db.dao().putFolder(DirectFolder("stage3-folder", "Owned"))
            db.dao().putSubscription(DirectSubscription("source", "Source", "https://example.org/feed", false, folderId = "stage3-folder"))
            db.dao().putArticle(row)
            val repo = DirectRssRepository(db, images = DirectImageCache(downloadAllowed = { false }), offline = settings)
            repo.pinArticle(row.id, true); repo.pinFolder("stage3-folder", true)
            assertTrue(LocalOfflineSettings(context).articlePinned(row.id)); assertTrue(LocalOfflineSettings(context).folderPinned("stage3-folder"))
            repo.pinArticle(row.id, false); repo.pinFolder("stage3-folder", false)
            assertFalse(LocalOfflineSettings(context).articlePinned(row.id)); assertFalse(LocalOfflineSettings(context).folderPinned("stage3-folder"))
            assertEquals(row.body, db.dao().article(row.id)!!.body)
        } finally { db.close() }
    }

    @Test fun boundedRssAndAtomMetadataPreservesCategoryFirstDailyPolicy() {
        fun parse(xml: String) = DirectRssClient(readFeed = { xml.toByteArray() }).fetch("https://example.org/feed").items.single()
        val rss = parse("<rss xmlns:dc='http://purl.org/dc/elements/1.1/'><channel><title>R</title><item><title>Other title</title><category>早报</category><dc:creator>Editor &amp; Team</dc:creator><description>Body</description></item></channel></rss>")
        assertEquals("Editor & Team", rss.author); assertTrue(isDaily(rss))
        val atom = parse("<feed xmlns='http://www.w3.org/2005/Atom'><title>A</title><entry><id>1</id><title>早报 | title</title><category term='Other'/><author><name>" + "A".repeat(600) + "</name></author><content>Body</content></entry></feed>")
        assertEquals(512, atom.author.length); assertEquals(listOf("Other"), atom.categories); assertFalse(isDaily(atom))
    }
}
