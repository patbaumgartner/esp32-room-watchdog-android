package com.patbaumgartner.android.roomwatchdog.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patbaumgartner.android.roomwatchdog.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented end-to-end UI tests run against the real activity on a device or
 * emulator (CI runs them via `connectedDebugAndroidTest`). No credentials are
 * configured on the test device, so the home screen shows the setup prompt.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun str(res: Int) = compose.activity.getString(res)

    @Test
    fun homeShowsSetupPromptWhenNotConfigured() {
        compose.onNodeWithText(str(R.string.state_tap_setup)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.default_room)).assertIsDisplayed()
    }

    @Test
    fun moreMenuOffersRecordingsAndSettings() {
        compose.onNodeWithContentDescription(str(R.string.menu_more)).performClick()
        compose.onNodeWithText(str(R.string.menu_recordings)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.menu_settings)).assertIsDisplayed()
    }

    @Test
    fun moreMenuNavigatesToSettingsAndBack() {
        compose.onNodeWithContentDescription(str(R.string.menu_more)).performClick()
        compose.onNodeWithText(str(R.string.menu_settings)).performClick()
        compose.onNodeWithText(str(R.string.settings_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.settings_room)).assertIsDisplayed()
    }
}
