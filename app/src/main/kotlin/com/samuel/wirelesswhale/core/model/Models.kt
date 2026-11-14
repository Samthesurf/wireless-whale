package com.samuel.wirelesswhale.core.model

enum class InternetState {
  UNKNOWN,
  DISCONNECTED,
  LOCAL_ONLY,
  CAPTIVE_PORTAL,
  VALIDATED,
}

enum class WifiSecurity {
  OPEN,
  WPA2_PERSONAL,
  WPA3_PERSONAL,
  WPA2_WPA3_PERSONAL,
  WEP,
  ENTERPRISE,
  OWE,
  UNKNOWN,
}

data class WifiCredentials(
  val ssid: String,
  val password: String = "",
  val security: WifiSecurity = WifiSecurity.WPA2_PERSONAL,
  val hidden: Boolean = false,
) {
  override fun toString(): String =
    "WifiCredentials(ssid=$ssid, security=$security, hidden=$hidden, password=[REDACTED])"
}

data class WifiNetwork(
  val id: String,
  val ssid: String,
  val bssid: String?,
  val rssiDbm: Int,
  val frequencyMhz: Int,
  val security: WifiSecurity,
  val capabilities: String = "",
  val lastSeenAtMillis: Long? = null,
)

data class WifiSnapshot(
  val enabled: Boolean = false,
  val connected: Boolean = false,
  val ssid: String? = null,
  val bssid: String? = null,
  val rssiDbm: Int? = null,
  val frequencyMhz: Int? = null,
  val linkSpeedMbps: Int? = null,
  val txLinkSpeedMbps: Int? = null,
  val rxLinkSpeedMbps: Int? = null,
  val ipv4: List<String> = emptyList(),
  val ipv6: List<String> = emptyList(),
  val gateway: String? = null,
  val dnsServers: List<String> = emptyList(),
  val internet: InternetState = InternetState.UNKNOWN,
  val metered: Boolean? = null,
  val vpnActive: Boolean = false,
  val permissionRequired: Boolean = false,
  val locationEnabled: Boolean = true,
  val lastUpdatedAtMillis: Long? = null,
  val message: String? = null,
)

data class ScanState(
  val scanning: Boolean = false,
  val lastScanAtMillis: Long? = null,
  val message: String? = null,
  val permissionRequired: Boolean = false,
  val locationRequired: Boolean = false,
  val throttled: Boolean = false,
)

enum class HotspotStatus {
  UNKNOWN,
  OFF,
  STARTING,
  ON,
  STOPPING,
  ERROR,
}

enum class EnhancedAccessState {
  NOT_INSTALLED,
  NOT_RUNNING,
  PERMISSION_REQUIRED,
  CONNECTING,
  READY,
  REVOKED,
  HELPER_UNAVAILABLE,
  UNSUPPORTED,
}

data class HotspotClient(
  val id: String,
  val macAddress: String? = null,
  val name: String? = null,
  val addresses: List<String> = emptyList(),
  val connected: Boolean = true,
  val lastSeenAtMillis: Long? = null,
  val receivedBytes: Long? = null,
  val transmittedBytes: Long? = null,
)

data class HotspotSnapshot(
  val status: HotspotStatus = HotspotStatus.UNKNOWN,
  val ssid: String? = null,
  val security: String? = null,
  val clients: List<HotspotClient>? = null,
  val enhancedAccess: EnhancedAccessState = EnhancedAccessState.NOT_INSTALLED,
  val canToggle: Boolean = false,
  val canConfigure: Boolean = false,
  val canBlockClients: Boolean = false,
  val clientUsageAvailable: Boolean = false,
  val hardQuotaAvailable: Boolean = false,
  val message: String? = null,
  val source: String = "System",
  val lastUpdatedAtMillis: Long? = null,
)

data class HotspotProfile(
  val ssid: String,
  val password: String,
  val band: Int? = null,
) {
  override fun toString(): String =
    "HotspotProfile(ssid=$ssid, band=$band, password=[REDACTED])"
}

sealed interface OperationResult {
  data class Success(val message: String) : OperationResult
  data class Failure(val message: String) : OperationResult
  data class NeedsSystemUi(val action: String) : OperationResult
}

enum class CheckStatus {
  WAITING,
  RUNNING,
  PASSED,
  FAILED,
  WARNING,
}

data class DiagnosticStep(
  val id: String,
  val title: String,
  val status: CheckStatus,
  val detail: String = "",
  val durationMs: Long? = null,
)

data class DiagnosticReport(
  val steps: List<DiagnosticStep> = emptyList(),
  val startedAtMillis: Long = 0,
  val finishedAtMillis: Long? = null,
  val cancelled: Boolean = false,
)

data class DiscoveredDevice(
  val id: String,
  val name: String?,
  val host: String?,
  val port: Int?,
  val serviceType: String,
)

data class HistoryEntry(
  val id: String,
  val title: String,
  val detail: String,
  val atMillis: Long,
  val kind: String,
)
