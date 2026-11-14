package com.samuel.wirelesswhale.core.domain

import com.samuel.wirelesswhale.core.model.WifiNetwork

/**
 * Stable presentation order: the currently connected BSS first, then
 * strongest signal first, then SSID and BSSID as deterministic tiebreaks.
 * Pure function so lists render identically for every observer.
 */
fun sortWifiNetworks(
  networks: List<WifiNetwork>,
  connectedBssid: String? = null,
): List<WifiNetwork> {
  val connected = sanitizeBssid(connectedBssid)
  return networks.sortedWith(
    compareByDescending<WifiNetwork> {
      connected != null && sanitizeBssid(it.bssid) == connected
    }.thenByDescending {
      it.rssiDbm
    }.thenBy {
      it.ssid.lowercase()
    }.thenBy {
      it.bssid.orEmpty()
    },
  )
}
