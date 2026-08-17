package com.patbaumgartner.roomwatchdog.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.patbaumgartner.roomwatchdog.AppVisibility
import com.patbaumgartner.roomwatchdog.R
import com.patbaumgartner.roomwatchdog.data.device.DeviceStatus
import com.patbaumgartner.roomwatchdog.data.device.DeviceException
import com.patbaumgartner.roomwatchdog.data.gotify.GotifyException
import com.patbaumgartner.roomwatchdog.data.model.latestRoomSignals
import com.patbaumgartner.roomwatchdog.data.settings.WatchdogConfig
import com.patbaumgartner.roomwatchdog.notifications.AlertNotifier
import com.patbaumgartner.roomwatchdog.service.AlertConnectionService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val stream: com.patbaumgartner.roomwatchdog.audio.StreamStatus = com.patbaumgartner.roomwatchdog.audio.StreamStatus(),
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
        get() = if (stream.phase == com.patbaumgartner.roomwatchdog.audio.StreamPhase.Live) {
            level
        } else {
            ((deviceStatus?.micPeakToPeak ?: 0) / MIC_FULL_SCALE).coerceIn(0f, 1f)
        }
}

/** Peak-to-peak reading treated as a full-height bar; picked so ordinary room noise stays subtle. */
private const val MIC_FULL_SCALE = 6_000f

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as com.patbaumgartner.roomwatchdog.RoomWatchdogApp
    private val settings = app.container.settings
    private val device = app.container.deviceClient
    private val stream = app.container.streamSession
    private var pollJob: Job? = null
    private var messageJob: Job? = null
    private var soundIndicatorJob: Job? = null

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
    }

    /**
     * Keeps the room reading fresh so the waveform breathes even when nobody is listening. The
     * firmware serves only one audio consumer at a time and blocks /status while streaming, so the
     * poll pauses for the duration of a live session.
     */
    private fun startStatusPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                val config = settings.current
                if (AppVisibility.isForeground && config.isConfigured && !stream.isActive) {
                    device.status(config.deviceUrl, config.apiToken).onSuccess { status ->
                        _state.update {
                            it.copy(
                                deviceStatus = status,
                                lastUpdated = System.currentTimeMillis(),
                                connected = true,
                                presenceDetected = status.presence,
                                error = null,
                            )
                        }
                    }.onFailure {
                        _state.update { it.copy(connected = false) }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun applyIntent(intent: Intent?) {
        val action = app.container.notifier.autoStartFrom(intent) ?: return
        val config = settings.current
        if (config.isConfigured) {
            stream.start(config.deviceUrl, config.apiToken, action == AlertNotifier.AutoStart.RECORD)
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
        if (setup.clientToken.startsWith(GotifyExceptionToken.APPLICATION)) {
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

    /** Re-baselines the radar. The room has to be empty for the reading to mean anything. */
    fun calibrate() {
        val config = settings.current
        if (!config.isConfigured) {
            showMessage(app.getString(R.string.message_setup_required))
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
        if (config.deviceUrl.isBlank() || stream.isActive) return
        viewModelScope.launch {
            device.status(config.deviceUrl, config.apiToken).onSuccess { status ->
                _state.update {
                    it.copy(
                        deviceStatus = status,
                        lastUpdated = System.currentTimeMillis(),
                        connected = true,
                        presenceDetected = status.presence,
                        error = null,
                    )
                }
            }.onFailure {
                _state.update { it.copy(connected = false) }
            }
        }
    }

    fun startListening() {
        val config = settings.current
        if (config.isConfigured) stream.start(config.deviceUrl, config.apiToken)
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
    fun renameRecording(id: String, displayName: String) {
        app.container.recordings.rename(id, displayName)
    }

    fun deleteRecording(id: String): Boolean = app.container.recordings.delete(id)

    fun clearError() = _state.update { it.copy(error = null) }
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

    private object GotifyExceptionToken {
        const val APPLICATION = "gtfya."
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val MESSAGE_DURATION_MS = 4_000L
        const val SOUND_INDICATOR_MS = 8_000L
    }
}
