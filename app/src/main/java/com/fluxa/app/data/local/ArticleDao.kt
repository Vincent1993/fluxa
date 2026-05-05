package com.fluxa.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles ORDER BY publishedAtEpochSeconds DESC")
    fun observeAll(): Flow<List<ArticleEntity>>

    @Query(
        """
        SELECT * FROM articles
        WHERE (:isRead IS NULL OR isRead = :isRead)
          AND (:isStarred IS NULL OR isStarred = :isStarred)
          AND (:source IS NULL OR source = :source)
          AND (:tag IS NULL OR tags LIKE '%' || :tag || '%')
          AND (:startDateEpochSeconds IS NULL OR publishedAtEpochSeconds >= :startDateEpochSeconds)
          AND (:endDateEpochSeconds IS NULL OR publishedAtEpochSeconds <= :endDateEpochSeconds)
          AND (
            :searchQuery = '' OR
            title LIKE '%' || :searchQuery || '%' OR
            contentHtml LIKE '%' || :searchQuery || '%' OR
            feedName LIKE '%' || :searchQuery || '%' OR
            tags LIKE '%' || :searchQuery || '%'
          )
        ORDER BY publishedAtEpochSeconds DESC
        """
    )
    fun observeFiltered(
        isRead: Boolean?,
        isStarred: Boolean?,
        source: String?,
        tag: String?,
        startDateEpochSeconds: Long?,
        endDateEpochSeconds: Long?,
        searchQuery: String
    ): Flow<List<ArticleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ArticleEntity>)

    @Query("UPDATE articles SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE articles SET isStarred = :starred WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean)

    @Query("SELECT isStarred FROM articles WHERE id = :id")
    suspend fun getStarred(id: String): Boolean?

    @Query("DELETE FROM articles")
    suspend fun clearAll()
}
