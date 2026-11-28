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
    fun `direct HTTP audio follows the announced port and websocket stays on the API port`() {
        assertEquals(
            "direct LAN audio must use the firmware's audio server",
            "http://192.168.1.216:81/audio.pcm",
            deviceAudioUrl("http://192.168.1.216", 81).toString(),
        )
        assertEquals(
            "a valid announcement replaces the fallback",
            "http://192.168.1.216:8082/audio.pcm",
            deviceAudioUrl("http://192.168.1.216", 8082).toString(),
        )
        assertEquals(
            "direct IPv6 keeps its brackets when the audio port is applied",
            "http://[fd12::1]:81/audio.pcm",
            deviceAudioUrl("http://[fd12::1]", 81).toString(),
        )
        assertEquals(
            "telemetry must not move to the audio server",
            "http://192.168.1.216/ws",
            deviceSocketUrl("http://192.168.1.216").toString(),
        )
    }

    @Test
    fun `HTTPS and explicit HTTP proxy ports are never replaced by the device port`() {
        assertEquals(
            "the public HTTPS proxy routes the path to the device audio server",
            "https://watchdog.example.com/audio.pcm",
            deviceAudioUrl("https://watchdog.example.com", 81).toString(),
        )
        assertEquals(
            "a custom HTTPS proxy port must be preserved",
            "https://watchdog.example.com:8443/audio.pcm",
            deviceAudioUrl("https://watchdog.example.com:8443", 8082).toString(),
        )
        assertEquals(
            "a custom cleartext proxy port must be preserved",
            "http://192.168.1.10:8080/audio.pcm",
            deviceAudioUrl("http://192.168.1.10:8080", 81).toString(),
        )
        val standardProxy = deviceAudioUrl("http://192.168.1.10:80", 81)
        assertEquals("explicit port 80 is the standard-port proxy override", 80, standardProxy.port)
        assertEquals(
            "OkHttp canonicalises an explicit default port without applying port 81",
            "http://192.168.1.10/audio.pcm",
            standardProxy.toString(),
        )
    }

    @Test
    fun `Hello host distinguishes a no-port hostname proxy from a direct device`() {
        assertEquals(
            "a matching local hostname and Hello host is direct",
            "http://watchdog.lan:8082/audio.pcm",
            deviceAudioUrl("http://watchdog.lan", 8082, "watchdog").toString(),
        )
        assertEquals(
            "a different Hello host identifies a standard-port proxy",
            "http://proxy.lan/audio.pcm",
            deviceAudioUrl("http://proxy.lan", 8082, "device").toString(),
        )
        assertEquals(
            "without identity evidence the migration fallback remains direct",
            "http://proxy.lan:81/audio.pcm",
            deviceAudioUrl("http://proxy.lan", 81, null).toString(),
        )
    }

    @Test
    fun `an implausible announced port never reaches OkHttp`() {
        listOf(null, 0, -1, 65_536, 70_000).forEach { port ->
            assertEquals(
                "port $port must leave the configured URL alone",
                "http://192.168.1.10/audio.pcm",
                deviceAudioUrl("http://192.168.1.10", port).toString(),
            )
        }
    }
}
