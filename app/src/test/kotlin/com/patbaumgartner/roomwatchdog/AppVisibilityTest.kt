package com.patbaumgartner.roomwatchdog

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVisibilityTest {

    @After
    fun drain() {
        repeat(8) { AppVisibility.onLeaveForeground() }
        AppVisibility.onListeningStopped()
    }

    @Test
    fun `app counts as background until an activity starts`() {
        assertFalse(AppVisibility.isForeground)

        AppVisibility.onEnterForeground()
        assertTrue(AppVisibility.isForeground)

        AppVisibility.onLeaveForeground()
        assertFalse(AppVisibility.isForeground)
    }

    /** Opening the app from a notification starts the new instance before the old one stops. */
    @Test
    fun `handover between two activity instances stays in the foreground`() {
        AppVisibility.onEnterForeground()
        AppVisibility.onEnterForeground()
        AppVisibility.onLeaveForeground()

        assertTrue("the replacement instance is still on screen", AppVisibility.isForeground)

        AppVisibility.onLeaveForeground()
        assertFalse(AppVisibility.isForeground)
    }

    @Test
    fun `an unbalanced stop cannot push the count below zero`() {
        AppVisibility.onLeaveForeground()
        AppVisibility.onEnterForeground()

        assertTrue(AppVisibility.isForeground)
    }

    /** Listening usually happens with the screen off, and the audio says more than a banner would. */
    @Test
    fun `a listening session counts as attending the room`() {
        AppVisibility.onListeningStarted()

        assertFalse("the UI is not on screen", AppVisibility.isForeground)
        assertTrue("but the room is audible", AppVisibility.isAttendingRoom)

        AppVisibility.onListeningStopped()
        assertFalse(AppVisibility.isAttendingRoom)
    }

    @Test
    fun `closing the screen mid-session keeps the session attending`() {
        AppVisibility.onEnterForeground()
        AppVisibility.onListeningStarted()
        AppVisibility.onLeaveForeground()

        assertTrue(AppVisibility.isAttendingRoom)
    }
}
