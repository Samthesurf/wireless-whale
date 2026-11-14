package com.samuel.wirelesswhale.platform.shizuku

/**
 * Stable wire identifiers for the hotspot UserService bridge.
 *
 * Pure Kotlin (no Android imports) so unit tests can pin every key. The
 * [BRIDGE_TAG] literal is referenced by ShizukuHotspotBridge and must be
 * kept verbatim by R8 (see docs/shizuku-integration.md); the tag — not the
 * obfuscated class name — is what the Shizuku server uses to identify the
 * service record.
 */
object HotspotBridgeContract {
  /** Shizuku service tag; stable across R8 renames. */
  const val BRIDGE_TAG = "ww-hotspot-bridge"

  /** Shizuku service versionCode; bump to force server-side replacement. */
  const val BRIDGE_VERSION = 3

  /** User-service process suffix: `<package>:hotspot_bridge`. */
  const val PROCESS_SUFFIX = "hotspot_bridge"

  /**
   * Server-sent destroy transaction handled in onTransact and ended with
   * System.exit(). Declared here so both service and tests pin it.
   */
  const val DESTROY_TRANSACTION = 16777115

  /** Linux UID expected for the non-root Shizuku shell backend. */
  const val EXPECTED_SHELL_UID = 2000

  // Bundle keys: handshake response.
  const val KEY_VERSION = "v"
  const val KEY_UID = "uid"
  const val KEY_SHELL_IDENTITY = "shell"
  const val KEY_PACKAGE = "pkg"
  const val KEY_SOFTAP_DIRECT = "softap_direct"
  const val KEY_TETHER_EVENTS = "tether_events"
  const val KEY_AP_STATE = "ap_state"
  const val KEY_CONTROL_AVAILABLE = "control_available"

  // Bundle keys: snapshot.
  const val KEY_STATUS = "status"
  const val KEY_SSID = "ssid"
  const val KEY_SECURITY = "security"
  const val KEY_TETHER_SUPPORTED = "tether_supported"
  const val KEY_UPSTREAM = "upstream"
  const val KEY_TETHERED_IFACES = "tethered_ifaces"
  const val KEY_CLIENTS = "clients"
  const val KEY_MESSAGE = "msg"
  const val KEY_SOURCE = "src"
  const val KEY_AT = "at"

  // Bundle keys: client entries (nested maps inside KEY_CLIENTS).
  const val KEY_CLIENT_ID = "id"
  const val KEY_CLIENT_MAC = "mac"
  const val KEY_CLIENT_NAME = "name"
  const val KEY_CLIENT_ADDRS = "addrs"
  const val KEY_CLIENT_SEEN = "seen"

  // Bundle keys: operation results.
  const val KEY_OK = "ok"
  const val KEY_CODE = "code"
  const val KEY_OP = "op"

  // Bundle keys: requests.
  const val KEY_PROFILE_SSID = "ssid"
  const val KEY_PROFILE_PASSWORD = "password"
  const val KEY_PROFILE_BAND = "band"
  const val KEY_CLIENT_BLOCKED = "blocked"

  // Status values.
  const val STATUS_UNKNOWN = "UNKNOWN"
  const val STATUS_OFF = "OFF"
  const val STATUS_STARTING = "STARTING"
  const val STATUS_ON = "ON"
  const val STATUS_STOPPING = "STOPPING"
  const val STATUS_ERROR = "ERROR"

  // Result codes (KEY_CODE).
  const val CODE_OK = "ok"
  const val CODE_NEEDS_SYSTEM_UI = "needs_system_ui"
  const val CODE_UNSUPPORTED = "unsupported"
  const val CODE_UNVERIFIED = "unverified"
  const val CODE_DENIED = "denied"
  const val CODE_INVALID = "invalid"
  const val CODE_NOT_READY = "not_ready"
  const val CODE_TIMEOUT = "timeout"
  const val CODE_FAILED = "failed"

  // Source labels.
  const val SOURCE_SOFTAP = "softap"
  const val SOURCE_TETHERING = "tethering"
  const val SOURCE_SYSTEM = "system"
  const val SOURCE_UNKNOWN = "unknown"
}
