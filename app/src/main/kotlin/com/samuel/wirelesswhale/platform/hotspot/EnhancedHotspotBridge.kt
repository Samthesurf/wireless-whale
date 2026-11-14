package com.samuel.wirelesswhale.platform.hotspot

import com.samuel.wirelesswhale.core.model.EnhancedAccessState
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeProtocol.BridgeResult
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeProtocol.BridgeSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * Test seam over ShizukuHotspotBridge. The production implementation is
 * the Shizuku bridge itself; unit tests inject a fake so repository policy
 * is verified without Shizuku, Binder, or the radio.
 */
internal interface EnhancedHotspotBridge {
  val accessState: StateFlow<EnhancedAccessState>
  val accessMessage: StateFlow<String?>
  val pushedSnapshot: StateFlow<BridgeSnapshot?>
  fun start()
  fun refreshAccess()
  fun requestEnhancedAccess()
  fun close()
  suspend fun fetchSnapshot(): BridgeSnapshot?
  suspend fun startTetheredHotspot(): BridgeResult?
  suspend fun stopTethering(): BridgeResult?
}
