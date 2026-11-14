package com.samuel.wirelesswhale.platform.shizuku

import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.CODE_FAILED
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.CODE_INVALID
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.CODE_OK
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_AT
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_CLIENTS
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_CLIENT_ADDRS
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_CLIENT_ID
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_CLIENT_MAC
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_CLIENT_NAME
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_CLIENT_SEEN
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_CODE
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_CONTROL_AVAILABLE
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_MESSAGE
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_OK
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_OP
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_SECURITY
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_SOURCE
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_SSID
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_STATUS
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_TETHERED_IFACES
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_TETHER_SUPPORTED
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_UPSTREAM
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_VERSION
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.SOURCE_UNKNOWN
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.STATUS_ERROR
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.STATUS_OFF
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.STATUS_ON
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.STATUS_UNKNOWN

/**
 * Pure hotspot-bridge logic: validation, identity, merge, and strict
 * parsers. No Android imports, so every rule below is covered by JVM unit
 * tests. Nothing here touches the radio.
 *
 * Membership rule (contract-critical): the Soft AP association set, keyed
 * by MAC, is authoritative. IP/hostname enrichment may only attach to an
 * already-associated MAC and must never add or remove members. In
 * particular the member set must NOT be intersected with IP entries:
 * static-IP and sleeping clients stay listed.
 */
object HotspotBridgeProtocol {

  private val MAC_RE = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
  private val IPV4_RE = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

  /** Hard bounds so no IPC payload or shell dump can grow unbounded. */
  const val MAX_CLIENTS = 128
  const val MAX_DUMP_LINES = 512
  const val MAX_ADDR_PER_CLIENT = 8

  /** Placeholder MAC some stacks print for incomplete entries. */
  const val ZERO_MAC = "00:00:00:00:00:00"

  // ------------------------------------------------------------------
  // Identity helpers.
  // ------------------------------------------------------------------

  /** Strict MAC parse; returns canonical upper-case form or null. */
  fun normalizeMac(raw: String?): String? {
    if (raw == null) return null
    val t = raw.trim()
    if (!MAC_RE.matches(t)) return null
    return t.uppercase()
  }

  /** Stable client id for one associated MAC. */
  fun clientIdFor(mac: String): String = "mac:${mac.uppercase()}"

  /** Best-effort IPv4/IPv6 literal check without network access. */
  fun isPlausibleIp(raw: String?): Boolean {
    if (raw == null) return false
    val t = raw.trim().removeSuffix("%").substringBefore("%")
    if (t.isEmpty()) return false
    val v4 = IPV4_RE.matchEntire(t)
    if (v4 != null) {
      return v4.groupValues.drop(1).all { (it.toIntOrNull() ?: 256) in 0..255 }
    }
    // IPv6: hextets with at least two colons; zone id already stripped.
    if (t.count { it == ':' } >= 2 && t.all { it.isLetterOrDigit() || it == ':' || it == '.' }) {
      return true
    }
    return false
  }

  // ------------------------------------------------------------------
  // Profile validation (mirrors SoftApConfiguration limits on API 30).
  // ------------------------------------------------------------------

  /**
   * Returns a list of human-readable problems; empty means acceptable.
   * Open networks pass with an empty password; anything else needs a
   * WPA2-PSK style passphrase of 8..63 printable ASCII characters.
   */
  fun validateProfile(ssid: String, password: String, band: Int?): List<String> {
    val problems = mutableListOf<String>()
    val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
    if (ssid.isEmpty() || ssidBytes.size > 32) {
      problems += "SSID must be 1..32 UTF-8 bytes."
    }
    if (ssid.startsWith(" ") || ssid.endsWith(" ")) {
      problems += "SSID must not start or end with a space."
    }
    if (band != null && band != 2 && band != 5 && band != 6) {
      problems += "Band must be 2, 5, 6 GHz, or unspecified."
    }
    if (password.isEmpty()) {
      // Open network: allowed; caller labels it explicitly.
    } else if (password.length < 8 || password.length > 63 ||
      password.any { it.code !in 0x20..0x7E }
    ) {
      problems += "Passphrase must be 8..63 printable ASCII characters."
    }
    return problems
  }

  // ------------------------------------------------------------------
  // Authoritative membership merge.
  // ------------------------------------------------------------------

  data class BridgeClient(
    val id: String,
    val mac: String,
    val addresses: List<String> = emptyList(),
    val hostname: String? = null,
    val lastSeenAtMillis: Long? = null,
  )

