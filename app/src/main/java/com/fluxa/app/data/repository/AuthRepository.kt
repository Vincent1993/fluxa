package com.fluxa.app.data.repository

interface AuthRepository {
    suspend fun exchangeCode(code: String)
    suspend fun refreshIfNeeded(): String?
    fun hasSession(): Boolean
    suspend fun logout()
}
