package com.samuel.wirelesswhale.core.domain

import com.samuel.wirelesswhale.core.model.InternetState

/** Message shown when the location permission required for Wi-Fi scanning is missing. */
const val SCAN_PERMISSION_MESSAGE = "Location permission is required to scan for Wi-Fi networks."

/** Message shown when location services are off and scan results are unavailable. */
const val LOCATION_DISABLED_MESSAGE = "Location services are off, so scan results are unavailable."

/**
 * Converts a ScanResult timestamp (microseconds since boot) into wall-clock
 * millis for display. Unknown, zero, or future timestamps fall back to the
 * delivery wall time so age is never invented or negative.
 *
 * @param receivedWallMillis wall clock when the results were delivered.
 * @param receivedBootMicros monotonic boot clock (microseconds) at delivery.
 * @param resultTimestampBootMicros the ScanResult timestamp (microseconds since boot).
 */
fun scanResultWallSeenAtMillis(
  receivedWallMillis: Long,
  receivedBootMicros: Long,
  resultTimestampBootMicros: Long,
): Long {
  if (resultTimestampBootMicros <= 0L) return receivedWallMillis
  val ageMicros = receivedBootMicros - resultTimestampBootMicros
  if (ageMicros <= 0L) return receivedWallMillis
  return receivedWallMillis - ageMicros / 1_000L
}

/**
 * True when a scan delivery should refresh the last-scan timestamp. Stale
 * (cached) broadcasts never stamp results as fresh; only a genuinely updated
 * delivery moves the timestamp.
 */
fun shouldRefreshLastScanAt(resultsUpdated: Boolean): Boolean = resultsUpdated

/**
 * True when an empty cached delivery must preserve previously observed
 * networks and the previous last-scan timestamp.
 */
fun shouldPreserveNetworksOnDelivery(resultsUpdated: Boolean, resultCount: Int): Boolean =
  !resultsUpdated && resultCount == 0

/**
 * User-facing message for a scan delivery. Stale broadcasts are always
 * labelled; genuine empty scans say so instead of silently clearing to blank.
 */
fun scanDeliveryMessage(resultsUpdated: Boolean, resultCount: Int): String? =
  when {
    !resultsUpdated && resultCount == 0 ->
      "No fresh scan data. The system served an empty cached result."
    !resultsUpdated -> "Results may be stale. The system served cached scan data."
    resultCount == 0 -> "Scan completed. No Wi-Fi networks found."
    else -> null
  }

/**
 * Maps capability observations to [InternetState]. A present capabilities
 * object without internet or validation flags means local-only access, never
 * unknown: unknown is reserved for having no observation at all.
 */
fun internetStateFor(
  hasCapabilities: Boolean,
  hasInternetCapability: Boolean,
  validated: Boolean,
  captivePortal: Boolean,
): InternetState =
  when {
    !hasCapabilities -> InternetState.UNKNOWN
    captivePortal -> InternetState.CAPTIVE_PORTAL
    validated -> InternetState.VALIDATED
    hasInternetCapability -> InternetState.LOCAL_ONLY
    else -> InternetState.LOCAL_ONLY
  }

/**
 * Picks the connection SSID, preferring the transport info and falling back to
 * the public connectionInfo snapshot. Redacted or blank values never surface:
 * they resolve to null (unknown) rather than a placeholder string.
 */
fun resolveConnectionSsid(primary: String?, fallback: String?): String? =
  sanitizeSsid(primary) ?: sanitizeSsid(fallback)

/**
 * Whether scanning is permitted. Fine location is required for
 * startScan/getScanResults on every API level, including 33 and above:
 * NEARBY_WIFI_DEVICES alone is never a replacement for scan calls.
 */
@Suppress("UNUSED_PARAMETER")
fun wifiScanPermissionGranted(
  fineLocationGranted: Boolean,
  nearbyWifiDevicesGranted: Boolean = false,
): Boolean = fineLocationGranted

/**
 * Message after a permission refresh. Recovered states clear stale
 * permission/location text; anything else keeps the previous message so
 * throttle or error detail is not wiped by a Settings return.
 */
fun refreshedScanMessage(
  previous: String?,
  previouslyBlocked: Boolean,
  hasPermission: Boolean,
  locationEnabled: Boolean,
): String? =
  when {
    !hasPermission -> SCAN_PERMISSION_MESSAGE
    !locationEnabled -> LOCATION_DISABLED_MESSAGE
    previouslyBlocked -> null
    else -> previous
  }
