package com.patbaumgartner.roomwatchdog.data.network

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
    fun `audio takes the device's own port unless the URL already names one`() {
        assertEquals(
            "http://192.168.1.10:81/audio.pcm",
            deviceAudioUrl("http://192.168.1.10", 81).toString(),
        )
        assertEquals(
            "https://watchdog.example.com:8443/audio.pcm",
            deviceAudioUrl("https://watchdog.example.com:8443", 81).toString(),
        )
        assertEquals("http://192.168.1.10/ws", deviceSocketUrl("http://192.168.1.10").toString())
    }

    @Test
    fun `an implausible announced port leaves the configured URL alone`() {
        listOf(null, 0, -1, 70_000).forEach { port ->
            assertEquals(
                "port $port must not reach okhttp",
                "http://192.168.1.10/audio.pcm",
                deviceAudioUrl("http://192.168.1.10", port).toString(),
            )
        }
    }
}
