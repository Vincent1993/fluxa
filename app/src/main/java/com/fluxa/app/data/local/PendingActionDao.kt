package com.fluxa.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingActionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PendingActionEntity)

    @Query("SELECT * FROM pending_actions ORDER BY createdAtEpochMillis ASC")
    suspend fun getAllOrdered(): List<PendingActionEntity>

    @Query("DELETE FROM pending_actions WHERE id = :id")
    suspend fun deleteById(id: Long)
}
