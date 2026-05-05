package com.fluxa.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val url: String,
    val author: String,
    val excerpt: String,
    val feedName: String,
    val publishedAtEpochSeconds: Long,
    val isRead: Boolean,
    val isStarred: Boolean,
    val contentHtml: String,
    val readingProgress: Float,
    val archivedAtEpochSeconds: Long?,
    val savedAtEpochSeconds: Long?,
    val lastOpenedAtEpochSeconds: Long?,
    val wordCount: Int
)

@Entity(tableName = "highlights")
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val articleId: String,
    val quote: String,
    val note: String,
    val color: String,
    val createdAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long
)

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long
)

@Entity(primaryKeys = ["articleId", "tagId"], tableName = "article_tag_cross_ref")
data class ArticleTagCrossRef(
    val articleId: String,
    val tagId: String
)
