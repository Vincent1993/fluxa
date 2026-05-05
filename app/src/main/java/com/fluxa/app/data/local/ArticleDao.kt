package com.fluxa.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles ORDER BY publishedAtEpochSeconds DESC")
    fun observeAll(): Flow<List<ArticleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ArticleEntity>)

    @Query("UPDATE articles SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE articles SET isStarred = :starred WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean)

    @Query("SELECT isStarred FROM articles WHERE id = :id")
    suspend fun getStarred(id: String): Boolean?

    @Query("SELECT * FROM articles WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<ArticleEntity>

    @Transaction
    suspend fun upsertPreservingLocalFields(remoteItems: List<ArticleEntity>) {
        if (remoteItems.isEmpty()) return
        val localMap = getByIds(remoteItems.map { it.id }).associateBy { it.id }
        val merged = remoteItems.map { remote ->
            val local = localMap[remote.id]
            if (local == null) remote else remote.copy(
                isRead = local.isRead,
                isStarred = local.isStarred
            )
        }
        upsertAll(merged)
    }
}
