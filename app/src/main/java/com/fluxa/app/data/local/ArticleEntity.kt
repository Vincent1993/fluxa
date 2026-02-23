package com.fluxa.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val feedName: String,
    val publishedAtEpochSeconds: Long,
    val isRead: Boolean,
    val isStarred: Boolean,
    val contentHtml: String
)
