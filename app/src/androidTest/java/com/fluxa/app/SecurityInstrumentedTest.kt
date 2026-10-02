package com.fluxa.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fluxa.app.data.api.AuthHeaderInterceptor
import com.fluxa.app.data.api.NewsBlurSessionInterceptor
import com.fluxa.app.data.api.NewsBlurApi
import com.fluxa.app.data.repository.NewsBlurAuthRepository
import com.fluxa.app.di.NetworkModule
import kotlinx.coroutines.runBlocking
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import com.fluxa.app.data.local.SecureTokenStore
import com.fluxa.app.data.model.AuthToken
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

// Exercise AndroidKeyStore with isolated preferences; never clear a user's session.
@RunWith(AndroidJUnit4::class)
class SecurityInstrumentedTest {
    private fun testStore(): SecureTokenStore {
        return SecureTokenStore(ApplicationProvider.getApplicationContext<Context>(), "instrumentation_fluxa_auth")
    }

    @Test fun bearerHeaderIsOnlySentToReaderApi() {
        val store = testStore()
        store.clear()
        try {
            store.saveToken(AuthToken("test-token", "refresh", 3600))
            val interceptor = AuthHeaderInterceptor(store)
            val reader = RecordingChain("/reader/api/0/user-info")
            interceptor.intercept(reader).close()
            assertEquals("Bearer test-token", reader.sent!!.header("Authorization"))
            val oauth = RecordingChain("/oauth2/token")
            interceptor.intercept(oauth).close()
            assertNull(oauth.sent!!.header("Authorization"))
        } finally { store.clear() }
    }

    @Test fun authorizationStateRejectsForgedAndRepeatedCallbacks() {
        val store = testStore()
        store.clear()
        try {
            val state = store.beginAuthorization()
            assertFalse(store.consumeAuthorizationState("forged"))
            assertTrue(store.consumeAuthorizationState(state))
            assertFalse(store.consumeAuthorizationState(state))
        } finally { store.clear() }
    }

    @Test fun newsBlurCookieIsEncryptedScopedAndExpires() {
        val store = testStore()
        store.clear()
        try {
            store.saveNewsBlurSession("reader", "newsblur_sessionid=fixture", System.currentTimeMillis() + 60000)
            val interceptor = NewsBlurSessionInterceptor(store)
            val valid = RecordingChain("https://www.newsblur.com/reader/feeds")
            interceptor.intercept(valid).close()
            assertEquals("newsblur_sessionid=fixture", valid.sent!!.header("Cookie"))
            for (url in listOf("https://www.newsblur.com/api/login", "https://example.org/reader/feeds", "http://www.newsblur.com/reader/feeds")) {
                val chain = RecordingChain(url)
                interceptor.intercept(chain).close()
                assertNull(chain.sent!!.header("Cookie"))
            }
            store.saveNewsBlurSession("reader", "newsblur_sessionid=expired", 1L)
            assertNull(store.getNewsBlurSession())
            store.clearNewsBlurSession()
            assertEquals("reader", store.getNewsBlurAccount())
        } finally { store.clear() }
    }

    @Test fun newsBlurLoginStoresOnlySessionAndBindsCacheAccount() = runBlocking {
        val appStore = SecureTokenStore(ApplicationProvider.getApplicationContext<Context>())
        val originalSession = appStore.getNewsBlurSession()
        val originalAccount = appStore.getNewsBlurAccount()
        val store = testStore()
        store.clear()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .addHeader("Set-Cookie", "newsblur_sessionid=fixture; Path=/; Secure; HttpOnly")
                .body("{\"code\":1}".toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://www.newsblur.com/")
            .client(client).addConverterFactory(MoshiConverterFactory.create(NetworkModule.provideMoshi()))
            .build().create(NewsBlurApi::class.java)
        val auth = NewsBlurAuthRepository(api, store)
        try {
            auth.signIn("reader", "fixture-password")
            assertTrue(auth.hasSession())
            assertEquals("newsblur_sessionid=fixture", store.getNewsBlurSession())
            assertTrue("Test storage must not change the app session", appStore.getNewsBlurSession() == originalSession)
            assertTrue("Test storage must not change the app account", appStore.getNewsBlurAccount() == originalAccount)
            assertTrue(runCatching { auth.signIn("different-account", "fixture-password") }.isFailure)
            auth.logout()
            assertFalse(auth.hasSession())
            assertEquals("reader", store.getNewsBlurAccount())
            auth.signIn("reader", "fixture-password")
            assertTrue(auth.hasSession())
        } finally { store.clear() }
    }

    private class RecordingChain(path: String) : Interceptor.Chain {
        private val initial = Request.Builder().url(if (path.startsWith("http")) path else "https://www.inoreader.com$path").build()
        var sent: Request? = null
        override fun request() = initial
        override fun proceed(request: Request): Response {
            sent = request
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("".toResponseBody()).build()
        }
        override fun connection(): Connection? = null
        override fun call(): Call = OkHttpClient().newCall(initial)
        override fun connectTimeoutMillis() = 1000
        override fun readTimeoutMillis() = 1000
        override fun writeTimeoutMillis() = 1000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