  /**
   * Merge Soft AP MACs (authoritative) with tethering IP/hostname data.
   *
   * - Every valid Soft AP MAC appears exactly once, even with no IP data.
   * - IP/hostname rows whose MAC is not associated are dropped: they never
   *   create members (prevents ARP/neighbor ghosts).
   * - Invalid MAC strings are dropped, never turned into clients.
   */
  fun mergeClients(
    softApMacs: Collection<String>,
    addressesByMac: Map<String, List<String>> = emptyMap(),
    hostnameByMac: Map<String, String?> = emptyMap(),
    nowMillis: Long? = null,
  ): List<BridgeClient> {
    val byMac = LinkedHashMap<String, MutableList<String>>()
    for (raw in softApMacs) {
      val mac = normalizeMac(raw) ?: continue
      byMac.getOrPut(mac) { mutableListOf() }
    }
    for ((rawMac, addrs) in addressesByMac) {
      val mac = normalizeMac(rawMac) ?: continue
      val known = byMac[mac] ?: continue // not associated: drop, never invent
      for (addr in addrs) {
        val clean = addr.trim()
        if (known.size >= MAX_ADDR_PER_CLIENT) break
        if (clean.isNotEmpty() && isPlausibleIp(clean) && clean !in known) {
          known += clean
        }
      }
    }
    return byMac.entries.take(MAX_CLIENTS).map { (mac, addrs) ->
      val name = hostnameByMac.entries
        .firstOrNull { normalizeMac(it.key) == mac }
        ?.value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 253 }
      BridgeClient(
        id = clientIdFor(mac),
        mac = mac,
        addresses = addrs.toList(),
        hostname = name,
        lastSeenAtMillis = nowMillis,
      )
    }
  }

  // ------------------------------------------------------------------
  // Strict parsers for stable kernel table formats (enrichment only).
  // ------------------------------------------------------------------

  /**
   * Strict `/proc/net/arp` parse: returns MAC (upper-case) to IPv4 for
   * complete entries only (ATF_COM flag set; incomplete rows and the
   * all-zero placeholder MAC are skipped). Malformed lines are skipped.
   * Input is capped at [MAX_DUMP_LINES]. Used only to enrich
   * already-associated members.
   */
  fun parseArpTable(text: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    var lines = 0
    for (line in text.lineSequence()) {
      if (lines++ >= MAX_DUMP_LINES) break
      val cols = line.trim().split(Regex("\\s+"))
      if (cols.size < 6) continue
      val ip = cols[0]
      if (!isPlausibleIp(ip) || "." !in ip) continue
      val flags = cols[2].removePrefix("0x").toIntOrNull(16) ?: 0
      if (flags and 0x2 == 0) continue // not complete: no valid link-layer address
      val mac = normalizeMac(cols[3]) ?: continue
      if (mac == ZERO_MAC) continue
      out.putIfAbsent(mac, ip)
      if (out.size >= MAX_CLIENTS) break
    }
    return out
  }

  private val NEIGH_RE =
    Regex("""^(\S+)\s+dev\s+(\S+)(?:\s+lladdr\s+([0-9A-Fa-f:]{17}))?\s+(\S+)""")

  /**
   * Strict `ip neigh show` parse: returns MAC (upper-case) to IP for rows
   * that carry a valid link-layer address. Rows without lladdr (FAILED /
   * INCOMPLETE probes) contribute nothing. Enrichment only.
   */
  fun parseNeighShow(text: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    var lines = 0
    for (line in text.lineSequence()) {
      if (lines++ >= MAX_DUMP_LINES) break
      val m = NEIGH_RE.matchEntire(line.trim()) ?: continue
      val mac = normalizeMac(m.groupValues[3]) ?: continue
      val ip = m.groupValues[1]
      if (!isPlausibleIp(ip)) continue
      out.putIfAbsent(mac, ip)
      if (out.size >= MAX_CLIENTS) break
    }
    return out
  }

  // ------------------------------------------------------------------
  // Snapshot map codec (Bundle adapters live in BundleCodec).
  // ------------------------------------------------------------------

  data class BridgeSnapshot(
    val status: String = STATUS_UNKNOWN,
    val ssid: String? = null,
    val security: String? = null,
    val tetherSupported: Boolean? = null,
    val upstream: Boolean? = null,
    val tetheredIfaces: List<String> = emptyList(),
    val clients: List<BridgeClient>? = null,
    val message: String? = null,
    val source: String = SOURCE_UNKNOWN,
    val atMillis: Long? = null,
    /**
     * True only when the shell service verified the exact API 30 control
     * shape (request start overload + int Builder ctor + exact build
     * return + Wi-Fi-only int stop). Null/false locks the request control.
     */
    val controlAvailable: Boolean? = null,
  )

  fun snapshotToMap(s: BridgeSnapshot): Map<String, Any?> = mapOf(
    KEY_VERSION to 1,
    KEY_STATUS to s.status,
    KEY_SSID to s.ssid,
    KEY_SECURITY to s.security,
    KEY_TETHER_SUPPORTED to s.tetherSupported,
    KEY_UPSTREAM to s.upstream,
    KEY_TETHERED_IFACES to s.tetheredIfaces.joinToString(","),
    KEY_CLIENTS to s.clients?.map { c ->
      mapOf(
        KEY_CLIENT_ID to c.id,
        KEY_CLIENT_MAC to c.mac,
        KEY_CLIENT_NAME to c.hostname,
        KEY_CLIENT_ADDRS to c.addresses.joinToString(","),
        KEY_CLIENT_SEEN to c.lastSeenAtMillis,
      )
    },
    KEY_MESSAGE to s.message,
    KEY_SOURCE to s.source,
    KEY_AT to s.atMillis,
    KEY_CONTROL_AVAILABLE to s.controlAvailable,
  )

  @Suppress("UNCHECKED_CAST")
  fun snapshotFromMap(m: Map<String, Any?>): BridgeSnapshot {
    fun str(key: String): String? = (m[key] as? String)?.takeIf { it.isNotEmpty() }
    val rawStatus = str(KEY_STATUS)
    // Only statuses our own shell service emits are accepted; anything
    // else is unknown rather than trusted blindly.
    val status = when (rawStatus) {
      STATUS_OFF, STATUS_ON, STATUS_UNKNOWN, STATUS_ERROR,
      HotspotBridgeContract.STATUS_STARTING, HotspotBridgeContract.STATUS_STOPPING,
      -> rawStatus
      else -> STATUS_UNKNOWN
    } ?: STATUS_UNKNOWN
    val rawClients = m[KEY_CLIENTS] as? List<*>
    val clients = rawClients?.mapNotNull { entry ->
      val e = entry as? Map<*, *> ?: return@mapNotNull null
      val mac = normalizeMac(e[KEY_CLIENT_MAC] as? String) ?: return@mapNotNull null
      BridgeClient(
        id = (e[KEY_CLIENT_ID] as? String)?.takeIf { it.isNotEmpty() } ?: clientIdFor(mac),
        mac = mac,
        addresses = (e[KEY_CLIENT_ADDRS] as? String)
          ?.split(",")?.map { it.trim() }
          ?.filter { it.isNotEmpty() && isPlausibleIp(it) }
          ?.take(MAX_ADDR_PER_CLIENT) ?: emptyList(),
        hostname = (e[KEY_CLIENT_NAME] as? String)?.trim()?.takeIf { it.isNotEmpty() },
        lastSeenAtMillis = (e[KEY_CLIENT_SEEN] as? Number)?.toLong(),
      )
    }
    return BridgeSnapshot(
      status = status,
      ssid = str(KEY_SSID)?.take(32),
      security = str(KEY_SECURITY)?.take(32),
      tetherSupported = m[KEY_TETHER_SUPPORTED] as? Boolean,
      upstream = m[KEY_UPSTREAM] as? Boolean,
      tetheredIfaces = (str(KEY_TETHERED_IFACES)
        ?.split(",")?.map { it.trim() }
        ?.filter { it.isNotEmpty() }?.take(16)) ?: emptyList(),
      clients = clients,
      message = str(KEY_MESSAGE)?.take(280),
      source = str(KEY_SOURCE)?.take(16) ?: SOURCE_UNKNOWN,
      atMillis = (m[KEY_AT] as? Number)?.toLong(),
      controlAvailable = m[KEY_CONTROL_AVAILABLE] as? Boolean,
    )
  }

  data class BridgeResult(
    val ok: Boolean,
    val code: String = CODE_FAILED,
    val op: String = "",
    val message: String = "",
  )

  fun resultToMap(r: BridgeResult): Map<String, Any?> = mapOf(
    KEY_OK to r.ok,
    KEY_CODE to r.code,
    KEY_OP to r.op,
    KEY_MESSAGE to r.message.take(280),
  )

  fun resultFromMap(op: String, m: Map<String, Any?>): BridgeResult {
    val ok = m[KEY_OK] as? Boolean ?: false
    val code = (m[KEY_CODE] as? String)?.takeIf { it.isNotEmpty() }
      ?: if (ok) CODE_OK else CODE_FAILED
    val message = (m[KEY_MESSAGE] as? String)?.take(280) ?: ""
    return BridgeResult(ok = ok, code = code, op = op, message = message)
  }

  fun invalidResult(op: String, message: String): BridgeResult =
    BridgeResult(ok = false, code = CODE_INVALID, op = op, message = message)

  // ------------------------------------------------------------------
  // Shell / caller authorization (pure, JVM-testable).
  //
  // The UserService runs as shell UID 2000 via Shizuku (non-root). The
  // Binder boundary is Process.myUid() == EXPECTED_SHELL_UID, not the
  // app-side handshake alone. Every IPC entry must validate the caller
  // before touching privileged APIs.
  // ------------------------------------------------------------------

  /** Only API 30 (Android 11) is verified on the audited device. */
  fun isSupportedSdk(sdkInt: Int): Boolean = sdkInt == 30

  /** True only for the non-root shell backend UID. */
  fun isShellIdentity(uid: Int): Boolean =
    uid == HotspotBridgeContract.EXPECTED_SHELL_UID

  /**
   * Caller is authorized only when it is the app UID that owns the
   * service or the service UID itself. Anything else is rejected with
   * SecurityException by the service, never served.
   */
  fun isCallerAuthorized(callingUid: Int, appUid: Int, serviceUid: Int): Boolean =
    callingUid == appUid || callingUid == serviceUid

  // ------------------------------------------------------------------
  // Request eligibility vs operation confirmation.
  //
  // canToggle (eligibility to *request*) is separate from a completed
  // start/stop round-trip (confirmation). Eligibility requires: bridge
  // READY (authorized shell identity) + proven tethering service
  // availability + a real ON/OFF observation. UNKNOWN, STARTING,
  // STOPPING, and ERROR never enable the control, and the control is
  // never optimistically On.
  // ------------------------------------------------------------------

  /**
   * Eligibility to request a toggle. Pure so the repository and tests
   * share one rule. tetherSupported == false explicitly disables;
   * null (unknown yet) still requires a real ON/OFF observation.
   * controlAvailable must be exactly true: a null (not yet probed) or
   * false (exact start/request/Builder/stop shape unusable) shape never
   * enables the control, so no false availability is shown.
   */
  fun isToggleRequestEligible(
    status: String,
    tetherSupported: Boolean?,
    accessReady: Boolean,
    controlAvailable: Boolean? = null,
  ): Boolean {
    if (controlAvailable != true) return false
    if (!accessReady) return false
    if (status != HotspotBridgeContract.STATUS_ON &&
      status != HotspotBridgeContract.STATUS_OFF
    ) {
      return false
    }
    if (tetherSupported == false) return false
    return true
  }

  /**
   * Authoritative membership resolution.
   *
   * - Confirmed OFF yields a stable empty list (authoritative zero).
   * - Without a membership callback ([membershipObserved] false) the
   *   result is null (unknown), even while ON: a state callback alone
   *   never justifies claiming zero associated clients.
   * - Otherwise the Soft AP MAC set is authoritative; tethering
   *   IP/hostname data only enriches associated members.
   */
  fun resolveAuthoritativeClients(
    status: String,
    membershipObserved: Boolean,
    softApMacs: Collection<String>,
    addressesByMac: Map<String, List<String>> = emptyMap(),
    hostnameByMac: Map<String, String?> = emptyMap(),
    nowMillis: Long? = null,
  ): List<BridgeClient>? {
    if (status == HotspotBridgeContract.STATUS_OFF) return emptyList()
    if (!membershipObserved) return null
    return mergeClients(softApMacs, addressesByMac, hostnameByMac, nowMillis)
  }

  // ------------------------------------------------------------------
  // Tile hint: stateless mapping, no cached toggle bit.
  // ------------------------------------------------------------------

  enum class TileHint { ACTIVE, INACTIVE, UNAVAILABLE }

  /**
   * Maps an observed hotspot status to a tile visual. UNKNOWN and ERROR
   * both render UNAVAILABLE: the tile never shows a remembered On bit.
   */
  fun tileHintFor(status: String?): TileHint = when (status) {
    STATUS_ON -> TileHint.ACTIVE
    STATUS_OFF -> TileHint.INACTIVE
    else -> TileHint.UNAVAILABLE
  }
}
