package com.fluxa.app.data.api.model

import com.squareup.moshi.Json

data class TokenResponse(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "token_type") val tokenType: String,
    @Json(name = "expires_in") val expiresIn: Long,
    @Json(name = "refresh_token") val refreshToken: String?
)

data class StreamContentsResponse(
    @Json(name = "id") val id: String,
    @Json(name = "continuation") val continuation: String?,
    @Json(name = "items") val items: List<StreamItem>
)

data class StreamItem(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String?,
    @Json(name = "published") val published: Long?,
    @Json(name = "categories") val categories: List<String>?,
    @Json(name = "origin") val origin: Origin?,
    @Json(name = "summary") val summary: Summary?
)

data class Origin(
    @Json(name = "title") val title: String?
)

data class Summary(
    @Json(name = "content") val content: String?
)
