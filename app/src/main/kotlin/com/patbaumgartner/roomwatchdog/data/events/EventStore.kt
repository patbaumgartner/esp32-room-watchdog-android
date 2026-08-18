package com.patbaumgartner.roomwatchdog.data.events

import android.content.Context
import androidx.core.content.edit
import com.patbaumgartner.roomwatchdog.data.model.WatchdogEvent
import com.patbaumgartner.roomwatchdog.data.model.WatchdogEventType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class StoredEvent(
    val messageId: Long,
    val title: String,
    val message: String,
    val priority: Int,
    val receivedAtMillis: Long,
    val type: String,
    val distanceCm: Int? = null,
    val moving: Boolean? = null,
    val soundLevel: Int? = null,
)

class EventStore(context: Context) {

    private val prefs = context.getSharedPreferences("watchdog_events", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _events = MutableStateFlow(read())
    val events: StateFlow<List<WatchdogEvent>> = _events.asStateFlow()

    /** Returns false when the message was already stored, so notifications stay de-duplicated. */
    @Synchronized
    fun record(event: WatchdogEvent): Boolean {
        if (_events.value.any { it.messageId == event.messageId && event.messageId != 0L }) return false
        write((listOf(event) + _events.value).take(MAX_EVENTS))
        return true
    }

    private fun read(): List<WatchdogEvent> {
        val stored = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<StoredEvent>>(stored) }
            .getOrDefault(emptyList())
            .map {
                WatchdogEvent(
                    messageId = it.messageId,
                    title = it.title,
                    message = it.message,
                    priority = it.priority,
                    receivedAtMillis = it.receivedAtMillis,
                    type = runCatching { WatchdogEventType.valueOf(it.type) }
                        .getOrDefault(WatchdogEventType.Unknown),
                    distanceCm = it.distanceCm,
                    moving = it.moving,
                    soundLevel = it.soundLevel,
                )
            }
    }

    private fun write(events: List<WatchdogEvent>) {
        val stored = events.map {
            StoredEvent(
                messageId = it.messageId,
                title = it.title,
                message = it.message,
                priority = it.priority,
                receivedAtMillis = it.receivedAtMillis,
                type = it.type.name,
                distanceCm = it.distanceCm,
                moving = it.moving,
                soundLevel = it.soundLevel,
            )
        }
        prefs.edit { putString(KEY, json.encodeToString(stored)) }
        _events.value = events
    }

    private companion object {
        const val KEY = "events"
        const val MAX_EVENTS = 50
    }
}
