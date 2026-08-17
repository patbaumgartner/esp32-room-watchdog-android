package com.patbaumgartner.roomwatchdog.data.settings

import android.content.Context
import androidx.core.content.edit
import com.patbaumgartner.roomwatchdog.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WatchdogConfig(
    val roomName: String = "",
    val deviceUrl: String = "",
    val apiToken: String = "",
    val gotifyUrl: String = "",
    val gotifyClientToken: String = "",
    val gotifyAppId: Long = ALL_APPLICATIONS,
    val lastMessageId: Long = 0,
) {
    val isConfigured: Boolean
        get() = deviceUrl.isNotBlank() && apiToken.isNotBlank() &&
                gotifyUrl.isNotBlank() && gotifyClientToken.isNotBlank()

    companion object {
        const val ALL_APPLICATIONS = -1L
    }
}

class SettingsRepository(context: Context) {

    private val defaultRoomName = context.getString(com.patbaumgartner.roomwatchdog.R.string.default_room)
    private val prefs = context.getSharedPreferences("watchdog_settings", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    private val _config = MutableStateFlow(read())
    val config: StateFlow<WatchdogConfig> = _config.asStateFlow()

    val current: WatchdogConfig get() = _config.value

    /** Debug builds prefill from local.properties so development needs no typing. */
    fun developerDefaults() = WatchdogConfig(
        roomName = BuildConfig.DEV_ROOM_NAME.ifBlank { defaultRoomName },
        deviceUrl = BuildConfig.DEV_DEVICE_URL,
        apiToken = BuildConfig.DEV_API_TOKEN,
        gotifyUrl = BuildConfig.DEV_GOTIFY_URL,
        gotifyClientToken = BuildConfig.DEV_GOTIFY_CLIENT_TOKEN,
    )

    private fun read() = WatchdogConfig(
        roomName = prefs.getString(KEY_ROOM, "").orEmpty(),
        deviceUrl = prefs.getString(KEY_DEVICE_URL, "").orEmpty(),
        apiToken = secrets.get(KEY_API_TOKEN).orEmpty(),
        gotifyUrl = prefs.getString(KEY_GOTIFY_URL, "").orEmpty(),
        gotifyClientToken = secrets.get(KEY_GOTIFY_TOKEN).orEmpty(),
        gotifyAppId = prefs.getLong(KEY_APP_ID, WatchdogConfig.ALL_APPLICATIONS),
        lastMessageId = prefs.getLong(KEY_LAST_MESSAGE, 0),
    )

    fun save(config: WatchdogConfig) {
        prefs.edit {
            putString(KEY_ROOM, config.roomName)
            putString(KEY_DEVICE_URL, config.deviceUrl.trimEnd('/'))
            putString(KEY_GOTIFY_URL, config.gotifyUrl.trimEnd('/'))
            putLong(KEY_APP_ID, config.gotifyAppId)
            putLong(KEY_LAST_MESSAGE, config.lastMessageId)
        }
        secrets.put(KEY_API_TOKEN, config.apiToken)
        secrets.put(KEY_GOTIFY_TOKEN, config.gotifyClientToken)
        _config.value = read()
    }

    fun rememberMessageId(id: Long) {
        if (id <= _config.value.lastMessageId) return
        prefs.edit { putLong(KEY_LAST_MESSAGE, id) }
        _config.value = _config.value.copy(lastMessageId = id)
    }

    fun clear() {
        prefs.edit { clear() }
        secrets.clear()
        _config.value = read()
    }

    private companion object {
        const val KEY_ROOM = "room_name"
        const val KEY_DEVICE_URL = "device_url"
        const val KEY_GOTIFY_URL = "gotify_url"
        const val KEY_APP_ID = "gotify_app_id"
        const val KEY_LAST_MESSAGE = "last_message_id"
        const val KEY_API_TOKEN = "api_token"
        const val KEY_GOTIFY_TOKEN = "gotify_client_token"
    }
}
