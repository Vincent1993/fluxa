package com.fluxa.app.data

import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.api.*
import com.fluxa.app.data.api.model.*
import com.fluxa.app.data.local.SecureTokenStore
import com.fluxa.app.data.model.AuthToken
import com.fluxa.app.navigation.Routes
import com.fluxa.app.di.NetworkModule
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ApiContractTest {
    @Test fun kotlinDtoParsingAcceptsOptionalFieldsAndFeedSource() {
        val moshi = NetworkModule.provideMoshi()
        val stream = moshi.adapter(StreamContentsResponse::class.java).fromJson(
            """{"id":"reading","items":[{"id":"tag:google.com,2005:reader/item/123",
              "origin":{"title":"Example","streamId":"feed/https://example.org/rss"}}]}""")!!
        assertNull(stream.continuation)
        assertEquals("feed/https://example.org/rss", stream.items.single().origin!!.streamId)
    }

    @Test fun streamPathEncodesFeedUrlAsSingleParameter() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"id":"feed","items":[]}"""))
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(MoshiConverterFactory.create(NetworkModule.provideMoshi()))
                .build().create(InoreaderApi::class.java)
            api.getReadingStream(count = 20, streamId = "feed/https://example.org/rss?a=1&b=2")
            val url = server.takeRequest().requestUrl!!
            assertEquals(6, url.pathSegments.size)
            assertEquals("feed/https://example.org/rss?a=1&b=2", url.pathSegments.last())
            assertEquals(setOf("n"), url.queryParameterNames)
            assertEquals("20", url.queryParameter("n"))
        } finally { server.shutdown() }
    }

    @Test fun articleRouteEscapesSlashQueryAndFragment() {
        val id = "tag:google.com,2005:reader/item/123?x#frag"
        val route = Routes.article(id)
        assertFalse(route.removePrefix("article/").contains("/"))
        assertEquals(id, android.net.Uri.decode(route.removePrefix("article/")))
    }
}
