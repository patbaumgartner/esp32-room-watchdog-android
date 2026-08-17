package com.patbaumgartner.roomwatchdog.data.gotify

import com.patbaumgartner.roomwatchdog.data.model.WatchdogEvent
import com.patbaumgartner.roomwatchdog.data.model.WatchdogEventParser
import com.patbaumgartner.roomwatchdog.data.model.WatchdogEventType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class GotifyApplication(
    val id: Long,
    val name: String = "",
    val description: String = "",
)

@Serializable
data class GotifyMessage(
    val id: Long = 0,
    val appid: Long = 0,
    val message: String = "",
    val title: String = "",
    val priority: Int = 0,
    val date: String = "",
    val extras: JsonObject? = null,
)

@Serializable
data class PagedMessages(val messages: List<GotifyMessage> = emptyList())

@Serializable
data class CreatedClient(val id: Long = 0, val token: String = "", val name: String = "")

@Serializable
data class GotifyUser(val id: Long = 0, val name: String = "", val admin: Boolean = false)

private const val EVENT_EXTRA = "watchdog::event"

/** Prefers the structured extra and falls back to the firmware's plain wording. */
fun GotifyMessage.toWatchdogEvent(receivedAtMillis: Long = System.currentTimeMillis()): WatchdogEvent {
    val payload = extras?.get(EVENT_EXTRA)?.let { runCatching { it.jsonObject }.getOrNull() }

    val declaredType = payload?.get("type")
        ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        ?.let { WatchdogEventParser.typeFromKey(it) }

    val type = declaredType ?: WatchdogEventParser.typeFromText(message)

    val distanceCm = payload?.get("distanceCm")
        ?.let { runCatching { it.jsonPrimitive.int }.getOrNull() }
        ?: WatchdogEventParser.distanceCmFromText(message)

    val moving = payload?.get("motion")
        ?.let { runCatching { it.jsonPrimitive.content == "moving" }.getOrNull() }
        ?: payload?.get("moving")?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
        ?: WatchdogEventParser.movingFromText(message)

    val soundLevel = payload?.get("soundLevel")
        ?.let { runCatching { it.jsonPrimitive.int }.getOrNull() }
        ?: WatchdogEventParser.soundLevelFromText(message)

    return WatchdogEvent(
        messageId = id,
        appId = appid,
        title = title,
        message = message,
        priority = priority,
        receivedAtMillis = receivedAtMillis,
        type = type,
        distanceCm = distanceCm.takeIf { type != WatchdogEventType.SoundDetected },
        moving = moving,
        soundLevel = soundLevel,
    )
}
