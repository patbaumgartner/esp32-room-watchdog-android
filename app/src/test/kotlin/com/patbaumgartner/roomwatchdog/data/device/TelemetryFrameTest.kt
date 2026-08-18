package com.patbaumgartner.roomwatchdog.data.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `/ws` contract the firmware actually speaks. The frames below are copies of what the
 * device sends, so a firmware change that renames or drops a field fails here rather than silently
 * flattening the live view to zeroes.
 */
class TelemetryFrameTest {

    @Test
    fun `hello announces the audio port the session must use`() {
        val frame = parseTelemetryFrame(
            """
            {"type":"hello","host":"watchdog","audioSampleRate":48000,"audioPort":81,
             "heartbeatMs":2000,"minGate":2,"maxGate":8,"uptimeMs":12345}
            """.trimIndent(),
        )

        assertEquals(
            TelemetryFrame.Hello(
                host = "watchdog",
                audioSampleRate = 48_000,
                audioPort = 81,
                heartbeatMs = 2_000,
                minGate = 2,
                maxGate = 8,
                uptimeMs = 12_345,
            ), frame
        )
    }

    @Test
    fun `hello without an audio port falls back to the firmware default`() {
        val frame = parseTelemetryFrame("""{"type":"hello","host":"watchdog"}""") as TelemetryFrame.Hello

        assertEquals(DeviceClient.DEFAULT_AUDIO_PORT, frame.audioPort)
        assertEquals(DeviceClient.SAMPLE_RATE, frame.audioSampleRate)
    }

    @Test
    fun `a telemetry frame carries the room reading the UI shows`() {
        val frame = parseTelemetryFrame(
            """
            {"type":"telemetry","presence":true,"targetState":3,"movingDistanceCm":150,
             "movingEnergy":42,"stationaryDistanceCm":210,"stationaryEnergy":7,
             "micPeakToPeak":312,"micMin":2586,"micMax":2898,"audioStreaming":true,
             "audioDroppedSamples":128,"uptimeMs":900000}
            """.trimIndent(),
        ) as TelemetryFrame.Telemetry

        assertTrue(frame.status.presence)
        assertTrue("bit 0 of targetState means moving", frame.status.moving)
        assertEquals("a moving target wins over the stationary one", 150, frame.status.primaryDistanceCm)
        assertEquals(312, frame.status.micPeakToPeak)
        assertEquals(128L, frame.status.audioDroppedSamples)
    }

    @Test
    fun `a stationary target reports the stationary distance`() {
        val frame = parseTelemetryFrame(
            """{"type":"telemetry","presence":true,"targetState":2,"stationaryDistanceCm":180}""",
        ) as TelemetryFrame.Telemetry

        assertEquals(false, frame.status.moving)
        assertEquals(180, frame.status.primaryDistanceCm)
    }

    @Test
    fun `every event the firmware emits maps to a known kind`() {
        val kinds = listOf(
            "boot" to TelemetryFrame.Event.Kind.Boot,
            "presence" to TelemetryFrame.Event.Kind.Presence,
            "cleared" to TelemetryFrame.Event.Kind.Cleared,
            "moved" to TelemetryFrame.Event.Kind.Moved,
            "sound" to TelemetryFrame.Event.Kind.Sound,
            "calibration" to TelemetryFrame.Event.Kind.Calibration,
        )
        kinds.forEach { (wire, expected) ->
            val frame = parseTelemetryFrame("""{"type":"event","event":"$wire","message":"x"}""")

            assertEquals(wire, expected, (frame as TelemetryFrame.Event).event)
        }
    }

    @Test
    fun `an event kind this app does not know yet stays Unknown instead of dropping the frame`() {
        val frame = parseTelemetryFrame("""{"type":"event","event":"tamper","message":"case opened"}""")

        assertEquals(TelemetryFrame.Event.Kind.Unknown, (frame as TelemetryFrame.Event).event)
        assertEquals("case opened", frame.message)
    }

    @Test
    fun `unknown fields are tolerated so newer firmware still reports the room`() {
        val frame = parseTelemetryFrame(
            """{"type":"telemetry","presence":true,"humidityPercent":41,"nested":{"a":1}}""",
        ) as TelemetryFrame.Telemetry

        assertTrue(frame.status.presence)
    }

    @Test
    fun `junk on the socket is ignored rather than fatal`() {
        listOf(
            "",
            "not json",
            "[1,2,3]",
            """{"type":"something-else"}""",
            """{"message":"no type at all"}""",
            """{"type":"telemetry","presence":"yes please"}""",
        ).forEach { assertNull("`$it` must not produce a frame", parseTelemetryFrame(it)) }
    }
}
