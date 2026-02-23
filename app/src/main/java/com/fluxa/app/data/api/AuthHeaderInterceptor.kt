package com.fluxa.app.data.api

import com.fluxa.app.data.local.SecureTokenStore
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthHeaderInterceptor @Inject constructor(
    private val tokenStore: SecureTokenStore
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStore.getAccessToken().orEmpty()
        val request = if (token.isNotBlank()) {
            chain.request().newBuilder()
                .addHeader("Authorization", "GoogleLogin auth=$token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
