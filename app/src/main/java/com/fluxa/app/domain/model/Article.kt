package com.fluxa.app.domain.model

import java.time.Instant

data class Article(
    val id: String,
    val title: String,
    val url: String,
    val author: String,
    val excerpt: String,
    val feedName: String,
    val publishedAt: Instant,
    val isRead: Boolean,
    val isStarred: Boolean,
    val contentHtml: String,
    val readingProgress: Float,
    val archivedAt: Instant?,
    val savedAt: Instant?,
    val lastOpenedAt: Instant?,
    val wordCount: Int
)
