package com.fluxa.app.data.api

import com.fluxa.app.data.local.SecureTokenStore
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewsBlurSessionInterceptor @Inject constructor(private val store: SecureTokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder().header("User-Agent", "Fluxa/0.2 (personal RSS client)")
        val url = chain.request().url
        if (url.host == "www.newsblur.com" && url.isHttps && url.encodedPath != "/api/login") {
            store.getNewsBlurSession()?.let { builder.header("Cookie", it) }
        }
        return chain.proceed(builder.build())
    }
}
