package com.samuel.wirelesswhale.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samuel.wirelesswhale.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-launch layout regression: the release APK installed on the phone
 * crashed on start with IllegalArgumentException (negative PaddingElement
 * from AppTopBar's -20.dp caller modifier). This test launches the real
 * MainActivity (which already sets content) and walks the four capsule tabs
 * so the production Home/Wifi/Hotspot/Tools render path executes.
 *
 * NOT RUN here (no build or phone in this task). No network actions, no
 * permission grants, no radio toggles -- only tab navigation.
 */
@RunWith(AndroidJUnit4::class)
class LaunchLayoutSmokeTest {

  @get:Rule
  val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun launchRendersAndNavigatesFourTabs() {
    composeTestRule.onNodeWithTag("whaleCapsuleNav").assertIsDisplayed()
    composeTestRule.onNodeWithTag("whaleTopBar").assertIsDisplayed()

    val tabs = listOf("whaleTabWifi", "whaleTabHotspot", "whaleTabTools", "whaleTabHome")
    for (tab in tabs) {
      composeTestRule.onNodeWithTag(tab).performClick()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("whaleCapsuleNav").assertIsDisplayed()
      composeTestRule.onNodeWithTag("whaleTopBar").assertIsDisplayed()
    }
  }
}
