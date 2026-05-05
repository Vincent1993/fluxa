package com.fluxa.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val source: String,
    val cursor: String?,
    val lastSyncAtEpochMillis: Long,
    val syncToken: String?
)
