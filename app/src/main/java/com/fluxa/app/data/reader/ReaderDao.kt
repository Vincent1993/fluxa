package com.fluxa.app.data.reader

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ReaderDao {
    @Query("SELECT * FROM reader_metadata") fun observeMetadata(): Flow<List<ReaderMetadata>>
    @Query("SELECT * FROM reader_metadata WHERE articleId=:id") suspend fun metadata(id: String): ReaderMetadata?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMetadata(item: ReaderMetadata)
    @Query("SELECT * FROM reader_annotations WHERE articleId=:id ORDER BY createdAtEpochSeconds, id")
    fun observeAnnotations(id: String): Flow<List<ReaderAnnotation>>
    @Query("SELECT * FROM reader_annotations ORDER BY createdAtEpochSeconds, id") fun observeAllAnnotations(): Flow<List<ReaderAnnotation>>
    @Query("SELECT * FROM reader_annotations WHERE id=:id") suspend fun annotation(id: String): ReaderAnnotation?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAnnotation(item: ReaderAnnotation)
    @Query("DELETE FROM reader_annotations WHERE id=:id") suspend fun deleteAnnotation(id: String)
    @Query("SELECT * FROM reader_tags") fun observeTags(): Flow<List<ReaderTag>>
    @Query("SELECT * FROM reader_article_tags") fun observeLinks(): Flow<List<ReaderArticleTag>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun putTag(item: ReaderTag)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun putLink(item: ReaderArticleTag)
    @Query("DELETE FROM reader_article_tags WHERE articleId=:id") suspend fun clearLinks(id: String)
}
