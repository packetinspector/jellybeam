package tv.jellybeam

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import java.util.concurrent.TimeUnit

class ImageHttpClientTest {

    @Test
    fun `art gets its own wider queue and keeps the app's timeouts`() {
        val base = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
        val images = imageHttpClient(base)

        assertEquals(IMAGE_MAX_REQUESTS_PER_HOST, images.dispatcher.maxRequestsPerHost)
        assertEquals(5, base.dispatcher.maxRequestsPerHost)
        assertNotSame(base.dispatcher, images.dispatcher)
        assertNotSame(base.connectionPool, images.connectionPool)
        assertEquals(base.readTimeoutMillis, images.readTimeoutMillis)
    }
}
