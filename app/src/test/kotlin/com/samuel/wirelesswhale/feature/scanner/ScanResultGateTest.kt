package com.samuel.wirelesswhale.feature.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure gating rules: results fire once, only while the host is alive and
 * STARTED. Skipped paused/disposed deliveries reset the consumed flag so
 * resume can scan again instead of wedging consumed true.
 */
class ScanResultGateTest {

  @Test
  fun deliversWhenAliveStartedAndNotConsumed() {
    assertTrue(ScanResultGate.canDeliver(disposed = false, lifecycleStarted = true, alreadyConsumed = false))
  }

  @Test
  fun neverDeliversWhenDisposed() {
    assertFalse(ScanResultGate.canDeliver(disposed = true, lifecycleStarted = true, alreadyConsumed = false))
  }

  @Test
  fun neverDeliversWhenNotStarted() {
    assertFalse(ScanResultGate.canDeliver(disposed = false, lifecycleStarted = false, alreadyConsumed = false))
  }

  @Test
  fun neverDeliversTwice() {
    assertFalse(ScanResultGate.canDeliver(disposed = false, lifecycleStarted = true, alreadyConsumed = true))
  }

  @Test
  fun skippedDeliveryResetsConsumedOnPauseOrDispose() {
    assertTrue(ScanResultGate.shouldResetConsumed(disposed = false, lifecycleStarted = false))
    assertTrue(ScanResultGate.shouldResetConsumed(disposed = true, lifecycleStarted = true))
    assertFalse(ScanResultGate.shouldResetConsumed(disposed = false, lifecycleStarted = true))
  }
}
