package com.patbaumgartner.android.roomwatchdog.data.device

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeviceClientTest {

    private val client = DeviceClient(OkHttpClient())

    @Test
    fun `session request uses the default audio port on direct private HTTP`() {
        val request = client.audioRequest("http://192.168.1.216", "test-token")

        assertEquals(
            "the session must not request audio from the API port",
            "http://192.168.1.216:81/audio.pcm",
            request.url.toString(),
        )
        assertEquals("credentials stay in the header", "Bearer test-token", request.header("Authorization"))
        assertFalse("the token must not enter the URL", request.url.toString().contains("test-token"))
    }

    @Test
    fun `session request uses a valid announced audio port`() {
        val request = client.audioRequest("http://192.168.1.216", "test-token", audioPort = 8082)

        assertEquals("http://192.168.1.216:8082/audio.pcm", request.url.toString())
    }

    @Test
    fun `missing and invalid session ports fall back to the firmware default`() {
        listOf(null, 0, -1, 65_536, 70_000).forEach { port ->
            val request = client.audioRequest("http://192.168.1.216", "test-token", audioPort = port)

            assertEquals(
                "port $port must fall back to 81",
                "http://192.168.1.216:81/audio.pcm",
                request.url.toString(),
            )
        }
    }

    @Test
    fun `session request preserves public HTTPS and custom proxy ports`() {
        val requests = listOf(
            "https://watchdog.baumgartner.sg" to "https://watchdog.baumgartner.sg/audio.pcm",
            "https://watchdog.baumgartner.sg:8443" to "https://watchdog.baumgartner.sg:8443/audio.pcm",
            "http://192.168.1.10:8080" to "http://192.168.1.10:8080/audio.pcm",
        )

        requests.forEach { (baseUrl, expected) ->
            assertEquals(
                "$baseUrl must keep its proxy port",
                expected,
                client.audioRequest(baseUrl, "test-token", audioPort = 8082).url.toString(),
            )
        }
    }

    @Test
    fun `mismatching Hello host keeps a no-port standard proxy on port 80`() {
        val request = client.audioRequest(
            baseUrl = "http://proxy.lan",
            apiToken = "test-token",
            audioPort = 8082,
            helloHost = "device",
        )

        assertEquals(80, request.url.port)
        assertEquals("http://proxy.lan/audio.pcm", request.url.toString())
    }

    @Test
    fun `explicit standard HTTP port remains a proxy override`() {
        val request = client.audioRequest("http://192.168.1.10:80", "test-token", audioPort = 8082)

        assertEquals("the announced port must not replace the proxy port", 80, request.url.port)
        assertEquals("http://192.168.1.10/audio.pcm", request.url.toString())
    }

    @Test
    fun `telemetry request remains on the configured API endpoint`() {
        val request = client.telemetryRequest("http://192.168.1.216", "test-token")

        assertEquals("http://192.168.1.216/ws", request.url.toString())
    }
}
