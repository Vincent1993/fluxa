package com.fluxa.app.data.repository

interface AuthRepository {
    suspend fun signIn(username: String, password: String) { error("此后端不支持账号登录") }
    suspend fun exchangeCode(code: String)
    suspend fun refreshIfNeeded(): String?
    fun hasSession(): Boolean
    suspend fun logout()
}
