package com.fluxa.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingActionDao {
    @Insert suspend fun insert(action: PendingActionEntity)
    @Query("SELECT * FROM pending_actions ORDER BY id")
    suspend fun getAllOrdered(): List<PendingActionEntity>
    @Query("DELETE FROM pending_actions WHERE id = :id")
    suspend fun deleteById(id: Long)
    @Query("SELECT COUNT(*) FROM pending_actions WHERE actionType IN ('MarkRead', 'ToggleStar')")
    fun observeCount(): Flow<Int>
}
