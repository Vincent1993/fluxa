package com.fluxa.app.data.repository

/** In-memory UI receipt, compared against the durable local revision, never a blind toggle. */
enum class ArticleFlag { Read, Star, ReadLater }
data class FlagReceipt(val id: String, val flag: ArticleFlag, val previous: Boolean, val applied: Boolean, val revision: String)
