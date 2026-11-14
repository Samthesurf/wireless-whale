package com.samuel.wirelesswhale.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.samuel.wirelesswhale.core.model.DiagnosticReport
import com.samuel.wirelesswhale.core.model.EnhancedAccessState
import com.samuel.wirelesswhale.core.model.HistoryEntry
import com.samuel.wirelesswhale.core.model.HotspotClient
import com.samuel.wirelesswhale.core.model.HotspotSnapshot
import com.samuel.wirelesswhale.core.model.HotspotStatus
import com.samuel.wirelesswhale.core.model.ScanState
import com.samuel.wirelesswhale.core.model.WifiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Navigation regressions for the app shell. Written for the parent's
 * real-device pass; NOT RUN here (no build or phone in this task).
 *
 * Stable tags (whaleTabHome and siblings, whaleCapsuleNav, whaleTopBar)
 * double as UiAutomator resource ids once the harness enables
 * testTagsAsResourceId. Human accessibility names are unchanged.
 */
class WirelessWhaleAppNavTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun externalHotspotRequestNavigatesFromTools() {
    var destination by mutableStateOf("home")
    var requestId by mutableStateOf(0L)
    composeTestRule.setContent {
      NavTestApp(destination = destination, requestId = requestId)
    }
    composeTestRule.onNodeWithTag("whaleTabTools").performClick()
    composeTestRule.onNodeWithText("Diagnose").assertIsDisplayed()
    composeTestRule.runOnIdle {
      destination = "hotspot"
      requestId = 1L
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Hotspot is off").assertIsDisplayed()
  }

  @Test
  fun repeatedSameRouteRequestResetsDetailToTab() {
    var destination by mutableStateOf("hotspot")
    var requestId by mutableStateOf(0L)
    composeTestRule.setContent {
      NavTestApp(
        destination = destination,
        requestId = requestId,
        hotspot = HotspotSnapshot(
          status = HotspotStatus.ON,
          ssid = "Whale Spot",
          clients = listOf(
            HotspotClient(
              "c1",
              macAddress = "aa:bb:cc:dd:ee:01",
              name = "Fixture Laptop",
              addresses = listOf("192.168.43.10"),
              connected = true,
              lastSeenAtMillis = System.currentTimeMillis() - 60_000,
            ),
          ),
          enhancedAccess = EnhancedAccessState.NOT_INSTALLED,
          canToggle = true,
        ),
      )
    }
    composeTestRule.onNodeWithText("Fixture Laptop").performClick()
    composeTestRule.onNodeWithText("Observed identity").assertIsDisplayed()
    composeTestRule.runOnIdle { requestId = 1L }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Observed identity").assertDoesNotExist()
    composeTestRule.onNodeWithText("Hotspot is on").assertIsDisplayed()
  }

  @Test
  fun ordinaryRecompositionKeepsUserTab() {
    var wifiSsid by mutableStateOf("Home Fibre")
    composeTestRule.setContent {
      NavTestApp(destination = "home", requestId = 0L, wifiSsid = wifiSsid)
    }
    composeTestRule.onNodeWithTag("whaleTabTools").performClick()
    composeTestRule.onNodeWithText("Diagnose").assertIsDisplayed()
    composeTestRule.runOnIdle { wifiSsid = "Office Fibre" }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Diagnose").assertIsDisplayed()
  }

  @Test
  fun homeRunCheckWithHistoryNavigatesAndRuns() {
    var runs = 0
    composeTestRule.setContent {
      NavTestApp(
        destination = "home",
        requestId = 0L,
        history = listOf(
          HistoryEntry(
            "h1",
            "Connection check",
            "2 of 5 checks failed",
            System.currentTimeMillis() - 3_600_000,
            "diagnostics",
          ),
        ),
        onRunDiagnostics = { runs += 1 },
      )
    }
    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Run check"))
    composeTestRule.onNodeWithText("Run check").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("External probe").assertIsDisplayed()
    assertEquals(1, runs)
  }

  @Test
  fun homeRunCheckWithoutHistoryNavigatesWithoutRunning() {
    var runs = 0
    composeTestRule.setContent {
      NavTestApp(destination = "home", requestId = 0L, onRunDiagnostics = { runs += 1 })
    }
    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Run check"))
    composeTestRule.onNodeWithText("Run check").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("External probe").assertIsDisplayed()
    assertEquals(0, runs)
  }

  @Test
  fun pushedDetailSurvivesRecreation() {
    val restorationTester = StateRestorationTester(composeTestRule)
    restorationTester.setContent {
      NavTestApp(destination = "home", requestId = 0L)
    }
    composeTestRule.onNodeWithTag("whaleTabTools").performClick()
    composeTestRule.onNodeWithText("Connection check").performClick()
    composeTestRule.onNodeWithText("External probe").assertIsDisplayed()
    restorationTester.emulateSavedInstanceStateRestore()
    composeTestRule.onNodeWithText("External probe").assertIsDisplayed()
  }
}

@Composable
private fun NavTestApp(
  destination: String,
  requestId: Long,
  wifiSsid: String = "Home Fibre",
  hotspot: HotspotSnapshot = HotspotSnapshot(status = HotspotStatus.OFF),
  history: List<HistoryEntry> = emptyList(),
  onRunDiagnostics: () -> Unit = {},
) {
  val now = System.currentTimeMillis()
  WirelessWhaleApp(
    wifi = WifiSnapshot(
      enabled = true,
      connected = true,
      ssid = wifiSsid,
      lastUpdatedAtMillis = now - 60_000,
    ),
    networks = emptyList(),
    scanState = ScanState(lastScanAtMillis = now - 60_000),
    hotspot = hotspot,
    diagnosticReport = DiagnosticReport(),
    diagnosticRunning = false,
    discoveredDevices = emptyList(),
    discoveryRunning = false,
    history = history,
    initialDestination = destination,
    onRequestWifiPermission = {},
    onOpenWifiSettings = {},
    onRefreshNetworks = {},
    onConnectNetwork = {},
    onOpenScanner = {},
    onShareCredentials = {},
    onOpenHotspotSettings = {},
    onToggleHotspot = {},
    onRequestEnhancedAccess = {},
    onBlockClient = { _, _ -> },
    onRunDiagnostics = onRunDiagnostics,
    onCancelDiagnostics = {},
    onStartDiscovery = {},
    onStopDiscovery = {},
    onClearHistory = {},
    onExportHistory = {},
    onOpenQuickSettingsHelp = {},
    navigationRequestId = requestId,
  )
}
