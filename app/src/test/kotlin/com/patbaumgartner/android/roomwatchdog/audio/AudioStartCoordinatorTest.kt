package com.patbaumgartner.android.roomwatchdog.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioStartCoordinatorTest {

    @Test
    fun `cold notification start waits for delayed Hello and preserves recording`() {
        val coordinator = AudioStartCoordinator(ENDPOINT_A)
        val generation = coordinator.currentGeneration()

        val wait = coordinator.requestStart(alsoRecord = true) as AudioStartDecision.Wait
        assertTrue("a cold start needs one bounded timer", wait.scheduleTimeout)
        val start = coordinator.onHello(generation, 8082, "a")

        assertEquals("http://a.local/", start?.endpoint)
        assertEquals("the delayed announcement must be used", 8082, start?.port)
        assertEquals("Hello identity must reach direct/proxy selection", "a", start?.helloHost)
        assertTrue("the notification recording request must survive the wait", start?.alsoRecord == true)
        assertNull("the completed request must not start again at timeout", coordinator.onTimeout(wait))
    }

    @Test
    fun `cold start falls back to 81 only at the bounded timeout`() {
        val coordinator = AudioStartCoordinator(ENDPOINT_A)
        assertTrue(
            "readiness must have a short finite bound",
            AUDIO_PORT_READINESS_TIMEOUT_MS in 1..5_000,
        )
        val wait = coordinator.requestStart(alsoRecord = false) as AudioStartDecision.Wait

        assertNull("there is no start before Hello or timeout", coordinator.onHello(wait.generation, 0))
        val start = coordinator.onTimeout(wait)

        assertEquals("legacy devices use the firmware default", 81, start?.port)
        assertFalse(start?.alsoRecord == true)
        assertTrue("a queued start remains valid until it is dispatched", coordinator.isCurrent(start!!))
    }

    @Test
    fun `stop before readiness prevents Hello and timeout from starting late`() {
        val coordinator = AudioStartCoordinator(ENDPOINT_A)
        val generation = coordinator.currentGeneration()
        val wait = coordinator.requestStart(alsoRecord = true) as AudioStartDecision.Wait

        coordinator.cancelPendingStart()

        assertNull("Hello must not start a cancelled request", coordinator.onHello(generation, 8082))
        assertNull("timeout must not start a cancelled request", coordinator.onTimeout(wait))
    }

    @Test
    fun `endpoint change resets readiness closes telemetry and rejects stale frames`() {
        var telemetryClosures = 0
        val coordinator = AudioStartCoordinator(ENDPOINT_A) { telemetryClosures++ }
        val generationA = coordinator.currentGeneration()
        coordinator.onHello(generationA, 8082)

        assertFalse(
            "equivalent normalized endpoints must not reconnect",
            coordinator.resetEndpoint("http://a.local/"),
        )
        assertEquals(0, telemetryClosures)
        assertTrue("a different endpoint must reset the socket", coordinator.resetEndpoint(ENDPOINT_B))
        assertEquals("the old telemetry socket must be closed", 1, telemetryClosures)

        val waitB = coordinator.requestStart(alsoRecord = false) as AudioStartDecision.Wait
        assertNull("a late A Hello must not update B", coordinator.onHello(generationA, 8181))
        assertNull("a late A timeout must not start B", coordinator.onTimeout(waitB.copy(generation = generationA)))
        val startB = coordinator.onHello(waitB.generation, 9090)
        assertEquals("http://b.local/", startB?.endpoint)
        assertEquals("B must wait for its own announcement", 9090, startB?.port)
    }

    @Test
    fun `explicit standard proxy port survives endpoint normalization`() {
        val coordinator = AudioStartCoordinator("http://a.local:80")
        val wait = coordinator.requestStart(alsoRecord = false) as AudioStartDecision.Wait

        assertEquals("http://a.local:80/", coordinator.onTimeout(wait)?.endpoint)
        assertTrue(coordinator.resetEndpoint(ENDPOINT_A))
    }

    @Test
    fun `credential revision resets a cached port on the same URL`() {
        val coordinator = AudioStartCoordinator(ENDPOINT_A)
        val generationA = coordinator.currentGeneration()
        coordinator.onHello(generationA, 8082)

        assertTrue(coordinator.resetEndpoint(ENDPOINT_A, force = true))

        assertTrue(
            "the new credentials need a new first Hello",
            coordinator.requestStart(alsoRecord = false) is AudioStartDecision.Wait,
        )
        assertNull(coordinator.onHello(generationA, 8082))
    }

    @Test
    fun `invalid Hello values keep readiness and cached valid port unchanged`() {
        val coordinator = AudioStartCoordinator(ENDPOINT_A)
        val generation = coordinator.currentGeneration()
        val wait = coordinator.requestStart(alsoRecord = false) as AudioStartDecision.Wait

        listOf(0, -1, 65_536, 70_000).forEach { port ->
            assertNull("invalid port $port must not complete readiness", coordinator.onHello(generation, port))
        }
        assertEquals("invalid Hello still reaches the legacy fallback", 81, coordinator.onTimeout(wait)?.port)

        coordinator.onHello(generation, 8082)
        coordinator.onHello(generation, 0)
        val next = coordinator.requestStart(alsoRecord = false) as AudioStartDecision.Start
        assertEquals("an invalid later Hello cannot replace a valid cache", 8082, next.port)
    }

    @Test
    fun `repeated taps share one timer and retain a recording request`() {
        val coordinator = AudioStartCoordinator(ENDPOINT_A)
        val generation = coordinator.currentGeneration()

        val first = coordinator.requestStart(alsoRecord = false) as AudioStartDecision.Wait
        val second = coordinator.requestStart(alsoRecord = true) as AudioStartDecision.Wait

        assertTrue(first.scheduleTimeout)
        assertFalse("only the first waiter schedules the bound", second.scheduleTimeout)
        assertTrue(coordinator.onHello(generation, 8082)?.alsoRecord == true)
    }

    private companion object {
        const val ENDPOINT_A = "http://a.local"
        const val ENDPOINT_B = "http://b.local"
    }
}
