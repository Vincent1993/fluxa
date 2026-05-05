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

    @Query(
        "UPDATE articles SET readingProgress = :progress, lastOpenedAtEpochSeconds = :lastOpenedAtEpochSeconds WHERE id = :id"
    )
    suspend fun updateReadingProgress(id: String, progress: Float, lastOpenedAtEpochSeconds: Long)

    @Query("UPDATE articles SET archivedAtEpochSeconds = :archivedAtEpochSeconds WHERE id = :id")
    suspend fun setArchivedAt(id: String, archivedAtEpochSeconds: Long?)

    @Query("UPDATE articles SET savedAtEpochSeconds = :savedAtEpochSeconds WHERE id = :id")
    suspend fun setSavedAt(id: String, savedAtEpochSeconds: Long?)

    @Query("SELECT isStarred FROM articles WHERE id = :id")
    suspend fun getStarred(id: String): Boolean?

    @Query("DELETE FROM articles")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHighlights(items: List<HighlightEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTags(items: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArticleTagCrossRefs(items: List<ArticleTagCrossRef>)

    @Query("DELETE FROM highlights WHERE articleId = :articleId")
    suspend fun deleteHighlightsByArticleId(articleId: String)

    @Query("DELETE FROM article_tag_cross_ref WHERE articleId = :articleId")
    suspend fun deleteTagRefsByArticleId(articleId: String)

    @Transaction
    suspend fun replaceArticleTags(articleId: String, refs: List<ArticleTagCrossRef>) {
        deleteTagRefsByArticleId(articleId)
        upsertArticleTagCrossRefs(refs)
    }
}
