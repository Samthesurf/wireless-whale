package com.samuel.wirelesswhale.platform.hotspot

import com.samuel.wirelesswhale.core.model.EnhancedAccessState
import com.samuel.wirelesswhale.core.model.HotspotClient
import com.samuel.wirelesswhale.core.model.HotspotSnapshot
import com.samuel.wirelesswhale.core.model.HotspotStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HotspotStalePolicyTest {

  private fun lastGood() = HotspotSnapshot(
    status = HotspotStatus.ON,
    ssid = "WhaleNet",
    security = "WPA2",
    clients = listOf(
      HotspotClient(id = "mac:AA:BB:CC:11:22:33", macAddress = "AA:BB:CC:11:22:33"),
    ),
    enhancedAccess = EnhancedAccessState.READY,
    canToggle = true,
    canConfigure = false,
    canBlockClients = false,
    clientUsageAvailable = false,
    hardQuotaAvailable = false,
    message = null,
    source = "SoftAp",
    lastUpdatedAtMillis = 7L,
  )

  @Test
  fun stale_disablesControls_preservesTrustworthyWithAge() {
    val stale = HotspotStalePolicy.markStale(
      current = lastGood(),
      bridgeAccess = EnhancedAccessState.READY,
      bridgeMessage = null,
    )
    // Controls locked even though the bridge still claims READY.
    assertFalse(stale.canToggle)
    assertFalse(stale.canConfigure)
    assertFalse(stale.canBlockClients)
    assertFalse(stale.clientUsageAvailable)
    assertFalse(stale.hardQuotaAvailable)
    // Last trustworthy values preserved with their age, never invented.
    assertEquals(HotspotStatus.ON, stale.status)
    assertEquals("WhaleNet", stale.ssid)
    assertEquals(1, stale.clients?.size)
    assertEquals("SoftAp", stale.source)
    assertEquals(7L, stale.lastUpdatedAtMillis)
    val message = stale.message ?: ""
    assertTrueContainsUnavailableOrStale(message)
  }

  @Test
  fun stale_withNoPriorGood_neverInventsOffOrZero() {
    val initial = HotspotSnapshot(
      status = HotspotStatus.UNKNOWN,
      clients = null,
      enhancedAccess = EnhancedAccessState.READY,
      canToggle = false,
      lastUpdatedAtMillis = null,
    )
    val stale = HotspotStalePolicy.markStale(
      current = initial,
      bridgeAccess = EnhancedAccessState.READY,
      bridgeMessage = null,
    )
    assertEquals(HotspotStatus.UNKNOWN, stale.status)
    assertNull(stale.clients)
    assertFalse(stale.canToggle)
    assertTrueContainsUnavailableOrStale(stale.message ?: "")
  }

  @Test
  fun stale_prefersExplicitReason() {
    val stale = HotspotStalePolicy.markStale(
      current = lastGood(),
      bridgeAccess = EnhancedAccessState.READY,
      reason = "Custom stale reason.",
      bridgeMessage = "Bridge message.",
    )
    assertEquals("Custom stale reason.", stale.message)
  }

  private fun assertTrueContainsUnavailableOrStale(message: String) {
    val lower = message.lowercase()
    val ok = lower.contains("unavailable") || lower.contains("stale") || lower.contains("did not answer")
    assertEquals(true, ok)
  }
}
