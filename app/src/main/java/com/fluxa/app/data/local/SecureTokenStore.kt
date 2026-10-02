package com.fluxa.app.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fluxa.app.data.model.AuthToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureTokenStore internal constructor(
    context: Context,
    preferencesName: String
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, "fluxa_auth")

    private val prefs = EncryptedSharedPreferences.create(
        context,
        preferencesName,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun isLoggedIn(): Boolean = !getAccessToken().isNullOrBlank()

    fun isAccessTokenExpired(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        val expiry = prefs.getLong(KEY_ACCESS_TOKEN_EXPIRES_AT, 0L)
        return expiry <= nowSeconds + 30
    }

    fun saveToken(token: AuthToken) {
        val expiresAt = (System.currentTimeMillis() / 1000) + token.expiresInSeconds
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, token.accessToken)
            .putString(KEY_REFRESH_TOKEN, token.refreshToken)
            .putLong(KEY_ACCESS_TOKEN_EXPIRES_AT, expiresAt)
            .apply()
    }

    fun clear() {
        check(prefs.edit().clear().commit())
    }

    fun getNewsBlurAccount(): String? = prefs.getString("newsblur_account", null)
    fun getNewsBlurSession(): String? = prefs.getString("newsblur_session", null)
        ?.takeIf { prefs.getLong("newsblur_expires", 0L) > System.currentTimeMillis() }
    fun saveNewsBlurSession(account: String, cookie: String, expiresAt: Long) {
        check(prefs.edit().putString("newsblur_account", account)
            .putString("newsblur_session", cookie).putLong("newsblur_expires", expiresAt).commit())
    }
    fun clearNewsBlurSession() {
        prefs.edit().remove("newsblur_session").remove("newsblur_expires").commit()
    }

    fun beginAuthorization(): String {
        val state = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("oauth_state", state)
            .putLong("oauth_started_at", System.currentTimeMillis()).commit()
        return state
    }

    fun consumeAuthorizationState(state: String?): Boolean {
        val expected = prefs.getString("oauth_state", null)
        val started = prefs.getLong("oauth_started_at", 0L)
        if (expected == null || state != expected ||
            System.currentTimeMillis() - started !in 0..600_000) return false
        prefs.edit().remove("oauth_state").remove("oauth_started_at").commit()
        return true
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_ACCESS_TOKEN_EXPIRES_AT = "access_token_expires_at"
    }
}
