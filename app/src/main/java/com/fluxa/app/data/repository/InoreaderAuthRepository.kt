package com.fluxa.app.data.repository

import com.fluxa.app.BuildConfig
import com.fluxa.app.data.api.InoreaderApi
import com.fluxa.app.data.local.SecureTokenStore
import com.fluxa.app.data.model.AuthToken
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class InoreaderAuthRepository @Inject constructor(
    private val api: InoreaderApi,
    private val tokenStore: SecureTokenStore
) : AuthRepository {
    private val refreshLock = Mutex()
    override suspend fun exchangeCode(code: String) {
        val response = api.exchangeToken(
            grantType = "authorization_code",
            code = code,
            clientId = BuildConfig.INOREADER_CLIENT_ID,
            clientSecret = BuildConfig.INOREADER_CLIENT_SECRET,
            redirectUri = BuildConfig.INOREADER_REDIRECT_URI
        )
        tokenStore.saveToken(
            AuthToken(
                accessToken = response.accessToken,
                refreshToken = response.refreshToken.orEmpty(),
                expiresInSeconds = response.expiresIn
            )
        )
    }

    override suspend fun refreshIfNeeded(): String? = refreshLock.withLock {
        val token = tokenStore.getAccessToken()
        if (token.isNullOrBlank()) return@withLock null
        if (!tokenStore.isAccessTokenExpired()) return@withLock token

        val refreshToken = tokenStore.getRefreshToken().orEmpty()
        if (refreshToken.isBlank()) return@withLock null

        val response = api.exchangeToken(
            grantType = "refresh_token",
            refreshToken = refreshToken,
            clientId = BuildConfig.INOREADER_CLIENT_ID,
            clientSecret = BuildConfig.INOREADER_CLIENT_SECRET,
            redirectUri = BuildConfig.INOREADER_REDIRECT_URI
        )
        tokenStore.saveToken(
            AuthToken(
                accessToken = response.accessToken,
                refreshToken = response.refreshToken ?: refreshToken,
                expiresInSeconds = response.expiresIn
            )
        )
        response.accessToken
    }

    override fun hasSession(): Boolean = tokenStore.isLoggedIn()

    override suspend fun logout() {
        tokenStore.clear()
    }
}
