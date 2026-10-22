package com.fluxa.app.data

import com.fluxa.app.data.directrss.DirectRssClient
import com.fluxa.app.data.directrss.redirectUrl
import com.fluxa.app.di.NetworkModule
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [28])
class Stage4PublicHttpTest {
    @Test fun anonymousHttpFeedWorksButAccountTransportCannotSendEvenSyntheticCredentials() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("<rss><channel><title>Owned</title><item><guid>1</guid><title>Public</title></item></channel></rss>"))
            assertEquals("Public", DirectRssClient().fetch(server.url("/public").toString()).items.single().title)
            val request = server.takeRequest()
            assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
            val account = NetworkModule.accountClient().build()
            assertFalse(account.followSslRedirects)
            assertFalse(account.connectionSpecs.any { !it.isTls })
            try {
                account.newCall(Request.Builder().url(server.url("/account"))
                    .header("Authorization", "OWNED-SYNTHETIC-NOT-A-CREDENTIAL").build()).execute().close()
                fail("Account HTTP must be refused before a socket request")
            } catch (_: IOException) { }
            assertEquals(1, server.requestCount)
            try { redirectUrl("https://example.org/feed", "http://example.org/feed"); fail("No TLS downgrade") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
