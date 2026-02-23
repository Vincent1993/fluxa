package com.fluxa.app.data.model

data class AuthToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long
)
