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
    val originalUrl: String = "",
    val summary: String = "",
    val readMinutes: Int = 0,
    val sourceNote: String = "",
    val readLater: Boolean = false,
    val categories: List<String> = emptyList(),
    val author: String = "",
    // Preserve the published NewsBlur reader metadata alongside the local RSS model.
    val url: String = originalUrl,
    val excerpt: String = summary,
    val wordCount: Int = 0,
    val readingProgress: Float = 0f,
    val archivedAtEpochSeconds: Long? = null,
    val savedAtEpochSeconds: Long? = null,
    val lastOpenedAtEpochSeconds: Long? = null,
    val tags: List<String> = emptyList(),
    val annotationCount: Int = 0
)
