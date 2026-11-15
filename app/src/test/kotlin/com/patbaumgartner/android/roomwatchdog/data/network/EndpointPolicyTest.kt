package com.patbaumgartner.android.roomwatchdog.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EndpointPolicyTest {

    @Test
    fun `device permits encrypted public and cleartext private endpoints`() {
        assertEquals("https://watchdog.example.com/", deviceBaseUrl("https://watchdog.example.com").toString())
        assertEquals("http://192.168.1.10/", deviceBaseUrl("http://192.168.1.10").toString())
        assertEquals("http://10.0.0.5/", deviceBaseUrl("http://10.0.0.5").toString())
        assertEquals("http://172.20.0.5/", deviceBaseUrl("http://172.20.0.5").toString())
        assertEquals("http://127.0.0.1/", deviceBaseUrl("http://127.0.0.1").toString())
        assertEquals("http://169.254.10.1/", deviceBaseUrl("http://169.254.10.1").toString())
        assertEquals("http://sensor.local/", deviceBaseUrl("http://sensor.local").toString())
        assertEquals("http://room-watchdog/", deviceBaseUrl("http://room-watchdog").toString())
    }

    @Test
    fun `device permits cleartext to loopback and local IPv6 literals`() {
        listOf(
            "http://[::1]",
            "http://[fd12:3456:789a::1]",
            "http://[fe80::1]",
        ).forEach { assertEquals(it, deviceBaseUrl(it).toString().trimEnd('/')) }
    }

    @Test
    fun `device rejects cleartext to a public IPv6 literal`() {
        val error = assertThrows(EndpointValidationException::class.java) {
            deviceBaseUrl("http://[2001:db8::1]")
        }

        assertEquals(EndpointIssue.Insecure, error.issue)
    }

    @Test
    fun `a public name that merely starts like a private range is still cleartext-blocked`() {
        listOf(
            "http://feature.example.com",
            "http://fc-barcelona.example.com",
            "http://fdn.example.com",
            "http://8.8.8.8",
            "http://1.2.3.999",
        ).forEach { value ->
            val error = assertThrows("$value must not pass as private", EndpointValidationException::class.java) {
                deviceBaseUrl(value)
            }
            assertEquals(EndpointIssue.Insecure, error.issue)
        }
    }

    @Test
    fun `device rejects cleartext public endpoints`() {
        val error = assertThrows(EndpointValidationException::class.java) {
            deviceBaseUrl("http://watchdog.example.com")
        }

        assertEquals(EndpointIssue.Insecure, error.issue)
    }

    @Test
    fun `gotify requires https`() {
        assertEquals("https://gotify.example.com/", gotifyBaseUrl("https://gotify.example.com").toString())

        val error = assertThrows(EndpointValidationException::class.java) {
            gotifyBaseUrl("http://gotify.example.com")
        }
        assertEquals(EndpointIssue.Insecure, error.issue)
    }

    @Test
    fun `endpoints reject credentials query strings and malformed input`() {
        listOf(
            "not a url",
            "https://user:password@example.com",
            "https://example.com?redirect=elsewhere",
            "https://example.com#fragment",
        ).forEach { value ->
            val error = assertThrows(EndpointValidationException::class.java) {
                gotifyBaseUrl(value)
            }
            assertEquals(EndpointIssue.Invalid, error.issue)
        }
    }

    @Test
    fun `audio stays on the base URL's own port so a proxy can route by path`() {
        // Only the scheme default (443 for https, 80 for http) or an explicit port in
        // the configured URL may appear; the device's announced audio port (81) must
        // never be written into the URL, otherwise a reverse proxy is bypassed.
        assertEquals(
            "https://watchdog.example.com/audio.pcm",
            deviceAudioUrl("https://watchdog.example.com").toString(),
        )
        assertEquals(
            "http://192.168.1.10/audio.pcm",
            deviceAudioUrl("http://192.168.1.10").toString(),
        )
        assertEquals(
            "https://watchdog.example.com:8443/audio.pcm",
            deviceAudioUrl("https://watchdog.example.com:8443").toString(),
        )
        assertEquals("http://192.168.1.10/ws", deviceSocketUrl("http://192.168.1.10").toString())
    }
}
