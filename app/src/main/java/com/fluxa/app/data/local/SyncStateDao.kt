package com.fluxa.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE source = :source LIMIT 1")
    suspend fun getBySource(source: String): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)
}
