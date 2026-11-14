package com.samuel.wirelesswhale.diagnostics

import com.samuel.wirelesswhale.core.model.CheckStatus
import com.samuel.wirelesswhale.core.model.DiagnosticStep
import com.samuel.wirelesswhale.core.model.DiscoveredDevice
import com.samuel.wirelesswhale.core.model.HistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Pure, JVM-testable rules shared by the diagnostics and local-data slice.
 *
 * No Android imports here on purpose: everything in this file must stay runnable
 * under plain local unit tests. Android wrappers ([ConnectionCheckRunner],
 * [LanDiscoveryRunner], HistoryStore, ClientNicknameStore) delegate to these
 * functions so the redaction, bounds, and result-mapping behavior is verified
 * without a device.
 */
object DiagnosticsSupport {

  // Step ids emitted by ConnectionCheckRunner, in run order.
  const val STEP_ACTIVE_NETWORK = "active-network"
  const val STEP_LINK = "link"
  const val STEP_GATEWAY = "gateway"
  const val STEP_DNS = "dns"
  const val STEP_HTTPS = "https"

  /** Probe destination shown in UI/help. The only remote host diagnostics contacts. */
  const val PROBE_HOST = "connectivitycheck.gstatic.com"

  /** Full probe URL. generate_204 answers 204 with an empty body when online. */
  const val PROBE_URL = "https://connectivitycheck.gstatic.com/generate_204"

  /** Bounded local history: newest first, oldest dropped beyond this size. */
  const val HISTORY_MAX_ENTRIES = 100
  const val HISTORY_TITLE_MAX = 120
  const val HISTORY_DETAIL_MAX = 500
  const val HISTORY_KIND_MAX = 32

  /** Client nicknames are untrusted input: short, single-line display labels. */
  const val NICKNAME_MAX_LENGTH = 24

  /** LAN discovery result cap: mDNS shows responsive advertisers only, never all clients. */
  const val DISCOVERY_MAX_DEVICES = 64

  /**
   * Bounded user-started mDNS session length. Discovery always ends by
   * explicit stop, failure handling, or this timeout, so the multicast lock
   * and NSD listeners can never leak into an unbounded background session.
   */
  const val DISCOVERY_TIMEOUT_MS = 60_000L

  private val WIFI_QR_PAYLOAD = Regex("WIFI:.*?;;")
  private val SECRET_ASSIGNMENT =
    Regex("(?i)\\b(password|passwd|pass|pwd|psk|secret)\\s*[:=]\\s*(\"[^\"]*\"|'[^']*'|\\S+)")
  private val WHITESPACE_RUN = Regex("\\s+")

  /**
   * Redacts credential material from free text before it is stored or exported.
   * Whole WIFI: QR payloads are redacted (SSID included: the payload is a
   * credential bundle, not an observation), as are password=/psk: style
   * assignments. Plain SSIDs and network observations pass through untouched.
   */
  fun redactSecrets(text: String): String {
    var out = WIFI_QR_PAYLOAD.replace(text, "WIFI:[REDACTED]")
    out = SECRET_ASSIGNMENT.replace(out, "\$1=[REDACTED]")
    return out
  }

  fun truncate(text: String, max: Int): String =
    if (text.length <= max) text else text.substring(0, max)

  /** Keeps the newest-first list within [HISTORY_MAX_ENTRIES]; drops the oldest. */
  fun trimHistory(entries: List<HistoryEntry>, max: Int = HISTORY_MAX_ENTRIES): List<HistoryEntry> =
    if (entries.size <= max) entries else entries.take(max)

  /**
   * Cleans untrusted nickname input: strips control characters, trims, collapses
   * internal whitespace, and truncates to [NICKNAME_MAX_LENGTH] code points so
   * surrogate pairs are never split. Returns "" when nothing displayable remains.
   */
  fun cleanNickname(raw: String): String {
    val stripped = raw.filterNot { it.isISOControl() }
    val collapsed = stripped.trim().replace(WHITESPACE_RUN, " ")
    if (collapsed.isEmpty()) return ""
    if (collapsed.codePointCount(0, collapsed.length) <= NICKNAME_MAX_LENGTH) return collapsed
    val end = collapsed.offsetByCodePoints(0, NICKNAME_MAX_LENGTH)
    return collapsed.substring(0, end).trimEnd()
  }

