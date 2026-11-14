package com.samuel.wirelesswhale.core.domain

import com.samuel.wirelesswhale.core.model.InternetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiObservationLogicTest {

  @Test
  fun scanTimestamp_convertsBootMicrosToWall() {
    // Delivered at wall 1_000_000, boot clock 500s; result seen 30s earlier.
    val wall = scanResultWallSeenAtMillis(
      receivedWallMillis = 1_000_000L,
      receivedBootMicros = 500_000_000L,
      resultTimestampBootMicros = 470_000_000L,
    )
    assertEquals(970_000L, wall)
  }

  @Test
  fun scanTimestamp_zeroOrUnknownFallsBackToDelivery() {
    assertEquals(
      1_000_000L,
      scanResultWallSeenAtMillis(1_000_000L, 500_000_000L, 0L),
    )
    assertEquals(
      1_000_000L,
      scanResultWallSeenAtMillis(1_000_000L, 500_000_000L, -5L),
    )
  }

  @Test
  fun scanTimestamp_futureTimestampClampsToDelivery() {
    // Clock moved backwards between scan and delivery: never report the future.
    assertEquals(
      1_000_000L,
      scanResultWallSeenAtMillis(1_000_000L, 500_000_000L, 600_000_000L),
    )
  }

  @Test
  fun scanTimestamp_sameInstantIsDeliveryTime() {
    assertEquals(
      1_000_000L,
      scanResultWallSeenAtMillis(1_000_000L, 500_000_000L, 500_000_000L),
    )
  }

  @Test
  fun staleDelivery_neverRefreshesTimestamp() {
    assertFalse(shouldRefreshLastScanAt(false))
    assertTrue(shouldRefreshLastScanAt(true))
  }

  @Test
  fun emptyCachedDelivery_preservesNetworks() {
    assertTrue(shouldPreserveNetworksOnDelivery(false, 0))
    assertFalse(shouldPreserveNetworksOnDelivery(true, 0))
    assertFalse(shouldPreserveNetworksOnDelivery(false, 3))
    assertFalse(shouldPreserveNetworksOnDelivery(true, 3))
  }

  @Test
  fun deliveryMessage_labelsStaleAndEmpty() {
    assertNull(scanDeliveryMessage(true, 3))
    assertEquals(
      "Results may be stale. The system served cached scan data.",
      scanDeliveryMessage(false, 3),
    )
    assertEquals(
      "No fresh scan data. The system served an empty cached result.",
      scanDeliveryMessage(false, 0),
    )
    assertEquals(
      "Scan completed. No Wi-Fi networks found.",
      scanDeliveryMessage(true, 0),
    )
  }

  @Test
  fun internetState_mapping() {
    assertEquals(
      InternetState.UNKNOWN,
      internetStateFor(false, false, false, false),
    )
    assertEquals(
      InternetState.CAPTIVE_PORTAL,
      internetStateFor(true, true, true, true),
    )
    assertEquals(
      InternetState.VALIDATED,
      internetStateFor(true, true, true, false),
    )
    assertEquals(
      InternetState.LOCAL_ONLY,
      internetStateFor(true, true, false, false),
    )
    assertEquals(
      InternetState.LOCAL_ONLY,
      internetStateFor(true, false, false, false),
    )
  }

  @Test
  fun resolveSsid_prefersPrimaryFallsBackCleanly() {
    assertEquals("Home", resolveConnectionSsid("Home", "Other"))
    assertEquals("Home", resolveConnectionSsid("\"Home\"", null))
    assertEquals("Fallback", resolveConnectionSsid("<unknown ssid>", "Fallback"))
    assertEquals("Fallback", resolveConnectionSsid(null, "\"Fallback\""))
    assertNull(resolveConnectionSsid("<unknown ssid>", null))
    assertNull(resolveConnectionSsid(null, null))
    assertNull(resolveConnectionSsid("", null))
    // Spaces are SSID data and are never trimmed: a whitespace-only SSID is
    // preserved literally, while truly empty values still resolve to null.
    assertEquals("   ", resolveConnectionSsid("", "   "))
    assertEquals("   ", resolveConnectionSsid("   ", "Fallback"))
  }

  @Test
  fun scanPermission_fineLocationAlwaysRequired() {
    assertTrue(wifiScanPermissionGranted(fineLocationGranted = true, nearbyWifiDevicesGranted = false))
    assertTrue(wifiScanPermissionGranted(fineLocationGranted = true, nearbyWifiDevicesGranted = true))
    // NEARBY_WIFI_DEVICES alone is not a replacement on 33+.
    assertFalse(wifiScanPermissionGranted(fineLocationGranted = false, nearbyWifiDevicesGranted = true))
    assertFalse(wifiScanPermissionGranted(fineLocationGranted = false, nearbyWifiDevicesGranted = false))
  }

  @Test
  fun refreshedMessage_clearsStaleBlockOnRecovery() {
    assertNull(
      refreshedScanMessage(
        previous = SCAN_PERMISSION_MESSAGE,
        previouslyBlocked = true,
        hasPermission = true,
        locationEnabled = true,
      ),
    )
    assertNull(
      refreshedScanMessage(
        previous = LOCATION_DISABLED_MESSAGE,
        previouslyBlocked = true,
        hasPermission = true,
        locationEnabled = true,
      ),
    )
  }

  @Test
  fun refreshedMessage_keepsUnrelatedMessageWhenNeverBlocked() {
    assertEquals(
      "Custom note",
      refreshedScanMessage(
        previous = "Custom note",
        previouslyBlocked = false,
        hasPermission = true,
        locationEnabled = true,
      ),
    )
    assertNull(
      refreshedScanMessage(
        previous = null,
        previouslyBlocked = false,
        hasPermission = true,
        locationEnabled = true,
      ),
    )
  }

  @Test
  fun refreshedMessage_reportsCurrentBlocker() {
    assertEquals(
      SCAN_PERMISSION_MESSAGE,
      refreshedScanMessage(null, false, hasPermission = false, locationEnabled = true),
    )
    assertEquals(
      LOCATION_DISABLED_MESSAGE,
      refreshedScanMessage(null, false, hasPermission = true, locationEnabled = false),
    )
    // Missing permission wins over disabled location.
    assertEquals(
      SCAN_PERMISSION_MESSAGE,
      refreshedScanMessage(null, true, hasPermission = false, locationEnabled = false),
    )
  }

  @Test
  fun budget_clockShiftBackwardsBlocksScan() {
    val budget = ScanBudget()
    val history = listOf(1_000_000L)
    // Monotonic clock jumped backwards: never allow, report a wait.
    assertFalse(canRequestScan(900_000L, history, budget))
    assertTrue(nextScanAllowedInMillis(900_000L, history, budget) > 0L)
  }

  @Test
  fun budget_monotonicHistoryWindow() {
    val budget = ScanBudget(maxScans = 4, windowMillis = 120_000L, minIntervalMillis = 30_000L)
    val history = listOf(0L, 30_000L, 60_000L, 90_000L)
    assertFalse(canRequestScan(100_000L, history, budget))
    assertEquals(20_000L, nextScanAllowedInMillis(100_000L, history, budget))
    assertTrue(canRequestScan(210_000L, history, budget))
  }
}
