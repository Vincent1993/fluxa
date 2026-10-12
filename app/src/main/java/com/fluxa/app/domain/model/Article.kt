package com.fluxa.app.domain.model

import java.time.Instant

data class Article(
    val id: String,
    val title: String,
    val feedName: String,
    val publishedAt: Instant,
    val isRead: Boolean,
    val isStarred: Boolean,
    val contentHtml: String,
    val source: String = "",
    val url: String = "",
    val author: String = "",
    val excerpt: String = "",
    val wordCount: Int = 0,
    val readingProgress: Float = 0f,
    val archivedAtEpochSeconds: Long? = null,
    val savedAtEpochSeconds: Long? = null,
    val lastOpenedAtEpochSeconds: Long? = null,
    val tags: List<String> = emptyList(),
    val annotationCount: Int = 0
)
