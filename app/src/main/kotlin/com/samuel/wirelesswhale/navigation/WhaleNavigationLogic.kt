package com.samuel.wirelesswhale.navigation

import com.samuel.wirelesswhale.ui.components.WhaleDestination

/**
 * Pure navigation helpers for [WirelessWhaleApp]. JVM-testable: every
 * function here is a stateless mapping over route tokens and request ids,
 * with no clock, Android, or Compose dependency.
 *
 * Route tokens carry tab names and row ids only. Passwords, credential
 * drafts, and share payloads are never part of navigation state, so saving
 * and restoring these tokens cannot persist a secret.
 */

/** Stable token for one route. Never contains credential material. */
fun routeToken(route: WhaleRoute): String = when (route) {
  is WhaleRoute.Tab -> "tab:" + when (route.destination) {
    WhaleDestination.Home -> "home"
    WhaleDestination.Wifi -> "wifi"
    WhaleDestination.Hotspot -> "hotspot"
    WhaleDestination.Tools -> "tools"
  }
  is WhaleRoute.NetworkDetail -> "network:" + escapeRouteId(route.networkId)
  is WhaleRoute.ClientDetail -> "client:" + escapeRouteId(route.clientId)
  WhaleRoute.HotspotProfile -> "hotspotProfile"
  WhaleRoute.ConnectionCheck -> "connectionCheck"
  WhaleRoute.LanDiscovery -> "lanDiscovery"
  WhaleRoute.History -> "history"
  WhaleRoute.Settings -> "settings"
  WhaleRoute.AccessSettings -> "accessSettings"
  WhaleRoute.QuickSettingsHelp -> "quickSettingsHelp"
  WhaleRoute.Licenses -> "licenses"
}

/** Inverse of [routeToken]. Unknown or empty tokens return null. */
fun routeFromToken(token: String?): WhaleRoute? {
  if (token.isNullOrEmpty()) return null
  if (token.startsWith("tab:")) return WhaleRoute.Tab(destinationFor(token.removePrefix("tab:")))
  if (token.startsWith("network:")) {
    val id = unescapeRouteId(token.removePrefix("network:"))
    return if (id.isEmpty()) null else WhaleRoute.NetworkDetail(id)
  }
  if (token.startsWith("client:")) {
    val id = unescapeRouteId(token.removePrefix("client:"))
    return if (id.isEmpty()) null else WhaleRoute.ClientDetail(id)
  }
  return when (token) {
    "hotspotProfile" -> WhaleRoute.HotspotProfile
    "connectionCheck" -> WhaleRoute.ConnectionCheck
    "lanDiscovery" -> WhaleRoute.LanDiscovery
    "history" -> WhaleRoute.History
    "settings" -> WhaleRoute.Settings
    "accessSettings" -> WhaleRoute.AccessSettings
    "quickSettingsHelp" -> WhaleRoute.QuickSettingsHelp
    "licenses" -> WhaleRoute.Licenses
    else -> null
  }
}

/** Backstack to a Bundle-safe token list. */
fun stackToTokens(stack: List<WhaleRoute>): ArrayList<String> =
  ArrayList(stack.map(::routeToken))

/**
 * Token list back to a backstack. Unknown tokens are dropped; a null,
 * empty, or fully unknown list returns null so the caller falls back to
 * the launch destination instead of showing an empty shell.
 */
fun stackFromTokens(tokens: List<String>?): List<WhaleRoute>? {
  if (tokens.isNullOrEmpty()) return null
  val routes = tokens.mapNotNull(::routeFromToken)
  return routes.ifEmpty { null }
}

/**
 * An external navigation request is consumed exactly once. Id 0 is the
 * default with no request, so it never navigates. A repeated
 * same-destination event carries a new token and still consumes, which is
 * what drops a detail screen back to its tab.
 */
fun shouldConsumeNavigationRequest(requestId: Long, lastConsumedId: Long): Boolean =
  requestId != 0L && requestId != lastConsumedId

/**
 * Tab navigation for an external request. A repeated same-route request
 * intentionally resets to the plain tab instead of keeping a pushed
 * detail route, so a second hotspot tile tap always lands on Hotspot.
 */
fun nextStackForExternalRequest(
  current: List<WhaleRoute>,
  initialDestination: String,
): List<WhaleRoute> {
  val requested = WhaleRoute.Tab(destinationFor(initialDestination))
  if (current.size == 1 && current[0] == requested) return current
  return listOf(requested)
}

private fun escapeRouteId(id: String): String {
  val out = StringBuilder(id.length)
  for (c in id) {
    if (c == '\\' || c == ':') out.append('\\')
    out.append(c)
  }
  return out.toString()
}

private fun unescapeRouteId(raw: String): String {
  val out = StringBuilder(raw.length)
  var i = 0
  while (i < raw.length) {
    val c = raw[i]
    if (c == '\\' && i + 1 < raw.length) {
      out.append(raw[i + 1])
      i += 2
    } else {
      out.append(c)
      i += 1
    }
  }
  return out.toString()
}
