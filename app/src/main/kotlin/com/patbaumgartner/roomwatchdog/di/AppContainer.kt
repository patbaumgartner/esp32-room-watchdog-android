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

    val gotifyClient = GotifyClient(apiHttpClient)
    val deviceClient = DeviceClient(apiHttpClient)

    val streamSession = PcmStreamSession(context, streamingHttpClient, deviceClient, recordings)

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
}
