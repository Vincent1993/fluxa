package com.fluxa.app.data.api

import android.content.Intent
import android.net.Uri
import com.fluxa.app.BuildConfig
import com.fluxa.app.data.local.SecureTokenStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OAuthManager @Inject constructor(private val tokenStore: SecureTokenStore) {
    fun createLoginIntent(): Intent {
        val uri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", BuildConfig.INOREADER_CLIENT_ID)
            .appendQueryParameter("redirect_uri", BuildConfig.INOREADER_REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "read write")
            .appendQueryParameter("state", tokenStore.beginAuthorization())
            .build()
        return Intent(Intent.ACTION_VIEW, uri)
    }

    companion object {
        private const val AUTH_URL = "https://www.inoreader.com/oauth2/auth"
    }
}
