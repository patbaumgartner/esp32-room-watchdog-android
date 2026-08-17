package com.patbaumgartner.roomwatchdog.data.gotify

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GotifyClientValidationTest {

    private val client = GotifyClient(OkHttpClient())

    @Test
    fun `cleartext Gotify endpoint fails before a network request`() = runBlocking {
        val result = client.currentUser("http://gotify.example.com", "client-token")

        assertTrue(result.isFailure)
        assertEquals(
            GotifyException.Kind.NotHttps,
            (result.exceptionOrNull() as GotifyException).kind,
        )
    }

    @Test
    fun `application token is rejected as a client token`() = runBlocking {
        val result = client.currentUser("https://gotify.example.com", "gtfya.application-token")

        assertTrue(result.isFailure)
        assertEquals(
            GotifyException.Kind.ApplicationToken,
            (result.exceptionOrNull() as GotifyException).kind,
        )
    }

    @Test
    fun `stream URL keeps the HTTPS scheme and carries no credentials`() {
        val url = gotifyStreamUrl("https://gotify.example.com")

        assertEquals("https://gotify.example.com/stream", url.toString())
        assertNull("the token belongs in a header, not a logged URL", url.queryParameter("token"))
    }
}
