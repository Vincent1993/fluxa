package com.fluxa.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingActionDao {
    @Insert suspend fun insert(action: PendingActionEntity)
    @Query("SELECT payload FROM pending_actions WHERE articleId = :id AND actionType = 'LocalRevision' ORDER BY id DESC LIMIT 1")
    suspend fun revision(id: String): String?
    @Query("SELECT * FROM pending_actions ORDER BY id")
    suspend fun getAllOrdered(): List<PendingActionEntity>
    @Query("DELETE FROM pending_actions WHERE id = :id")
    suspend fun deleteById(id: Long)
    @Query("DELETE FROM pending_actions WHERE articleId = :articleId AND actionType IN (:types)")
    suspend fun deleteField(articleId: String, types: List<String>)
    @Query("UPDATE pending_actions SET actionType = 'AckRead', payload = 'true' WHERE id = :id")
    suspend fun acknowledgeRead(id: Long)
    @Query("UPDATE pending_actions SET actionType = :type WHERE id = :id")
    suspend fun acknowledge(id: Long, type: String)
    @Query("SELECT COUNT(*) FROM pending_actions WHERE actionType IN ('AckRead', 'AckStar')")
    fun observeAwaitingCount(): Flow<Int>
    @Query("SELECT * FROM pending_actions WHERE actionType IN ('SyncErrorRead', 'SyncErrorStar') ORDER BY id")
    fun observeErrors(): Flow<List<PendingActionEntity>>
    @Query("SELECT COUNT(*) FROM pending_actions WHERE actionType IN ('MarkRead', 'ToggleStar', 'SetRead', 'RejectedRead', 'RejectedStar')")
    fun observeCount(): Flow<Int>
}
