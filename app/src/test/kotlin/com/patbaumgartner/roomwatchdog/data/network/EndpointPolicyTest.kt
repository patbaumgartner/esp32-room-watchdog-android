package com.patbaumgartner.roomwatchdog.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EndpointPolicyTest {

    @Test
    fun `device permits encrypted public and cleartext private endpoints`() {
        assertEquals("https://watchdog.example.com/", deviceBaseUrl("https://watchdog.example.com").toString())
        assertEquals("http://192.168.1.10/", deviceBaseUrl("http://192.168.1.10").toString())
        assertEquals("http://sensor.local/", deviceBaseUrl("http://sensor.local").toString())
        assertEquals("http://room-watchdog/", deviceBaseUrl("http://room-watchdog").toString())
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
}
