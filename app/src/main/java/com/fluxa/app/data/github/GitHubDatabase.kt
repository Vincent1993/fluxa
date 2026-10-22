package com.fluxa.app.data.github

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "repositories", primaryKeys = ["profile", "id"])
data class GitHubRepo(
    val profile: String, val id: Long, val name: String,
    val description: String = "", val topics: String = "", val language: String = "",
    val url: String = "", val stars: Int = 0,
    // null means automatic; the empty string is an intentional manual override.
    val manualTags: String? = null
)
@Entity(tableName = "profiles")
data class GitHubProfile(@PrimaryKey val username: String, val cursor: String? = null,
    val visited: String = "", val pages: Int = 0, val status: String = "尚未导入")
@Entity(tableName = "settings")
data class GitHubSettings(@PrimaryKey val id: Int = 1, val active: String = "vincent1993", val cooldownUntil: Long = 0)
@Entity(tableName = "daily", primaryKeys = ["profile", "date"])
data class GitHubDaily(val profile: String, val date: String, val batch: Int, val ids: String)
@Entity(tableName = "history", primaryKeys = ["profile", "date", "repoId"])
data class GitHubHistory(val profile: String, val date: String, val repoId: Long)

@Dao
interface GitHubDao {
    @Query("SELECT * FROM repositories WHERE profile = :profile ORDER BY name, id") fun observeRepos(profile: String): Flow<List<GitHubRepo>>
    @Query("SELECT * FROM repositories WHERE profile = :profile ORDER BY name, id") suspend fun repos(profile: String): List<GitHubRepo>
    @Query("SELECT * FROM repositories WHERE profile = :profile AND id = :id") suspend fun repo(profile: String, id: Long): GitHubRepo?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRepo(repo: GitHubRepo)
    @Query("UPDATE repositories SET manualTags = :tags WHERE profile = :profile AND id = :id") suspend fun tags(profile: String, id: Long, tags: String?)
    @Query("SELECT * FROM profiles WHERE username = :profile") suspend fun profile(profile: String): GitHubProfile?
    @Query("SELECT * FROM profiles WHERE username = :profile") fun observeProfile(profile: String): Flow<GitHubProfile?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProfile(profile: GitHubProfile)
    @Query("SELECT * FROM settings WHERE id = 1") suspend fun settings(): GitHubSettings?
    @Query("SELECT * FROM settings WHERE id = 1") fun observeSettings(): Flow<GitHubSettings?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSettings(settings: GitHubSettings)
    @Query("SELECT * FROM daily WHERE profile = :profile AND date = :date") suspend fun daily(profile: String, date: String): GitHubDaily?
    @Query("SELECT * FROM daily WHERE profile = :profile ORDER BY date DESC") fun observeDaily(profile: String): Flow<List<GitHubDaily>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDaily(daily: GitHubDaily)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun putHistory(history: GitHubHistory)
    @Query("SELECT repoId FROM history WHERE profile = :profile AND date >= :since AND date <= :until") suspend fun recent(profile: String, since: String, until: String): List<Long>
    @Query("DELETE FROM repositories WHERE profile = :profile") suspend fun deleteRepos(profile: String)
    @Query("DELETE FROM profiles WHERE username = :profile") suspend fun deleteProfile(profile: String)
    @Query("DELETE FROM daily WHERE profile = :profile") suspend fun deleteDaily(profile: String)
    @Query("DELETE FROM history WHERE profile = :profile") suspend fun deleteHistory(profile: String)
}

@Database(entities = [GitHubRepo::class, GitHubProfile::class, GitHubSettings::class, GitHubDaily::class, GitHubHistory::class], version = 1, exportSchema = true)
abstract class GitHubDatabase : RoomDatabase() { abstract fun dao(): GitHubDao }
