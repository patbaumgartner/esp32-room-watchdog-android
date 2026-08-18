package com.patbaumgartner.roomwatchdog.data.device

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Frames the firmware sends on `/ws`; anything unknown is ignored rather than fatal. */
sealed interface TelemetryFrame {

    /** Sent once per connection and carries the settings the client has to follow. */
    @Serializable
    data class Hello(
        val host: String = "",
        val audioSampleRate: Int = DeviceClient.SAMPLE_RATE,
        val audioPort: Int = DeviceClient.DEFAULT_AUDIO_PORT,
        val heartbeatMs: Long = 2_000,
        val minGate: Int = 0,
        val maxGate: Int = 0,
        val uptimeMs: Long = 0,
    ) : TelemetryFrame

    /** Live sensor snapshot: on change, and at least once per heartbeat. */
    @Serializable
    data class Telemetry(val status: DeviceStatus) : TelemetryFrame

    @Serializable
    data class Event(
        val event: Kind = Kind.Unknown,
        val message: String = "",
        val uptimeMs: Long = 0,
    ) : TelemetryFrame {

        @Serializable
        enum class Kind {
            @SerialName("boot")
            Boot,

            @SerialName("presence")
            Presence,

            @SerialName("cleared")
            Cleared,

            @SerialName("moved")
            Moved,

            @SerialName("sound")
            Sound,

            @SerialName("calibration")
            Calibration,

            @SerialName("unknown")
            Unknown,
        }
    }
}

private val telemetryJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * Frames the firmware has not taught this app about, and frames it garbles, return null: telemetry
 * is advisory, and a socket carrying one bad message must not take the live view down with it.
 */
internal fun parseTelemetryFrame(text: String): TelemetryFrame? {
    val root = runCatching { telemetryJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    return runCatching {
        when (root["type"]?.jsonPrimitive?.content) {
            "hello" -> telemetryJson.decodeFromJsonElement(TelemetryFrame.Hello.serializer(), root)
            "telemetry" ->
                TelemetryFrame.Telemetry(telemetryJson.decodeFromJsonElement(DeviceStatus.serializer(), root))

            "event" -> telemetryJson.decodeFromJsonElement(TelemetryFrame.Event.serializer(), root)
            else -> null
        }
    }.getOrNull()
}
