package com.patbaumgartner.roomwatchdog.data.model

enum class WatchdogEventType {
    Online,
    PresenceDetected,
    PresenceCleared,
    Movement,
    SoundDetected,
    CalibrationStarted,
    Unknown,
}

data class WatchdogEvent(
    val messageId: Long,
    val title: String,
    val message: String,
    val priority: Int,
    val receivedAtMillis: Long,
    val type: WatchdogEventType,
    val distanceCm: Int? = null,
    val moving: Boolean? = null,
    val soundLevel: Int? = null,
)

data class RoomSignals(
    val presence: Boolean? = null,
    val presenceAtMillis: Long? = null,
    val lastSoundAtMillis: Long? = null,
)

/** Derives the latest independent presence and sound states from newest-first events. */
fun List<WatchdogEvent>.latestRoomSignals(): RoomSignals {
    var presence: Boolean? = null
    var presenceAtMillis: Long? = null
    var lastSoundAtMillis: Long? = null

    for (event in this) {
        if (presence == null) {
            presence = when (event.type) {
                WatchdogEventType.PresenceDetected, WatchdogEventType.Movement -> true
                WatchdogEventType.PresenceCleared -> false
                else -> null
            }
            if (presence != null) presenceAtMillis = event.receivedAtMillis
        }
        if (lastSoundAtMillis == null && event.type == WatchdogEventType.SoundDetected) {
            lastSoundAtMillis = event.receivedAtMillis
        }
        if (presence != null && lastSoundAtMillis != null) break
    }

    return RoomSignals(presence, presenceAtMillis, lastSoundAtMillis)
}

/**
 * Understands the structured `watchdog::event` extra, and falls back to parsing the
 * firmware's human-readable text so the app also works against unmodified firmware.
 */
object WatchdogEventParser {

    private val distanceRegex = Regex("""([0-9]+(?:\.[0-9]+)?)\s*m\b""", RegexOption.IGNORE_CASE)
    private val soundLevelRegex = Regex("""level\s+([0-9]+)""", RegexOption.IGNORE_CASE)

    fun typeFromText(message: String): WatchdogEventType {
        val text = message.lowercase()
        return when {
            text.contains("presence cleared") || text.contains("room empty") ||
                    text.contains("no presence") || text.contains("nobody is here") ->
                WatchdogEventType.PresenceCleared

            text.contains("person detected") || text.contains("presence detected") ||
                    text.contains("someone is here") || text.contains("someone is close") ||
                    text.contains("person nearby") -> WatchdogEventType.PresenceDetected

            text.contains("moved to") -> WatchdogEventType.Movement
            text.contains("sound detected") -> WatchdogEventType.SoundDetected
            text.contains("online") -> WatchdogEventType.Online
            text.contains("calibration") -> WatchdogEventType.CalibrationStarted
            else -> WatchdogEventType.Unknown
        }
    }

    fun typeFromKey(key: String?): WatchdogEventType? = when (key?.trim()?.lowercase()) {
        "online" -> WatchdogEventType.Online
        "presence_detected", "person_detected", "proximity_detected" ->
            WatchdogEventType.PresenceDetected

        "presence_cleared" -> WatchdogEventType.PresenceCleared
        "movement" -> WatchdogEventType.Movement
        "sound_detected" -> WatchdogEventType.SoundDetected
        "calibration_started" -> WatchdogEventType.CalibrationStarted
        else -> null
    }

    fun distanceCmFromText(message: String): Int? =
        distanceRegex.find(message)?.groupValues?.get(1)?.toFloatOrNull()?.let { (it * 100).toInt() }

    fun movingFromText(message: String): Boolean? {
        val text = message.lowercase()
        return when {
            text.contains("moving") -> true
            text.contains("still") || text.contains("stationary") -> false
            else -> null
        }
    }

    fun soundLevelFromText(message: String): Int? =
        soundLevelRegex.find(message)?.groupValues?.get(1)?.toIntOrNull()
}
