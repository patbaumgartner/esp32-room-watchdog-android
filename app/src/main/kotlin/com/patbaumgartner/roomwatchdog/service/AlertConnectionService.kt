package com.patbaumgartner.roomwatchdog.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.patbaumgartner.roomwatchdog.AppVisibility
import com.patbaumgartner.roomwatchdog.R
import com.patbaumgartner.roomwatchdog.RoomWatchdogApp
import com.patbaumgartner.roomwatchdog.data.gotify.toWatchdogEvent
import com.patbaumgartner.roomwatchdog.data.gotify.GotifyStream
import com.patbaumgartner.roomwatchdog.data.model.WatchdogEvent
import com.patbaumgartner.roomwatchdog.data.model.WatchdogEventType
import com.patbaumgartner.roomwatchdog.data.settings.WatchdogConfig
import com.patbaumgartner.roomwatchdog.notifications.AlertNotifier
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class ConnectionState { Connecting, Connected, Reconnecting, Unauthorised }

/**
 * Keeps one Gotify websocket alive so alerts arrive without a cloud push service.
 * Android requires a foreground service for this, hence the quiet ongoing notification.
 */
class AlertConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: RoomWatchdogApp

    private var stream: GotifyStream? = null
    private var reconnectJob: Job? = null
    private var handshakeJob: Job? = null
    private var attempt = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as RoomWatchdogApp
        startForeground(getString(R.string.status_connecting), null)
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connect()
        return START_STICKY
    }

    override fun onDestroy() {
        stream?.close()
        unregisterNetworkCallback()
        scope.cancel()
        state.value = ConnectionState.Connecting
        super.onDestroy()
    }

    /**
     * Reachable from the system callback thread, the reconnect coroutine and `onStartCommand` at
     * once. Without serialising, two callers each replace [stream] and the loser's socket stays
     * open, delivering every alert twice.
     */
    @Synchronized
    private fun connect() {
        val config = app.container.settings.current
        if (!config.isConfigured) {
            stopSelf()
            return
        }
        reconnectJob?.cancel()
        stream?.close()

        state.value = if (attempt == 0) ConnectionState.Connecting else ConnectionState.Reconnecting

        stream = app.container.gotifyStream(
            baseUrl = config.gotifyUrl,
            clientToken = config.gotifyClientToken,
            onOpen = {
                attempt = 0
                state.value = ConnectionState.Connected
                startForeground(getString(R.string.status_connected), config.roomName)
                scope.launch { syncMissed(config) }
            },
            onMessage = { message -> scope.launch { handle(message.toWatchdogEvent(), config) } },
            onClosed = { unauthorised ->
                if (unauthorised) {
                    state.value = ConnectionState.Unauthorised
                    startForeground(getString(R.string.status_action_required), null)
                } else {
                    scheduleReconnect()
                }
            },
        ).also { it.connect() }

        // The streaming client has no read or call timeout, so a server that accepts the socket
        // and never completes the handshake would leave alerts silently dead. Retry instead.
        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            if (state.value != ConnectionState.Connected && state.value != ConnectionState.Unauthorised) {
                scheduleReconnect()
            }
        }
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        state.value = ConnectionState.Reconnecting
        startForeground(getString(R.string.status_reconnecting), null)
        val delayMillis = backoffMillis(attempt++)
        reconnectJob = scope.launch {
            delay(delayMillis)
            connect()
        }
    }

    private suspend fun syncMissed(config: WatchdogConfig) {
        val since = app.container.settings.current.lastMessageId
        if (since <= 0) return
        app.container.gotifyClient
            .messagesSince(config.gotifyUrl, config.gotifyClientToken, since)
            .getOrNull()
            ?.forEach { handle(it.toWatchdogEvent(), config) }
    }

    private fun handle(event: WatchdogEvent, config: WatchdogConfig) {
        if (event.type == WatchdogEventType.Unknown) {
            app.container.settings.rememberMessageId(event.messageId)
            return
        }
        if (!app.container.events.record(event)) return
        app.container.settings.rememberMessageId(event.messageId)
        if (AppVisibility.isAttendingRoom) {
            // The live screen, or the audio already playing, says this better than a banner would.
            app.container.notifier.cancelEventAlerts()
            return
        }
        app.container.notifier.notifyEvent(event, config.roomName.ifBlank { getString(R.string.app_name) })
    }

    private fun startForeground(title: String, detail: String?) {
        val notification = app.container.notifier.foregroundNotification(title, detail)
        ServiceCompat.startForeground(
            this,
            AlertNotifier.ID_FOREGROUND,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    private fun registerNetworkCallback() {
        val manager = getSystemService<ConnectivityManager>() ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (state.value != ConnectionState.Connected) {
                    attempt = 0
                    connect()
                }
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun unregisterNetworkCallback() {
        val manager = getSystemService<ConnectivityManager>() ?: return
        networkCallback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun backoffMillis(attempt: Int): Long {
        val exponential = MIN_BACKOFF_MS * 2.0.pow(attempt)
        val capped = min(exponential, MAX_BACKOFF_MS.toDouble()).toLong()
        return capped + (0..JITTER_MS).random()
    }

    companion object {
        private const val MIN_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 20 * 60 * 1000L
        private const val JITTER_MS = 2_000L
        private const val HANDSHAKE_TIMEOUT_MS = 20_000L

        /** The connection state drives the ongoing notification; nothing outside the service sets it. */
        private val state = MutableStateFlow(ConnectionState.Connecting)

        fun start(context: Context) {
            val intent = Intent(context, AlertConnectionService::class.java)
            context.startForegroundService(intent)
        }
    }
}
