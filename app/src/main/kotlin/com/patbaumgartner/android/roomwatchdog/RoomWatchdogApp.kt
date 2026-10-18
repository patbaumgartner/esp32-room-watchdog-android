package com.patbaumgartner.android.roomwatchdog

import android.app.Application
import com.patbaumgartner.android.roomwatchdog.di.AppContainer

class RoomWatchdogApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
    }
}
