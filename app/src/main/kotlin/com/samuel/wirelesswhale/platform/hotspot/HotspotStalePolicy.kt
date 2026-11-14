package com.samuel.wirelesswhale.platform.hotspot

import com.samuel.wirelesswhale.core.model.EnhancedAccessState
import com.samuel.wirelesswhale.core.model.HotspotSnapshot

/**
 * Pure stale/unavailable mapping for enhanced reads that return no data.
 *
 * Production link: HotspotRepositoryCore collects READY then awaits
 * bridge.fetchSnapshot(). A null/failed fetch (transport timeout, decode
 * failure, or a buggy bridge that stays READY) must never leave a prior
 * ON/clients/canToggle visible as fresh. This helper disables all
 * controls, preserves the last trustworthy observation with its age, and
 * surfaces an unavailable/stale reason. It never invents OFF or zero
 * clients: unknown stays null, prior values stay intact.
 */
internal object HotspotStalePolicy {
  const val DEFAULT_REASON =
    "Hotspot state is unavailable: the helper did not answer. " +
      "Showing the last confirmed state. Retry, or use system settings."

  /**
   * Returns [current] marked stale/unavailable.
   *
   * - Preserves status, ssid, security, clients, source and
   *   lastUpdatedAtMillis (the age of the last trustworthy read).
   * - Forces canToggle/canConfigure/canBlockClients and usage flags off.
   * - Keeps the bridge-reported [bridgeAccess] (often still READY for a
   *   buggy bridge) so the UI does not invent a demotion it did not
   *   observe; the stale [message] carries the unavailability.
   */
  fun markStale(
    current: HotspotSnapshot,
    bridgeAccess: EnhancedAccessState,
    reason: String? = null,
    bridgeMessage: String? = null,
  ): HotspotSnapshot {
    val message = when {
      !reason.isNullOrBlank() -> reason
      !bridgeMessage.isNullOrBlank() -> bridgeMessage
      else -> DEFAULT_REASON
    }
    return current.copy(
      enhancedAccess = bridgeAccess,
      canToggle = false,
      canConfigure = false,
      canBlockClients = false,
      clientUsageAvailable = false,
      hardQuotaAvailable = false,
      message = message,
    )
  }
}
