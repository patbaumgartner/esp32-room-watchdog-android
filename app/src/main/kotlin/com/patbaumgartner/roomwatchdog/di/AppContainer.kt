package com.patbaumgartner.roomwatchdog.di

import android.content.Context
import com.patbaumgartner.roomwatchdog.audio.PcmStreamSession
import com.patbaumgartner.roomwatchdog.data.device.DeviceClient
import com.patbaumgartner.roomwatchdog.data.device.TelemetryFrame
import com.patbaumgartner.roomwatchdog.data.device.TelemetryStream
import com.patbaumgartner.roomwatchdog.data.events.EventStore
import com.patbaumgartner.roomwatchdog.data.gotify.GotifyClient
import com.patbaumgartner.roomwatchdog.data.gotify.GotifyMessage
import com.patbaumgartner.roomwatchdog.data.gotify.GotifyStream
import com.patbaumgartner.roomwatchdog.data.settings.SettingsRepository
import com.patbaumgartner.roomwatchdog.notifications.AlertNotifier
import com.patbaumgartner.roomwatchdog.recordings.RecordingStore
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class AppContainer(context: Context) {

    val settings = SettingsRepository(context)
    val events = EventStore(context)
    val recordings = RecordingStore(context)
    val notifier = AlertNotifier(context)

    private val apiHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .pingInterval(1, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()
    private val streamingHttpClient = apiHttpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Websockets prove they are alive with pings; the audio stream is a plain response body, so its
     * liveness bound is the read itself. At 48 kHz bytes arrive every few milliseconds, and a socket
     * that has produced none for this long is a dead device rather than a slow one. The overall call
     * stays unbounded - a session may legitimately run for hours.
     */
    private val audioHttpClient = streamingHttpClient.newBuilder()
        .readTimeout(AUDIO_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val gotifyClient = GotifyClient(apiHttpClient)
    val deviceClient = DeviceClient(apiHttpClient)

    val streamSession = PcmStreamSession(context, audioHttpClient, deviceClient, recordings)

    fun gotifyStream(
        baseUrl: String,
        clientToken: String,
        onOpen: () -> Unit,
        onMessage: (GotifyMessage) -> Unit,
        onClosed: (Boolean) -> Unit,
    ) = GotifyStream(
        http = streamingHttpClient,
        baseUrl = baseUrl,
        clientToken = clientToken,
        onOpen = onOpen,
        onMessage = onMessage,
        onClosed = onClosed,
    )

    fun telemetryStream(
        baseUrl: String,
        apiToken: String,
        onOpen: () -> Unit,
        onFrame: (TelemetryFrame) -> Unit,
        onClosed: (Boolean) -> Unit,
    ) = TelemetryStream(
        http = streamingHttpClient,
        deviceClient = deviceClient,
        baseUrl = baseUrl,
        apiToken = apiToken,
        onOpen = onOpen,
        onFrame = onFrame,
        onClosed = onClosed,
    )

    private companion object {
        const val AUDIO_READ_TIMEOUT_SECONDS = 15L
    }
}
