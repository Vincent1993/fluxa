package com.fluxa.app.data.reader

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reader_metadata")
data class ReaderMetadata(
    @PrimaryKey val articleId: String,
    val url: String = "",
    val author: String = "",
    val excerpt: String = "",
    val wordCount: Int = 0,
    val readingProgress: Float = 0f,
    val archivedAtEpochSeconds: Long? = null,
    val savedAtEpochSeconds: Long? = null,
    val lastOpenedAtEpochSeconds: Long? = null
)

@Entity(tableName = "reader_annotations")
data class ReaderAnnotation(
    @PrimaryKey val id: String,
    val articleId: String,
    val kind: String,
    val quote: String,
    val note: String,
    val color: String,
    val createdAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long
)

@Entity(tableName = "reader_tags")
data class ReaderTag(@PrimaryKey val id: String, val name: String, val createdAtEpochSeconds: Long = 0, val updatedAtEpochSeconds: Long = 0)

@Entity(tableName = "reader_article_tags", primaryKeys = ["articleId", "tagId"])
data class ReaderArticleTag(val articleId: String, val tagId: String)
