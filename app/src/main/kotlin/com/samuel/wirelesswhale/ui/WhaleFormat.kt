package com.samuel.wirelesswhale.ui

import com.samuel.wirelesswhale.core.model.CheckStatus
import com.samuel.wirelesswhale.core.model.EnhancedAccessState
import com.samuel.wirelesswhale.core.model.HotspotStatus
import com.samuel.wirelesswhale.core.model.InternetState
import com.samuel.wirelesswhale.core.model.WifiSecurity

/**
 * Pure presentation helpers shared by every screen. JVM-testable: every
 * function takes an explicit [nowMillis] where time matters and never reads
 * the clock or Android APIs. See app/src/test UiFormatTest.
 */

fun formatAge(lastMillis: Long?, nowMillis: Long): String? {
  if (lastMillis == null || nowMillis < lastMillis) return null
  val seconds = (nowMillis - lastMillis) / 1000
  return when {
    seconds < 10 -> "Just now"
    seconds < 60 -> "${seconds} sec ago"
    seconds < 3600 -> {
      val minutes = seconds / 60
      if (minutes <= 1) "1 min ago" else "$minutes min ago"
    }
    else -> {
      val hours = seconds / 3600
      if (hours <= 1) "1 hour ago" else "$hours hours ago"
    }
  }
}

/** Short staleness stamp, e.g. "Last updated 4 min ago". Null when unknown. */
fun formatUpdatedStamp(lastMillis: Long?, nowMillis: Long): String? {
  val age = formatAge(lastMillis, nowMillis) ?: return null
  return "Last updated $age".replace("Just now", "just now")
}

/** 0 to 4 bars from RSSI in dBm. Never invents a signal for null input. */
fun signalBars(rssiDbm: Int?): Int {
  if (rssiDbm == null) return 0
  return when {
    rssiDbm >= -55 -> 4
    rssiDbm >= -67 -> 3
    rssiDbm >= -78 -> 2
    rssiDbm >= -88 -> 1
    else -> 0
  }
}

fun signalLabel(rssiDbm: Int?): String = when (signalBars(rssiDbm)) {
  4 -> "Excellent"
  3 -> "Good"
  2 -> "Fair"
  1 -> "Weak"
  else -> "No signal"
}

fun bandLabel(frequencyMhz: Int?): String = when {
  frequencyMhz == null || frequencyMhz <= 0 -> "Band unknown"
  frequencyMhz < 3000 -> "2.4 GHz"
  frequencyMhz < 6000 -> "5 GHz"
  else -> "6 GHz"
}

fun securityLabel(security: WifiSecurity): String = when (security) {
  WifiSecurity.OPEN -> "Open"
  WifiSecurity.WPA2_PERSONAL -> "WPA2"
  WifiSecurity.WPA3_PERSONAL -> "WPA3"
  WifiSecurity.WPA2_WPA3_PERSONAL -> "WPA2/WPA3"
  WifiSecurity.WEP -> "WEP"
  WifiSecurity.ENTERPRISE -> "Enterprise"
  WifiSecurity.OWE -> "Enhanced open"
  WifiSecurity.UNKNOWN -> "Unknown security"
}

fun internetLabel(state: InternetState): String = when (state) {
  InternetState.VALIDATED -> "Internet verified"
  InternetState.CAPTIVE_PORTAL -> "Sign in required"
  InternetState.LOCAL_ONLY -> "Connected, no internet"
  InternetState.DISCONNECTED -> "Not connected"
  InternetState.UNKNOWN -> "Internet status unknown"
}

fun hotspotTitle(status: HotspotStatus): String = when (status) {
  HotspotStatus.ON -> "Hotspot is on"
  HotspotStatus.OFF -> "Hotspot is off"
  HotspotStatus.STARTING -> "Hotspot is starting"
  HotspotStatus.STOPPING -> "Hotspot is stopping"
  HotspotStatus.ERROR -> "Hotspot needs attention"
  HotspotStatus.UNKNOWN -> "Hotspot status unknown"
}

fun enhancedAccessLabel(state: EnhancedAccessState): String = when (state) {
  EnhancedAccessState.READY -> "Enhanced: ready"
  EnhancedAccessState.NOT_INSTALLED -> "Standard access"
  EnhancedAccessState.NOT_RUNNING -> "Enhanced: not running"
  EnhancedAccessState.PERMISSION_REQUIRED -> "Enhanced: permission needed"
  EnhancedAccessState.CONNECTING -> "Enhanced: connecting"
  EnhancedAccessState.REVOKED -> "Enhanced: access lost"
  EnhancedAccessState.HELPER_UNAVAILABLE -> "Enhanced: helper unavailable"
  EnhancedAccessState.UNSUPPORTED -> "Enhanced: not supported"
}

fun checkStatusLabel(status: CheckStatus): String = when (status) {
  CheckStatus.WAITING -> "Waiting"
  CheckStatus.RUNNING -> "Running"
  CheckStatus.PASSED -> "Passed"
  CheckStatus.FAILED -> "Failed"
  CheckStatus.WARNING -> "Warning"
}

/**
 * Truthful client count line. Null (unknown source) never renders as zero.
 * Zero only renders when the source authoritatively reported an empty list.
 */
fun clientCountLine(clientCount: Int?): String = when (clientCount) {
  null -> "Client count unknown"
  0 -> "No clients connected"
  1 -> "1 client connected"
  else -> "$clientCount clients connected"
}

data class SsidValidation(val error: String?)

fun validateSsid(ssid: String): SsidValidation {
  if (ssid.isBlank()) return SsidValidation("Enter a network name.")
  if (ssid.length > 32) return SsidValidation("Network names are at most 32 characters.")
  return SsidValidation(null)
}

data class PasswordValidation(val error: String?)

/**
 * WPA passphrases are 8 to 63 characters. Open networks skip this check;
 * WEP and enterprise are rejected with an explanation instead of a silent
 * downgrade.
 */
fun validatePassword(password: String, security: WifiSecurity): PasswordValidation {
  if (security == WifiSecurity.OPEN || security == WifiSecurity.OWE) {
    return PasswordValidation(null)
  }
  if (security == WifiSecurity.WEP || security == WifiSecurity.ENTERPRISE) {
    return PasswordValidation("This network type needs system settings. Use Open system settings to join it.")
  }
  if (security == WifiSecurity.UNKNOWN) {
    return PasswordValidation("Security is unknown. Confirm the details before connecting.")
  }
  if (password.length < 8) return PasswordValidation("Passwords need at least 8 characters.")
  if (password.length > 63) return PasswordValidation("Passwords are at most 63 characters.")
  return PasswordValidation(null)
}

/** Groups scan rows by SSID for the grouped Wi-Fi list. Stable order. */
fun groupNetworksBySsid(ids: List<String>, ssidOf: (String) -> String): Map<String, List<String>> {
  val groups = LinkedHashMap<String, List<String>>()
  for (id in ids) {
    val key = ssidOf(id)
    groups[key] = (groups[key] ?: emptyList()) + id
  }
  return groups
}
