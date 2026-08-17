package com.patbaumgartner.roomwatchdog

/**
 * Tracks whether the user is currently looking at the app.
 *
 * While the UI is on screen the live status view already communicates presence and sound, so the
 * background service stays silent instead of stacking redundant notifications on top of it.
 */
object AppVisibility {

    @Volatile
    var isForeground: Boolean = false
        private set

    fun onEnterForeground() {
        isForeground = true
    }

    fun onLeaveForeground() {
        isForeground = false
    }
}
