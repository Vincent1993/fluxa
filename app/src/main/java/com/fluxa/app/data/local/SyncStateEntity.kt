package com.fluxa.app.data.local

import androidx.room.*

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val source: String,
    val cursor: String?,
    val lastSyncAtEpochMillis: Long,
    val syncToken: String? = null
)

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE source = :source")
    suspend fun getBySource(source: String): SyncStateEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)
}
