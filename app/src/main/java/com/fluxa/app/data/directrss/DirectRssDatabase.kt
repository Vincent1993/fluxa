package com.fluxa.app.data.directrss

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "folders", indices = [Index(value = ["name"], unique = true)])
data class DirectFolder(@PrimaryKey val id: String, val name: String)

@Entity(tableName = "subscriptions", indices = [Index("folderId")], foreignKeys = [ForeignKey(
    entity = DirectFolder::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.NO_ACTION
)])
data class DirectSubscription(
    @PrimaryKey val id: String, val title: String, val url: String, val dailyOnly: Boolean,
    val lastSuccess: Long = 0, val status: String = "尚未刷新",
    val alias: String? = null, val folderId: String? = null,
    @ColumnInfo(defaultValue = "0") val paused: Boolean = false,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false
)
@Entity(tableName = "articles", indices = [Index("source")])
data class DirectArticle(
    @PrimaryKey val id: String, val guid: String, val source: String, val title: String,
    val published: Long, val body: String, val originalUrl: String,
    val isRead: Boolean = false, val isStarred: Boolean = false,
    val cachedImages: Int = 0, val totalImages: Int = 0, val categories: String = "",
    val summary: String = "", val readMinutes: Int = 1,
    @ColumnInfo(defaultValue = "''") val author: String = "",
    @ColumnInfo(defaultValue = "0") val readLater: Boolean = false
)
enum class LocalReadingMode { RSS, FULL_TEXT, ORIGINAL }

@Entity(tableName = "reading_modes")
data class DirectReadingMode(@PrimaryKey val source: String, val mode: String)
@Entity(tableName = "full_bodies")
data class DirectFullBody(@PrimaryKey val articleId: String, val body: String)

@Entity(tableName = "reader_notes", indices = [Index("articleId")])
data class DirectReaderNote(
    @PrimaryKey val id: String, val articleId: String, val mode: String,
    val quote: String, val quoteHash: String, val occurrence: Int, val occurrences: Int, val note: String
)

@Entity(tableName = "local_revisions")
data class DirectRevision(@PrimaryKey val articleId: String, val revision: String)

data class DirectCacheEntry(val id: String, val source: String, val isRead: Boolean, val isStarred: Boolean, val readLater: Boolean, val bytes: Long)

@Dao
interface DirectRssDao {
    @Query("SELECT id FROM articles ORDER BY id") suspend fun backupArticleIds(): List<String>
    @Query("SELECT articleId FROM full_bodies ORDER BY articleId") suspend fun backupFullIds(): List<String>
    @Query("SELECT * FROM reader_notes ORDER BY id") suspend fun allNotes(): List<DirectReaderNote>
    @Query("SELECT * FROM reading_modes ORDER BY source") suspend fun allModes(): List<DirectReadingMode>
    @Query("SELECT * FROM full_bodies ORDER BY articleId") suspend fun allFullBodies(): List<DirectFullBody>
    @Query("SELECT * FROM reader_notes WHERE articleId = :id ORDER BY id") fun observeNotes(id: String): Flow<List<DirectReaderNote>>
    @Query("SELECT COUNT(*) FROM reader_notes") suspend fun noteCount(): Int
    @Query("SELECT COUNT(*) FROM reader_notes WHERE articleId = :id") suspend fun articleNoteCount(id: String): Int
    @Query("SELECT * FROM reader_notes WHERE id = :id") suspend fun note(id: String): DirectReaderNote?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putNote(note: DirectReaderNote)
    @Query("DELETE FROM reader_notes WHERE id = :id") suspend fun deleteNote(id: String)

