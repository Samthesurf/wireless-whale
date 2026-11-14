package com.samuel.wirelesswhale.core.domain

/** Value Android reports for SSIDs that are hidden by redaction or permissions. */
const val UNKNOWN_SSID_TOKEN = "<unknown ssid>"

/** Placeholder BSSID Android reports when the real BSSID is redacted. */
const val PLACEHOLDER_BSSID = "02:00:00:00:00:00"

/**
 * Returns a display-safe SSID, or null when the raw value carries no real
 * identifier. Strips legacy quote wrapping applied by WifiInfo on older APIs
 * and drops the "<unknown ssid>" redaction token (any case, quoted or not).
 */
fun sanitizeSsid(raw: String?): String? {
  var value = raw.orEmpty()
  if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
    value = value.substring(1, value.length - 1)
  }
  if (value.isEmpty()) return null
  if (value.equals(UNKNOWN_SSID_TOKEN, ignoreCase = true)) return null
  return value
}

/** ScanResult.SSID is already literal, unlike WifiInfo's quoted representation. */
fun sanitizeScanSsid(raw: String?): String? = raw?.takeIf { it.isNotEmpty() }

/**
 * Returns a stable lowercase BSSID, or null when the raw value is blank or
 * the Android redaction placeholder.
 */
fun sanitizeBssid(raw: String?): String? {
  val value = raw?.trim().orEmpty()
  if (value.isEmpty()) return null
  if (value.equals(PLACEHOLDER_BSSID, ignoreCase = true)) return null
  return value.lowercase()
}
