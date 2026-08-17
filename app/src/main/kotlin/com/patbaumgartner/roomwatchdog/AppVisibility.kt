package com.patbaumgartner.roomwatchdog

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks whether the user is currently looking at the app.
 *
 * While the UI is on screen the live status view already communicates presence and sound, so the
 * background service stays silent instead of stacking redundant notifications on top of it.
 *
 * Counted rather than a flag: opening the app from a notification action can start a second
 * activity instance before the first one stops, and a boolean would then be left reading
 * "background" while the app is plainly on screen.
 */
object AppVisibility {

    private val startedActivities = AtomicInteger(0)

    val isForeground: Boolean get() = startedActivities.get() > 0

    fun onEnterForeground() {
        startedActivities.incrementAndGet()
    }

    fun onLeaveForeground() {
        startedActivities.updateAndGet { started -> if (started > 0) started - 1 else 0 }
    }
}
