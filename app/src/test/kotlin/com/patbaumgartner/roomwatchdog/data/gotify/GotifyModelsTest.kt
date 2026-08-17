package com.patbaumgartner.roomwatchdog.data.gotify

import com.patbaumgartner.roomwatchdog.data.model.WatchdogEventParser
import com.patbaumgartner.roomwatchdog.data.model.WatchdogEventType
import com.patbaumgartner.roomwatchdog.data.model.latestRoomSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GotifyModelsTest {

    @Test
    fun `current firmware presence text becomes a typed event`() {
        val event = GotifyMessage(
            id = 42,
            appid = 7,
            title = "ESP32 Room Watchdog",
            message = "Person detected at 1.5m (moving)",
            priority = 5,
        ).toWatchdogEvent(receivedAtMillis = 100)

        assertEquals(WatchdogEventType.PresenceDetected, event.type)
        assertEquals(150, event.distanceCm)
        assertEquals(true, event.moving)
    }

    @Test
    fun `current firmware sound text extracts its level`() {
        val event = GotifyMessage(message = "Sound detected (level 2341)").toWatchdogEvent()

        assertEquals(WatchdogEventType.SoundDetected, event.type)
        assertEquals(2341, event.soundLevel)
        assertNull(event.distanceCm)
    }

    @Test
    fun `presence sensor proximity wording becomes a presence alert`() {
        assertEquals(
            WatchdogEventType.PresenceDetected,
            WatchdogEventParser.typeFromText("Someone is close at 0.8m"),
        )
        assertEquals(
            WatchdogEventType.PresenceDetected,
            WatchdogEventParser.typeFromKey("proximity_detected"),
        )
    }

    @Test
    fun `parser preserves unknown text as a generic event`() {
        assertEquals(WatchdogEventType.Unknown, WatchdogEventParser.typeFromText("hello"))
    }

    @Test
    fun `latest presence transition controls the visual state`() {
        val cleared = GotifyMessage(message = "Room empty").toWatchdogEvent(receivedAtMillis = 300)
        val detected = GotifyMessage(message = "Person detected at 1.2m").toWatchdogEvent(receivedAtMillis = 200)

        assertEquals(false, listOf(cleared, detected).latestRoomSignals().presence)
        assertEquals(true, listOf(detected, cleared).latestRoomSignals().presence)
    }

    @Test
    fun `latest sound timestamp drives the temporary noise indicator`() {
        val newer = GotifyMessage(message = "Sound detected (level 3200)")
            .toWatchdogEvent(receivedAtMillis = 400)
        val older = GotifyMessage(message = "Sound detected (level 1200)")
            .toWatchdogEvent(receivedAtMillis = 100)

        assertEquals(400L, listOf(newer, older).latestRoomSignals().lastSoundAtMillis)
    }
}
