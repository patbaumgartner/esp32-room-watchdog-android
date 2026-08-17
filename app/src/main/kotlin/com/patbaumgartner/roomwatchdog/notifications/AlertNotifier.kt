package com.patbaumgartner.roomwatchdog.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import com.patbaumgartner.roomwatchdog.AppVisibility
import com.patbaumgartner.roomwatchdog.R
import com.patbaumgartner.roomwatchdog.data.model.WatchdogEvent
import com.patbaumgartner.roomwatchdog.data.model.WatchdogEventType
import java.security.MessageDigest
import java.security.SecureRandom

class AlertNotifier(private val context: Context) {

    private val manager = context.getSystemService<NotificationManager>()!!
    private val intentToken: String by lazy {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(KEY_INTENT_TOKEN, null) ?: ByteArray(INTENT_TOKEN_BYTES)
            .also(SecureRandom()::nextBytes)
            .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE) }
            .also { token -> preferences.edit { putString(KEY_INTENT_TOKEN, token) } }
    }

    fun createChannels() {
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.channel_alerts),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alerts_description)
            enableVibration(true)
        }
        val status = NotificationChannel(
            CHANNEL_STATUS,
            context.getString(R.string.channel_status),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = context.getString(R.string.channel_status_description)
            setShowBadge(false)
        }
        manager.createNotificationChannels(listOf(alerts, status))
    }

    fun foregroundNotification(state: String, detail: String?) =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_watchdog)
            .setContentTitle(state)
            .setContentText(detail)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentIntent(openApp(AutoStart.NONE))
            .build()

    fun notifyEvent(event: WatchdogEvent, roomName: String) {
        if (AppVisibility.isForeground) {
            cancelEventAlerts()
            return
        }
        when (event.type) {
            WatchdogEventType.PresenceCleared -> {
                manager.cancel(ID_PRESENCE)
                return
            }

            WatchdogEventType.Online, WatchdogEventType.CalibrationStarted -> {
                manager.notify(ID_STATE, quiet(event, roomName))
                return
            }

            else -> Unit
        }

        val id = if (event.type == WatchdogEventType.SoundDetected) ID_SOUND else ID_PRESENCE
        // Movement silently refreshes the presence alert instead of alerting again.
        val silent = event.type == WatchdogEventType.Movement

        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_watchdog)
            .setContentTitle(headline(event, roomName))
            .setContentText(event.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.message))
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent)
            .setSilent(silent)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentIntent(openApp(AutoStart.NONE))
            .addAction(0, context.getString(R.string.action_listen), openApp(AutoStart.LISTEN))
            .addAction(0, context.getString(R.string.action_record), openApp(AutoStart.RECORD))

        manager.notify(id, builder.build())
    }

    /** Accepts auto-start actions only when they came from one of this app's immutable PendingIntents. */
    fun autoStartFrom(intent: Intent?): String? {
        val action = intent?.getStringExtra(EXTRA_AUTO_START) ?: return null
        if (action != AutoStart.LISTEN && action != AutoStart.RECORD) return null
        val suppliedToken = intent.getStringExtra(EXTRA_INTENT_TOKEN) ?: return null
        val trusted = MessageDigest.isEqual(
            suppliedToken.toByteArray(Charsets.UTF_8),
            intentToken.toByteArray(Charsets.UTF_8),
        )
        return action.takeIf { trusted }
    }

    /** Clears every event alert, leaving the silent foreground notification untouched. */
    fun cancelEventAlerts() {
        manager.cancel(ID_PRESENCE)
        manager.cancel(ID_SOUND)
        manager.cancel(ID_STATE)
    }

    private fun quiet(event: WatchdogEvent, roomName: String) =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_watchdog)
            .setContentTitle(headline(event, roomName))
            .setContentText(event.message)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp(AutoStart.NONE))
            .build()

    private fun headline(event: WatchdogEvent, roomName: String): String = when (event.type) {
        WatchdogEventType.PresenceDetected, WatchdogEventType.Movement ->
            context.getString(R.string.alert_presence, roomName)

        WatchdogEventType.SoundDetected -> context.getString(R.string.alert_sound, roomName)
        WatchdogEventType.Online -> context.getString(R.string.alert_online, roomName)
        WatchdogEventType.CalibrationStarted -> context.getString(R.string.alert_calibrating, roomName)
        WatchdogEventType.PresenceCleared -> context.getString(R.string.alert_cleared, roomName)
        WatchdogEventType.Unknown -> roomName
    }

    private fun openApp(autoStart: String): PendingIntent {
        val intent = Intent()
            .setClassName(context, MAIN_ACTIVITY)
            .setAction("$ACTION_OPEN.$autoStart")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_AUTO_START, autoStart)
            .putExtra(EXTRA_INTENT_TOKEN, intentToken)
        return PendingIntent.getActivity(
            context,
            autoStart.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    object AutoStart {
        const val NONE = "none"
        const val LISTEN = "listen"
        const val RECORD = "record"
    }

    companion object {
        const val CHANNEL_ALERTS = "watchdog_alerts"
        const val CHANNEL_STATUS = "watchdog_status"
        const val ID_FOREGROUND = 1
        const val ID_PRESENCE = 2
        const val ID_SOUND = 3
        const val ID_STATE = 4
        const val EXTRA_AUTO_START = "auto_start"
        private const val EXTRA_INTENT_TOKEN = "notification_intent_token"
        private const val ACTION_OPEN = "com.patbaumgartner.roomwatchdog.OPEN"
        private const val MAIN_ACTIVITY = "com.patbaumgartner.roomwatchdog.ui.MainActivity"
        private const val PREFERENCES = "watchdog_notification_intents"
        private const val KEY_INTENT_TOKEN = "token"
        private const val INTENT_TOKEN_BYTES = 32
    }
}
