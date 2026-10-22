package com.fluxa.app.domain.model

data class Subscription(val id: String, val title: String, val url: String,
    val isLocal: Boolean = false, val dailyOnly: Boolean = false,
    val lastSuccess: Long = 0, val status: String = "",
    val alias: String? = null, val folderId: String? = null,
    val paused: Boolean = false, val archived: Boolean = false)
