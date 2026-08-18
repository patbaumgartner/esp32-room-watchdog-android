package com.patbaumgartner.roomwatchdog

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks whether the user is already being told what an alert would say.
 *
 * That is true while the UI is on screen, and also while a listening session is running: the phone
 * is playing the room live, usually with the screen off in a pocket, so a "sound detected" banner
 * is noise on top of the sound itself.
 *
 * Activities are counted rather than flagged: opening the app from a notification action can start
 * a second instance before the first one stops, and a boolean would then be left reading
 * "background" while the app is plainly on screen.
 */
object AppVisibility {

    private val startedActivities = AtomicInteger(0)

    @Volatile
    private var listening = false

    val isForeground: Boolean get() = startedActivities.get() > 0

    val isAttendingRoom: Boolean get() = isForeground || listening

    fun onEnterForeground() {
        startedActivities.incrementAndGet()
    }

    fun onLeaveForeground() {
        startedActivities.updateAndGet { started -> if (started > 0) started - 1 else 0 }
    }

    fun onListeningStarted() {
        listening = true
    }

    fun onListeningStopped() {
        listening = false
    }
}
