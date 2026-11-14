package com.samuel.wirelesswhale.scanner

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samuel.wirelesswhale.feature.scanner.ScannerScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scanner smoke tests. The camera itself needs physical-device evidence,
 * which the parent verifies; these tests pin the screen contract in place:
 * the screen renders, the title and close control exist, and closing
 * reports back exactly once without touching camera hardware state.
 */
@RunWith(AndroidJUnit4::class)
class ScannerScreenTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun scannerShowsTitleAndClose() {
    composeRule.setContent {
      ScannerScreen(onResult = {}, onClose = {})
    }
    composeRule.onNodeWithText("Scan Wi-Fi QR code").assertIsDisplayed()
    composeRule.onNodeWithText("Close").assertIsDisplayed()
  }

  @Test
  fun closeInvokesCallbackOnce() {
    var closes = 0
    composeRule.setContent {
      ScannerScreen(onResult = {}, onClose = { closes++ })
    }
    composeRule.onNodeWithText("Close").performClick()
    composeRule.runOnIdle { assertTrue(closes == 1) }
  }

  @Test
  fun scannerShowsPermissionOrCameraUi() {
    composeRule.setContent {
      ScannerScreen(onResult = {}, onClose = {})
    }
    val permissionPrompt = composeRule.onAllNodesWithText("Allow camera").fetchSemanticsNodes().size
    val cameraInstruction = composeRule.onAllNodesWithText(
      "Point the camera at a Wi-Fi QR code."
    ).fetchSemanticsNodes().size
    val cameraError = composeRule.onAllNodesWithText(
      "The camera could not be started on this device."
    ).fetchSemanticsNodes().size
    assertTrue(permissionPrompt + cameraInstruction + cameraError == 1)
  }
}
