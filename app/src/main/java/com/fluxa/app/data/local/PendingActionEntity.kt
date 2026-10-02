package com.fluxa.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_actions")
data class PendingActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val articleId: String,
    val actionType: String,
    val payload: String = "",
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)
