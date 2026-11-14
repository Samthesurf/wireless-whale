package com.samuel.wirelesswhale.core.domain

import com.samuel.wirelesswhale.core.model.WifiSecurity

private val PSK_PROTOCOLS = setOf("WPA", "WPA2", "RSN")

/** Bracket tokens that carry no security meaning; only these may read as OPEN. */
private val OPEN_TOKENS = setOf("ESS", "WPS")

private fun bracketTokens(caps: String): List<String> =
  Regex("\\[([^\\]]*)\\]").findAll(caps).map { it.groupValues[1] }.toList()

private fun tokenParts(token: String): List<String> = token.split("-")

/**
 * Maps an Android ScanResult capabilities string to [WifiSecurity].
 *
 * Pure function shared by the Wi-Fi repository and unit tests. Ambiguous or
 * unrecognized capability sets map to [WifiSecurity.UNKNOWN] and must never
 * silently downgrade to OPEN. Only exact, recognized protocol/AKM tokens
 * (WPA/WPA2/RSN + PSK, SAE, EAP, WEP, OWE) assert a security type: substring
 * matching would misread unknown protocols such as WAPI-PSK as WPA2, or
 * unknown flags such as FOO as OPEN via a trailing ESS.
 */
fun securityFromCapabilities(capabilities: String): WifiSecurity {
  val caps = capabilities.uppercase()
  if (caps.isBlank()) return WifiSecurity.OPEN
  val tokens = bracketTokens(caps).ifEmpty {
    // Unbracketed input: treat the whole string as one token's parts so the
    // same exact-token rules still apply instead of substring matching.
    listOf(caps.replace(Regex("[^A-Z0-9]+"), "-").trim('-'))
  }
  fun hasPart(part: String): Boolean = tokens.any { tokenParts(it).contains(part) }
  if (hasPart("EAP") || tokens.contains("IEEE8021X")) return WifiSecurity.ENTERPRISE
  if (tokens.contains("WEP")) return WifiSecurity.WEP
  val hasSae = hasPart("SAE")
  val hasPsk = tokens.any {
    val parts = tokenParts(it)
    parts.size >= 2 && parts[0] in PSK_PROTOCOLS && parts[1] == "PSK"
  }
  if (hasSae && hasPsk) return WifiSecurity.WPA2_WPA3_PERSONAL
  if (hasSae) return WifiSecurity.WPA3_PERSONAL
  if (hasPart("OWE")) return WifiSecurity.OWE
  if (hasPsk) return WifiSecurity.WPA2_PERSONAL
  if (tokens.all { it in OPEN_TOKENS }) return WifiSecurity.OPEN
  return WifiSecurity.UNKNOWN
}
