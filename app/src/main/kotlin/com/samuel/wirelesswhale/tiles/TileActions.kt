package com.samuel.wirelesswhale.tiles

/**
 * Exact tile action strings from the shared implementation contract.
 *
 * SCAN_QR is fired by the Scan tile and handled by the app (parent wires
 * MainActivity). HOTSPOT is the app's hotspot deep-link action for the
 * parent to route; the Hotspot tile itself opens system hotspot controls
 * on the standard path and never toggles blindly.
 */
object TileActions {
  const val SCAN_QR = "com.samuel.wirelesswhale.action.SCAN_QR"
  const val HOTSPOT = "com.samuel.wirelesswhale.action.HOTSPOT"
}
