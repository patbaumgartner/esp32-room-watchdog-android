package com.patbaumgartner.roomwatchdog.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.patbaumgartner.roomwatchdog.AppVisibility
import com.patbaumgartner.roomwatchdog.R
import com.patbaumgartner.roomwatchdog.RoomWatchdogApp
import com.patbaumgartner.roomwatchdog.audio.StreamPhase
import com.patbaumgartner.roomwatchdog.audio.StreamStatus
import com.patbaumgartner.roomwatchdog.data.device.DeviceClient
import com.patbaumgartner.roomwatchdog.data.device.DeviceStatus
import com.patbaumgartner.roomwatchdog.data.device.DeviceException
import com.patbaumgartner.roomwatchdog.data.device.TelemetryFrame
import com.patbaumgartner.roomwatchdog.data.device.TelemetryStream
import com.patbaumgartner.roomwatchdog.data.gotify.GotifyClient
import com.patbaumgartner.roomwatchdog.data.gotify.GotifyException
import com.patbaumgartner.roomwatchdog.data.model.latestRoomSignals
import com.patbaumgartner.roomwatchdog.data.settings.WatchdogConfig
import com.patbaumgartner.roomwatchdog.notifications.AlertNotifier
import com.patbaumgartner.roomwatchdog.service.AlertConnectionService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface AppScreen {
    data object Home : AppScreen
    data object Recordings : AppScreen
    data object Settings : AppScreen
}

data class SetupState(
    val roomName: String = "",
    val deviceUrl: String = "",
    val apiToken: String = "",
    val gotifyUrl: String = "",
    val clientToken: String = "",
    val error: String? = null,
    val busy: Boolean = false,
)

data class HomeState(
    val screen: AppScreen = AppScreen.Home,
    val config: WatchdogConfig = WatchdogConfig(),
    val deviceStatus: DeviceStatus? = null,
    val lastUpdated: Long? = null,
    val connected: Boolean = false,
    val stream: StreamStatus = StreamStatus(),
    val level: Float = 0f,
    val presenceDetected: Boolean = false,
    val soundDetected: Boolean = false,
    val lastSoundAtMillis: Long? = null,
    val setup: SetupState = SetupState(),
    val error: String? = null,
    val message: String? = null,
) {
    /**
     * One amplitude for the waveform: the live stream while listening, otherwise the microphone
     * level the device reports over /status.
     */
    val waveLevel: Float
        get() = if (stream.phase == StreamPhase.Live) {
            level
        } else {
            ((deviceStatus?.micPeakToPeak ?: 0) / MIC_FULL_SCALE).coerceIn(0f, 1f)
        }
}

