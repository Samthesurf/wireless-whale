package com.samuel.wirelesswhale.navigation

import com.samuel.wirelesswhale.ui.components.WhaleDestination

/**
 * UI-local navigation. Tabs plus secondary routes with back states.
 * Navigation state stays inside the UI layer; the exact contract entry
 * point is [com.samuel.wirelesswhale.ui.WirelessWhaleApp].
 */
sealed interface WhaleRoute {
  data class Tab(val destination: WhaleDestination) : WhaleRoute
  data class NetworkDetail(val networkId: String) : WhaleRoute
  data class ClientDetail(val clientId: String) : WhaleRoute
  data object HotspotProfile : WhaleRoute
  data object ConnectionCheck : WhaleRoute
  data object LanDiscovery : WhaleRoute
  data object History : WhaleRoute
  data object Settings : WhaleRoute
  data object AccessSettings : WhaleRoute
  data object QuickSettingsHelp : WhaleRoute
  data object Licenses : WhaleRoute
}

fun destinationFor(name: String): WhaleDestination = when (name) {
  "wifi" -> WhaleDestination.Wifi
  "hotspot" -> WhaleDestination.Hotspot
  "tools" -> WhaleDestination.Tools
  else -> WhaleDestination.Home
}