  fun isValidNickname(raw: String): Boolean = cleanNickname(raw).isNotEmpty()

  /**
   * Maps an HTTPS probe outcome to a result step. Only HTTP 204 against
   * [PROBE_URL] counts as validated internet. HTTP 200 (or any 2xx/3xx with a
   * body) means something answered but is likely a captive portal or proxy, so
   * it is a warning, not a pass. DNS, gateway, and validated-state conclusions
   * stay separate: a failed HTTPS step alone never claims "no internet", it
   * reports exactly what the endpoint returned.
   */
  fun httpsStep(code: Int?, error: String?, durationMs: Long?): DiagnosticStep {
    val base = DiagnosticStep(id = STEP_HTTPS, title = "Internet check", status = CheckStatus.FAILED)
    if (error != null && code == null) {
      return base.copy(
        status = CheckStatus.FAILED,
        detail = "No answer from $PROBE_HOST ($error). Local link and DNS are reported separately.",
        durationMs = durationMs,
      )
    }
    return when (code) {
      204 -> base.copy(
        status = CheckStatus.PASSED,
        detail = "HTTP 204 from $PROBE_HOST in ${durationMs ?: -1} ms. Internet path validated at check time.",
        durationMs = durationMs,
      )
      in 200..399 -> base.copy(
        status = CheckStatus.WARNING,
        detail = "HTTP $code from $PROBE_HOST, not 204: possible captive portal or proxy. " +
          "Sign in through the portal or retry.",
        durationMs = durationMs,
      )
      else -> base.copy(
        status = CheckStatus.FAILED,
        detail = "HTTP ${code ?: "unknown"} from $PROBE_HOST. " +
          "Endpoint, DNS, and link results are separate steps; check them before concluding.",
        durationMs = durationMs,
      )
    }
  }

  /**
   * Maps a DNS outcome to a result step. A success reports the real measured
   * count and duration. A timeout names the bound; any other error is quoted
   * verbatim so DNS failure is never silently relabeled.
   */
  fun dnsStep(addressCount: Int, durationMs: Long?, error: String?): DiagnosticStep {
    val base = DiagnosticStep(id = STEP_DNS, title = "DNS resolution", status = CheckStatus.FAILED)
    if (error != null) {
      return base.copy(
        status = CheckStatus.FAILED,
        detail = "Could not resolve $PROBE_HOST ($error).",
        durationMs = durationMs,
      )
    }
    return base.copy(
      status = CheckStatus.PASSED,
      detail = "Resolved $PROBE_HOST to $addressCount address(es) in ${durationMs ?: -1} ms " +
        "(measured, not a security score).",
      durationMs = durationMs,
    )
  }

