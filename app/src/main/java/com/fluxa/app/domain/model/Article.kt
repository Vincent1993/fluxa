package com.fluxa.app.domain.model

import java.time.Instant

data class Article(
    val id: String,
    val title: String,
    val feedName: String,
    val publishedAt: Instant,
    val isRead: Boolean,
    val isStarred: Boolean,
    val source: String,
    val tags: String,
    val contentHtml: String
)
