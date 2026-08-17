package com.patbaumgartner.roomwatchdog.data.device

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The device's live telemetry socket. The firmware keeps a single client and closes the previous
 * one, so this holds at most one connection; reconnection and backoff belong to the caller.
 */
class TelemetryStream(
    private val http: OkHttpClient,
    private val deviceClient: DeviceClient,
    private val baseUrl: String,
    private val apiToken: String,
    private val onOpen: () -> Unit,
    private val onFrame: (TelemetryFrame) -> Unit,
    private val onClosed: (unauthorised: Boolean) -> Unit,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private var socket: WebSocket? = null

    @Volatile
    private var active = false

    fun connect() {
        if (active) return
        active = true
        val request = runCatching { deviceClient.telemetryRequest(baseUrl, apiToken) }.getOrElse {
            active = false
            onClosed(false)
            return
        }
        socket = http.newWebSocket(request, Listener())
    }

    fun close() {
        active = false
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
    }

    /** Asks the device to re-baseline the radar; the room has to be empty for it to mean anything. */
    fun requestCalibration(): Boolean = socket?.send("calibrate") == true

    private fun parse(text: String): TelemetryFrame? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return when (root["type"]?.jsonPrimitive?.content) {
            "hello" -> runCatching { json.decodeFromJsonElement(TelemetryFrame.Hello.serializer(), root) }.getOrNull()
            "telemetry" -> runCatching {
                TelemetryFrame.Telemetry(json.decodeFromJsonElement(DeviceStatus.serializer(), root))
            }.getOrNull()

            "event" -> runCatching { json.decodeFromJsonElement(TelemetryFrame.Event.serializer(), root) }.getOrNull()
            else -> null
        }
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (active) onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!active) return
            parse(text)?.let(onFrame)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!active) return
            active = false
            onClosed(false)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!active) return
            active = false
            onClosed(response?.code == 401 || response?.code == 403)
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
