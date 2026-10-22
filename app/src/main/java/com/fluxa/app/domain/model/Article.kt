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
    val author: String = ""
)
