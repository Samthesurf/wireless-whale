package com.samuel.wirelesswhale.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPolicyTest {

  private val budget = ScanBudget(maxScans = 4, windowMillis = 120_000L, minIntervalMillis = 30_000L)

  @Test
  fun firstScan_isAllowed() {
    assertTrue(canRequestScan(nowMillis = 1_000_000L, scanTimestampsMillis = emptyList(), budget = budget))
  }

  @Test
  fun minInterval_blocksRapidRescan() {
    val history = listOf(1_000_000L)
    assertFalse(canRequestScan(1_000_000L + 10_000L, history, budget))
    assertTrue(canRequestScan(1_000_000L + 30_000L, history, budget))
  }

  @Test
  fun windowCap_blocksFifthScanInWindow() {
    val history = listOf(0L, 30_000L, 60_000L, 90_000L)
    assertFalse(canRequestScan(100_000L, history, budget))
    assertTrue(canRequestScan(121_000L, history, budget))
  }

  @Test
  fun staleHistory_isPruned() {
    val history = listOf(0L, 1_000L, 500_000L)
    assertEquals(listOf(500_000L), pruneScanHistory(history, 500_000L, budget))
  }

  @Test
  fun nextScanAllowed_ignoresWindowUntilBudgetFull() {
    // One scan in history: only the per-scan cooldown binds, never the window.
    val history = listOf(1_000_000L)
    assertEquals(20_000L, nextScanAllowedInMillis(1_010_000L, history, budget))
  }

  @Test
  fun nextScanAllowed_overfullHistoryWaitsForEnoughExpiries() {
    // Five scans inside a four-scan window: must wait until two entries expire.
    val history = listOf(0L, 30_000L, 60_000L, 90_000L, 100_000L)
    assertFalse(canRequestScan(110_000L, history, budget))
    assertEquals(40_000L, nextScanAllowedInMillis(110_000L, history, budget))
  }

  @Test
  fun nextScanAllowed_reportsWaitThenZero() {
    val history = listOf(1_000_000L)
    assertEquals(20_000L, nextScanAllowedInMillis(1_010_000L, history, budget))
    assertEquals(0L, nextScanAllowedInMillis(1_000_000L + 30_000L, history, budget))
    assertEquals(0L, nextScanAllowedInMillis(2_000_000L, emptyList(), budget))
  }

  @Test
  fun freshness_window() {
    assertFalse(isScanFresh(null, 1_000_000L))
    assertTrue(isScanFresh(1_000_000L - 30_000L, 1_000_000L))
    assertFalse(isScanFresh(1_000_000L - 61_000L, 1_000_000L))
    assertFalse(isScanFresh(1_000_001L, 1_000_000L))
  }
}
