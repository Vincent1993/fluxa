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
class DirectV3Stage2MigrationTest {
    @Test fun actualV3SchemaKeepsBodyModesFullTextAndFlagsWithAppendOnlyRevisionTable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "owned-v3-${UUID.randomUUID()}.db"
        val path = "schemas/com.fluxa.app.data.directrss.DirectRssDatabase/3.json"
        val schema = JSONObject(sequenceOf(File(path), File("app/$path")).first { it.exists() }.readText()).getJSONObject("database")
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sql ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
                    sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    entity.optJSONArray("indices")?.let { indices ->
                        for (j in 0 until indices.length()) sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
                sql.execSQL("INSERT INTO articles VALUES ('owned','guid','source','Title',123,'<p>RSS 汉字</p>','https://example.org/a',1,1,0,0,'category','summary',4)")
                sql.execSQL("INSERT INTO reading_modes VALUES ('source','FULL_TEXT')")
                sql.execSQL("INSERT INTO full_bodies VALUES ('owned','<p>Full body</p>')")
                sql.version = 3
            }
            fun open() = Room.databaseBuilder(context, DirectRssDatabase::class.java, name)
                .addMigrations(DirectRssDatabase.MIGRATION_1_2, DirectRssDatabase.MIGRATION_2_3, DirectRssDatabase.MIGRATION_3_4, DirectRssDatabase.MIGRATION_4_5, DirectRssDatabase.MIGRATION_5_6)
                .allowMainThreadQueries().build()
            open().let { db -> try {
                assertEquals("<p>RSS 汉字</p>", db.dao().article("owned")!!.body)
                assertTrue(db.dao().article("owned")!!.isRead); assertTrue(db.dao().article("owned")!!.isStarred)
                assertEquals("<p>Full body</p>", db.dao().fullBody("owned")!!.body)
                assertEquals("FULL_TEXT", db.dao().mode("source")!!.mode)
                assertNull(db.dao().revision("owned"))
                db.dao().putRevision(DirectRevision("owned", "owned-version"))
            } finally { db.close() } }
            open().let { db -> try { assertEquals("owned-version", db.dao().revision("owned")!!.revision) } finally { db.close() } }
        } finally { context.deleteDatabase(name) }
    }
}
