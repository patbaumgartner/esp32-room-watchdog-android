package com.patbaumgartner.roomwatchdog.data.gotify

import com.patbaumgartner.roomwatchdog.data.network.gotifyBaseUrl
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Single Gotify `/stream` websocket. Reconnection is driven by the caller so the
 * service owns one backoff policy.
 */
class GotifyStream(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val clientToken: String,
    private val onOpen: () -> Unit,
    private val onMessage: (GotifyMessage) -> Unit,
    private val onClosed: (unauthorised: Boolean) -> Unit,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private var socket: WebSocket? = null
    @Volatile
    private var active = false

    fun connect() {
        if (active) return
        active = true
        val url = runCatching {
            gotifyBaseUrl(baseUrl).newBuilder()
                .scheme("wss")
                .addPathSegment("stream")
                .addQueryParameter("token", clientToken)
                .build()
        }.getOrElse {
            active = false
            onClosed(true)
            return
        }
        val request = Request.Builder()
            .url(url)
            .build()
        socket = http.newWebSocket(request, Listener())
    }

    fun close() {
        active = false
        socket?.close(1000, null)
        socket = null
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (active) onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!active) return
            runCatching { json.decodeFromString<GotifyMessage>(text) }
                .getOrNull()
                ?.let(onMessage)
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
}