  /** Minimal JSON string escaper so history export needs no extra dependency. */
  fun jsonEscape(raw: String): String {
    val sb = StringBuilder(raw.length + 8)
    for (c in raw) {
      when (c) {
        '\\' -> sb.append("\\\\")
        '"' -> sb.append("\\\"")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c.isISOControl()) sb.append("\\u%04x".format(c.code)) else sb.append(c)
      }
    }
    return sb.toString()
  }

  /**
   * Builds the redacted history export. Entries are re-redacted here as defense
   * in depth, so export output never depends on add-time redaction alone.
   * The list is capped to [HISTORY_MAX_ENTRIES] so export size stays bounded
   * even if a caller passes a longer list.
   */
  fun buildHistoryExport(entries: List<HistoryEntry>): String {
    val bounded = trimHistory(entries)
    val sb = StringBuilder("[")
    bounded.forEachIndexed { index, entry ->
      if (index > 0) sb.append(',')
      sb.append("{\"id\":\"")
      sb.append(jsonEscape(entry.id))
      sb.append("\",\"title\":\"")
      sb.append(jsonEscape(redactSecrets(entry.title)))
      sb.append("\",\"detail\":\"")
      sb.append(jsonEscape(redactSecrets(entry.detail)))
      sb.append("\",\"atMillis\":")
      sb.append(entry.atMillis)
      sb.append(",\"kind\":\"")
      sb.append(jsonEscape(redactSecrets(entry.kind)))
      sb.append("\"}")
    }
    sb.append(']')
    return sb.toString()
  }

  // ---- LAN discovery session helpers (pure; LanDiscoveryRunner delegates) ----

  /** Stable key for one (service type, instance name) pair. */
  fun discoveryKey(serviceType: String, serviceName: String?): String =
    "$serviceType|${serviceName ?: "unknown"}"

  /** True when a callback belongs to a previous session and must be ignored. */
  fun isStaleDiscoveryCallback(callbackGeneration: Int, currentGeneration: Int): Boolean =
    callbackGeneration != currentGeneration

  /**
   * Inserts [device] into [current] with a hard bound. Updates in place when
   * the id already exists; otherwise evicts the oldest entry first when at
   * capacity. Returns the evicted id, or null when nothing was evicted.
   */
  fun insertDiscoveryBounded(
    current: LinkedHashMap<String, DiscoveredDevice>,
    device: DiscoveredDevice,
    max: Int = DISCOVERY_MAX_DEVICES,
  ): String? {
    if (current.containsKey(device.id)) {
      current[device.id] = device
      return null
    }
    var evicted: String? = null
    val cap = max.coerceAtLeast(1)
    while (current.size >= cap) {
      val oldest = current.keys.firstOrNull() ?: break
      current.remove(oldest)
      evicted = oldest
    }
    current[device.id] = device
    return evicted
  }

  /**
   * Serialized resolve queue with dedup. API-30-era NSD resolution is driven
   * one at a time: [offer] dedups, [takeNext] starts the next id only when
   * nothing is active, [finish] clears the active id and returns the next id
   * to start (or null when idle). [remove] drops a lost service before it
   * resolves so a stale resolve can never resurrect a removed entry.
   */
  class ResolveQueue {
    private val queue = ArrayDeque<String>()
    private val queued = LinkedHashSet<String>()
    private var active: String? = null

    fun offer(id: String): Boolean {
      if (id == active || queued.contains(id)) return false
      queue.addLast(id)
      queued.add(id)
      return true
    }

    fun remove(id: String): Boolean {
      if (!queued.remove(id)) return false
      queue.remove(id)
      return true
    }

    /** Returns the next id to resolve, marking it active, or null when busy/empty. */
    fun takeNext(): String? {
      if (active != null) return null
      val next = queue.removeFirstOrNull() ?: return null
      queued.remove(next)
      active = next
      return next
    }

    /**
     * Marks [id] finished and returns the next id to start, or null.
     * Finish is idempotent: finishing a non-active id just pumps the queue.
     */
    fun finish(id: String): String? {
      if (active == id) active = null
      if (active != null) return null
      val next = queue.removeFirstOrNull() ?: return null
      queued.remove(next)
      active = next
      return next
    }

    fun clear() {
      queue.clear()
      queued.clear()
      active = null
    }

    fun activeId(): String? = active
    fun pendingCount(): Int = queue.size
  }

  // ---- Cancellation helpers (pure; ConnectionCheckRunner delegates) ----

  /**
   * True only for a genuine coroutine timeout. User cancellation is a plain
   * [CancellationException] and must propagate; a timeout becomes a normal
   * FAILED step so later probes still run.
   */
  fun isTimeoutCancellation(e: CancellationException): Boolean =
    e is TimeoutCancellationException

  /**
   * Marks the in-flight RUNNING step as cancelled by the user. Terminal
   * states are returned unchanged.
   */
  fun markStepCancelled(step: DiagnosticStep): DiagnosticStep {
    if (step.status != CheckStatus.RUNNING) return step
    return step.copy(
      status = CheckStatus.FAILED,
      detail = "${step.detail} Cancelled by user.".trim(),
    )
  }
}
