package com.fluxa.app.data.repository

/** Exact captured IDs, never a future query. A later read/star change invalidates its undo token. */
data class ReadChange(val id: String, val previous: Boolean, val revision: String)
data class ReadReceipt(val changes: List<ReadChange>, val failures: List<String> = emptyList())