/** Peak-to-peak reading treated as a full-height bar; picked so ordinary room noise stays subtle. */
private const val MIC_FULL_SCALE = 6_000f

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RoomWatchdogApp
    private val settings = app.container.settings
    private val device = app.container.deviceClient
    private val stream = app.container.streamSession
    private var pollJob: Job? = null
    private var telemetryJob: Job? = null
    private var messageJob: Job? = null
    private var soundIndicatorJob: Job? = null

    @Volatile
    private var telemetry: TelemetryStream? = null

    @Volatile
    private var audioPort = DeviceClient.DEFAULT_AUDIO_PORT

    private val _state = MutableStateFlow(
        HomeState(
            screen = AppScreen.Home,
            config = settings.current,
            setup = setupFrom(
                if (settings.current.isConfigured) settings.current else settings.developerDefaults(),
            ),
        ),
    )
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(settings.config, stream.status, stream.level) { config, streamStatus, level ->
                Triple(config, streamStatus, level)
            }.collect { (config, streamStatus, level) ->
                _state.update {
                    it.copy(
                        config = config,
                        stream = streamStatus,
                        level = level,
                    )
                }
            }
        }
        viewModelScope.launch {
            app.container.events.events.collect { events ->
                val signals = events.latestRoomSignals()
                val soundAt = signals.lastSoundAtMillis
                val soundRemaining = soundAt?.let {
                    SOUND_INDICATOR_MS - (System.currentTimeMillis() - it)
                } ?: 0L
                _state.update {
                    it.copy(
                        presenceDetected = if (
                            signals.presenceAtMillis != null &&
                            signals.presenceAtMillis >= (it.lastUpdated ?: 0L)
                        ) {
                            signals.presence ?: it.presenceDetected
                        } else {
                            it.presenceDetected
                        },
                        soundDetected = soundRemaining > 0,
                        lastSoundAtMillis = soundAt,
                    )
                }
                soundIndicatorJob?.cancel()
                if (soundAt != null && soundRemaining > 0) {
                    soundIndicatorJob = viewModelScope.launch {
                        delay(soundRemaining)
                        _state.update { current ->
                            if (current.lastSoundAtMillis == soundAt) {
                                current.copy(soundDetected = false)
                            } else {
                                current
                            }
                        }
                    }
                }
            }
        }
        startStatusPolling()
        startTelemetry()
    }

    /**
     * Live telemetry rides the device's WebSocket: it pushes a frame whenever the room changes and
     * a heartbeat otherwise, so the UI follows the sensor instead of a poll timer. The device keeps
     * one telemetry client, so the socket is dropped whenever the app leaves the foreground.
     */
    private fun startTelemetry() {
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch {
            var backoffMs = TELEMETRY_MIN_BACKOFF_MS
            while (true) {
                val config = settings.current
                if (!AppVisibility.isForeground || !config.isConfigured) {
                    closeTelemetry()
                    delay(TELEMETRY_IDLE_CHECK_MS)
                    continue
                }
                if (telemetry == null) {
                    val connected = CompletableDeferred<Boolean>()
                    val socket = app.container.telemetryStream(
                        baseUrl = config.deviceUrl,
                        apiToken = config.apiToken,
                        onOpen = {
                            connected.complete(true)
                            _state.update { it.copy(connected = true, error = null) }
                        },
                        onFrame = ::onTelemetryFrame,
                        onClosed = {
                            connected.complete(false)
                            telemetry = null
                            _state.update { current -> current.copy(connected = false) }
                        },
                    )
                    telemetry = socket
                    socket.connect()

                    // The streaming client deliberately has no read or call timeout, so a server
                    // that accepts the connection and then says nothing would hang this loop.
                    val opened = withTimeoutOrNull(TELEMETRY_HANDSHAKE_MS) { connected.await() }
                    if (opened != true) closeTelemetry()
                    backoffMs = if (opened == true) {
                        TELEMETRY_MIN_BACKOFF_MS
                    } else {
                        minOf(backoffMs * 2, TELEMETRY_MAX_BACKOFF_MS)
                    }
                }
                delay(backoffMs)
            }
        }
    }

    private fun closeTelemetry() {
        telemetry?.close()
        telemetry = null
    }

    private fun onTelemetryFrame(frame: TelemetryFrame) {
        when (frame) {
            is TelemetryFrame.Hello -> {
                audioPort = frame.audioPort
            }

            is TelemetryFrame.Telemetry -> _state.update {
                it.copy(
                    deviceStatus = frame.status,
                    lastUpdated = System.currentTimeMillis(),
                    connected = true,
                    presenceDetected = frame.status.presence,
                    error = null,
                )
            }

            is TelemetryFrame.Event -> onTelemetryEvent(frame)
        }
    }

    /** The socket reports sound before Gotify does, so the indicator follows it directly. */
    private fun onTelemetryEvent(event: TelemetryFrame.Event) {
        when (event.event) {
            TelemetryFrame.Event.Kind.Sound -> {
                val now = System.currentTimeMillis()
                _state.update { it.copy(soundDetected = true, lastSoundAtMillis = now) }
                soundIndicatorJob?.cancel()
                soundIndicatorJob = viewModelScope.launch {
                    delay(SOUND_INDICATOR_MS)
                    _state.update { current ->
                        if (current.lastSoundAtMillis == now) current.copy(soundDetected = false) else current
                    }
                }
            }

            TelemetryFrame.Event.Kind.Presence ->
                _state.update { it.copy(presenceDetected = true) }

            TelemetryFrame.Event.Kind.Cleared ->
                _state.update { it.copy(presenceDetected = false) }

            else -> Unit
        }
    }

    /**
     * Fallback for when the telemetry socket is down: the REST snapshot keeps the room reading from
     * going stale. It pauses during a session because firmware old enough to lack the socket also
     * serves audio from the API port, and answering /status there drops the stream.
     */
    private fun startStatusPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                val config = settings.current
                if (AppVisibility.isForeground && config.isConfigured && telemetry == null && !stream.isActive) {
                    device.status(config.deviceUrl, config.apiToken)
                        .onSuccess(::onStatus)
                        .onFailure { _state.update { it.copy(connected = false) } }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun onStatus(status: DeviceStatus) = _state.update {
        it.copy(
            deviceStatus = status,
            lastUpdated = System.currentTimeMillis(),
            connected = true,
            presenceDetected = status.presence,
            error = null,
        )
    }

    fun applyIntent(intent: Intent?) {
        val action = app.container.notifier.autoStartFrom(intent) ?: return
        val config = settings.current
        if (config.isConfigured) {
            stream.start(
                baseUrl = config.deviceUrl,
                apiToken = config.apiToken,
                alsoRecord = action == AlertNotifier.AutoStart.RECORD,
                audioPort = audioPort,
            )
        }
    }

    fun updateSetup(update: SetupState.() -> SetupState) {
        _state.update { it.copy(setup = it.setup.update()) }
    }

    fun useDeveloperSetup() {
        val defaults = settings.developerDefaults()
        updateSetup {
            copy(
                roomName = defaults.roomName,
                deviceUrl = defaults.deviceUrl,
                apiToken = defaults.apiToken,
                gotifyUrl = defaults.gotifyUrl,
                clientToken = defaults.gotifyClientToken,
                error = null,
            )
        }
    }

    fun saveSetup() {
        val setup = _state.value.setup
        if (setup.deviceUrl.isBlank() || setup.apiToken.isBlank() || setup.gotifyUrl.isBlank() || setup.clientToken.isBlank()) {
            updateSetup { copy(error = app.getString(R.string.error_setup_missing)) }
            return
        }
        if (setup.clientToken.startsWith(GotifyClient.APPLICATION_TOKEN_PREFIX)) {
            updateSetup { copy(error = app.getString(R.string.error_app_token)) }
            return
        }
        updateSetup { copy(busy = true, error = null) }
        viewModelScope.launch {
            val deviceResult = device.status(setup.deviceUrl, setup.apiToken)
            if (deviceResult.isFailure) {
                updateSetup { copy(busy = false, error = deviceSetupError(deviceResult.exceptionOrNull())) }
                return@launch
            }
            val gotifyResult = app.container.gotifyClient.currentUser(setup.gotifyUrl, setup.clientToken)
            if (gotifyResult.isFailure) {
                updateSetup { copy(busy = false, error = gotifySetupError(gotifyResult.exceptionOrNull())) }
                return@launch
            }
            settings.save(
                WatchdogConfig(
                    roomName = setup.roomName.ifBlank { app.getString(R.string.default_room) },
                    deviceUrl = setup.deviceUrl,
                    apiToken = setup.apiToken,
                    gotifyUrl = setup.gotifyUrl,
                    gotifyClientToken = setup.clientToken,
                ),
            )
            updateSetup { copy(busy = false, error = null) }
            AlertConnectionService.start(getApplication())
            _state.update { it.copy(screen = AppScreen.Home, connected = true) }
            showMessage(app.getString(R.string.message_settings_saved))
        }
    }

    fun localNetworkPermissionDenied() {
        updateSetup { copy(error = app.getString(R.string.error_local_network_permission)) }
        _state.update { it.copy(screen = AppScreen.Settings) }
    }

    /** Re-baselines the radar. The room has to be empty for the reading to mean anything. */
    fun calibrate() {
        val config = settings.current
        if (!config.isConfigured) {
            showMessage(app.getString(R.string.message_setup_required))
            return
        }
        if (telemetry?.requestCalibration() == true) {
            showMessage(app.getString(R.string.message_calibrating))
            return
        }
        viewModelScope.launch {
            device.calibrate(config.deviceUrl, config.apiToken)
                .onSuccess { showMessage(app.getString(R.string.message_calibrating)) }
                .onFailure { showMessage(app.getString(R.string.message_calibration_failed)) }
        }
    }

    fun refreshStatus() {
        val config = settings.current
        if (config.deviceUrl.isBlank() || telemetry != null || stream.isActive) return
        viewModelScope.launch {
            device.status(config.deviceUrl, config.apiToken)
                .onSuccess(::onStatus)
                .onFailure { _state.update { it.copy(connected = false) } }
        }
    }

    fun startListening() {
        val config = settings.current
        if (config.isConfigured) stream.start(config.deviceUrl, config.apiToken, audioPort = audioPort)
    }

    fun stopListening() {
        stream.stop()
        viewModelScope.launch {
            delay(250)
            refreshStatus()
        }
    }

    fun startRecording() = stream.startRecording()
    fun stopRecording() = stream.stopRecording()
    fun toggleMuted() = stream.toggleMuted()
    fun toggleNoiseFilter() = stream.toggleNoiseFilter()
    fun renameRecording(id: String, displayName: String) {
        app.container.recordings.rename(id, displayName)
    }

    fun deleteRecording(id: String): Boolean = app.container.recordings.delete(id)

    fun openRecordings() = _state.update { it.copy(screen = AppScreen.Recordings) }

    fun openSettings() = _state.update { state ->
        // Show what is actually stored; fall back to the pre-filled draft on a fresh install.
        val setup = if (state.config.isConfigured) setupFrom(state.config) else state.setup
        state.copy(screen = AppScreen.Settings, setup = setup.copy(error = null, busy = false))
    }

    fun goHome() = _state.update { it.copy(screen = AppScreen.Home) }

    private fun showMessage(text: String) {
        _state.update { it.copy(message = text) }
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(MESSAGE_DURATION_MS)
            _state.update { it.copy(message = null) }
        }
    }

    override fun onCleared() {
        stream.stop()
        closeTelemetry()
    }

    private fun deviceSetupError(error: Throwable?): String = when ((error as? DeviceException)?.kind) {
        DeviceException.Kind.Auth -> app.getString(R.string.error_device_auth)
        DeviceException.Kind.InvalidUrl -> app.getString(R.string.error_device_url)
        DeviceException.Kind.Insecure -> app.getString(R.string.error_device_insecure)
        else -> app.getString(R.string.error_device_unreachable)
    }

    private fun gotifySetupError(error: Throwable?): String = when ((error as? GotifyException)?.kind) {
        GotifyException.Kind.NotHttps -> app.getString(R.string.error_gotify_https)
        GotifyException.Kind.Auth -> app.getString(R.string.error_gotify_auth)
        GotifyException.Kind.ApplicationToken -> app.getString(R.string.error_app_token)
        else -> app.getString(R.string.error_gotify_unreachable)
    }

    private fun setupFrom(config: WatchdogConfig) = SetupState(
        roomName = config.roomName,
        deviceUrl = config.deviceUrl,
        apiToken = config.apiToken,
        gotifyUrl = config.gotifyUrl,
        clientToken = config.gotifyClientToken,
    )

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val MESSAGE_DURATION_MS = 4_000L
        const val SOUND_INDICATOR_MS = 8_000L
        const val TELEMETRY_MIN_BACKOFF_MS = 2_000L
        const val TELEMETRY_MAX_BACKOFF_MS = 30_000L
        const val TELEMETRY_IDLE_CHECK_MS = 1_000L
        const val TELEMETRY_HANDSHAKE_MS = 15_000L
    }
}
