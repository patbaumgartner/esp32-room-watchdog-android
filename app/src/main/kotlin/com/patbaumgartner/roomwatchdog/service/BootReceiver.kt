package com.patbaumgartner.roomwatchdog.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.patbaumgartner.roomwatchdog.RoomWatchdogApp

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? RoomWatchdogApp ?: return
        if (app.container.settings.current.isConfigured) {
            AlertConnectionService.start(context)
        }
    }
}