    @Query("SELECT a.id, a.source, a.isRead, a.isStarred, a.readLater, length(CAST(a.body AS BLOB)) + COALESCE(length(CAST(f.body AS BLOB)), 0) AS bytes FROM articles a LEFT JOIN full_bodies f ON a.id = f.articleId")
    suspend fun cacheEntries(): List<DirectCacheEntry>
    @Query("SELECT COALESCE(SUM(length(CAST(body AS BLOB))), 0) FROM full_bodies WHERE articleId NOT IN (SELECT id FROM articles)")
    suspend fun orphanPayloadBytes(): Long
    @Query("SELECT * FROM local_revisions WHERE articleId = :id") suspend fun revision(id: String): DirectRevision?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRevision(value: DirectRevision)
    @Query("SELECT * FROM reading_modes WHERE source = :source") suspend fun mode(source: String): DirectReadingMode?
    @Query("UPDATE full_bodies SET body = :body WHERE articleId = :id") suspend fun updateFullBody(id: String, body: String)
    @Query("SELECT * FROM reading_modes WHERE source = :source") fun observeMode(source: String): Flow<DirectReadingMode?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMode(mode: DirectReadingMode)
    @Query("SELECT * FROM full_bodies WHERE articleId = :id") suspend fun fullBody(id: String): DirectFullBody?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun putFullBody(body: DirectFullBody)
    @Query("DELETE FROM full_bodies WHERE articleId = :id") suspend fun deleteFullBody(id: String)
    @Query("SELECT * FROM folders ORDER BY name") suspend fun folders(): List<DirectFolder>
    @Query("SELECT * FROM folders ORDER BY name") fun observeFolders(): Flow<List<DirectFolder>>
    @Query("SELECT * FROM folders WHERE id = :id") suspend fun folder(id: String): DirectFolder?
    @Query("SELECT * FROM folders WHERE name = :name") suspend fun folderNamed(name: String): DirectFolder?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun putFolder(folder: DirectFolder)
    @Query("SELECT * FROM subscriptions ORDER BY title") fun observeSubscriptions(): Flow<List<DirectSubscription>>
    @Query("SELECT * FROM articles ORDER BY published DESC, id") fun observeArticles(): Flow<List<DirectArticle>>
    @Query("SELECT * FROM subscriptions ORDER BY id") suspend fun subscriptions(): List<DirectSubscription>
    @Query("SELECT * FROM subscriptions WHERE id = :id") suspend fun subscription(id: String): DirectSubscription?
    @Query("SELECT * FROM articles WHERE id = :id") suspend fun article(id: String): DirectArticle?
    @Query("SELECT * FROM articles ORDER BY id") suspend fun articles(): List<DirectArticle>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSubscription(value: DirectSubscription)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putArticle(value: DirectArticle)
    @Query("UPDATE articles SET isRead = 1 WHERE id = :id") suspend fun markRead(id: String)
    @Query("UPDATE articles SET isRead = :read WHERE id = :id") suspend fun setReadState(id: String, read: Boolean)
    @Query("UPDATE articles SET readLater = :value WHERE id = :id") suspend fun setReadLater(id: String, value: Boolean)
    @Query("UPDATE articles SET isStarred = NOT isStarred WHERE id = :id") suspend fun toggleStar(id: String)
    @Query("UPDATE subscriptions SET status = :status WHERE id = :id") suspend fun status(id: String, status: String)
}
@Database(entities = [DirectSubscription::class, DirectArticle::class, DirectFolder::class, DirectReadingMode::class, DirectFullBody::class, DirectRevision::class, DirectReaderNote::class], version = 6, exportSchema = true)
abstract class DirectRssDatabase : RoomDatabase() {
    abstract fun dao(): DirectRssDao
    companion object {
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `articles` ADD COLUMN `readLater` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `reader_notes` (`id` TEXT NOT NULL, `articleId` TEXT NOT NULL, `mode` TEXT NOT NULL, `quote` TEXT NOT NULL, `quoteHash` TEXT NOT NULL, `occurrence` INTEGER NOT NULL, `occurrences` INTEGER NOT NULL, `note` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reader_notes_articleId` ON `reader_notes` (`articleId`)")
            }
        }
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `articles` ADD COLUMN `author` TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `local_revisions` (`articleId` TEXT NOT NULL, `revision` TEXT NOT NULL, PRIMARY KEY(`articleId`))")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `reading_modes` (`source` TEXT NOT NULL, `mode` TEXT NOT NULL, PRIMARY KEY(`source`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `full_bodies` (`articleId` TEXT NOT NULL, `body` TEXT NOT NULL, PRIMARY KEY(`articleId`))")
            }
        }
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `folders` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_folders_name` ON `folders` (`name`)")
                db.execSQL("ALTER TABLE `subscriptions` ADD COLUMN `alias` TEXT")
                db.execSQL("ALTER TABLE `subscriptions` ADD COLUMN `folderId` TEXT REFERENCES `folders` (`id`) ON UPDATE NO ACTION ON DELETE NO ACTION")
                db.execSQL("ALTER TABLE `subscriptions` ADD COLUMN `paused` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `subscriptions` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscriptions_folderId` ON `subscriptions` (`folderId`)")
            }
        }
    }
}
