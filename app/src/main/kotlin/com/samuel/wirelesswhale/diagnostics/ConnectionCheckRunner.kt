package com.samuel.wirelesswhale.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.samuel.wirelesswhale.core.model.CheckStatus
import com.samuel.wirelesswhale.core.model.DiagnosticReport
import com.samuel.wirelesswhale.core.model.DiagnosticStep
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * User-started connection diagnostics. Nothing here runs on its own: the only
 * entry point is [run], called from an explicit Run action, and every external
 * probe (DNS, HTTPS) happens only inside that call.
 *
 * Probes, in order: active network, Wi-Fi link, gateway, DNS, HTTPS.
 * Each step is emitted incrementally through [onStep] (RUNNING first, then its
 * terminal state), so the UI can render running, canceled, partial, and
 * complete states from the same stream. A failing step never aborts the run;
 * remaining steps still execute and the report stays partial-but-honest.
 *
 * Cancellation coherence (runner plus ViewModel):
 * - A genuine coroutine timeout inside DNS/HTTPS becomes a normal FAILED step
 *   for that probe only; later probes still run. Timeouts never set
 *   `cancelled = true`.
 * - User cancellation (a plain [CancellationException]) always propagates out
 *   of the in-flight probe, aborts the remaining probes, marks the in-flight
 *   RUNNING step as cancelled, and returns `DiagnosticReport(cancelled = true)`.
 *   [run] does not rethrow user cancellation, so the `onStep` stream always
 *   ends with a terminal report instead of a dangling RUNNING row.
 * - `WorkstationViewModel.runDiagnostics` treats both paths as cancelled: it
 *   uses the returned `cancelled` flag for the normal path and still catches
 *   [CancellationException] for races (cancel before the first emit or while
 *   the report is being delivered). Both converge on the same canceled UI and
 *   the same "Diagnostics cancelled" history entry.
 *
 * Network behavior and bounds (also surface these in UI/help, not just here):
 * - Destination: only [DiagnosticsSupport.PROBE_URL] (GET, no request body).
 *   No other host is contacted. Response body is capped at 4 KB and discarded;
 *   only the status code is interpreted.
 * - DNS timeout 10 s, HTTPS connect 8 s / read 8 s / overall 20 s cap.
 *   `Network.getAllByName` blocks on Dispatchers.IO and cannot be unblocked by
 *   coroutine cancellation alone: a DNS timeout abandons the wait and records
 *   a FAILED step while the stray OS lookup may linger briefly on its own IO
 *   thread. Later probes still run because the timeout is converted, not
 *   rethrown. User cancellation still propagates immediately between steps.
 * - The HTTPS connection is tracked and explicitly disconnected when its
 *   coroutine is cancelled, so a blocked connect/read does not linger after
 *   Cancel. The disconnect is idempotent with the normal finally-disconnect.
 * - Default platform routing is used deliberately: no `bindProcessToNetwork`
 *   and no per-`Network` socket pinning, so an active VPN keeps carrying the
 *   traffic and the HTTPS result reflects the path the user actually has.
 *   The active-network step reports VPN presence instead of bypassing it.
 * - Only HTTP 204 counts as validated internet. HTTP 200/3xx is a captive
 *   portal warning, never a pass. A failed ICMP-style assumption is never
 *   made: no ping is sent, and gateway/DNS/validated conclusions are separate
 *   steps, so an HTTPS failure alone never claims "no internet".
 * - RSSI and link speed are radio measurements, not internet throughput, and
 *   are labeled as such. Scan/sample timestamps are check times, not redraw
 *   times.
 * - Safe for API 30 through 36: ConnectivityManager/NetworkCapabilities are
 *   API 21+, `transportInfo` is API 29+. The DHCP + connectionInfo fallbacks
 *   are deprecated in newer APIs but remain functional; their use is the
 *   API-30-correct path and is wrapped in permission-safe try/catch.
 * - `CancellationException` is never swallowed by broad catches: every
 *   `runCatching`-style helper rethrows it, timeouts are caught as
 *   [TimeoutCancellationException] explicitly, and only timeouts become FAILED
 *   steps.
 */
class ConnectionCheckRunner(context: Context) {

  private val appContext = context.applicationContext
  private val connectivity: ConnectivityManager =
    appContext.getSystemService(ConnectivityManager::class.java)

  private val wifi: WifiManager? = try {
    appContext.getSystemService(WifiManager::class.java)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    null
  }

  companion object {
    const val DNS_TIMEOUT_MS = 10_000L
    const val HTTPS_CONNECT_TIMEOUT_MS = 8_000
    const val HTTPS_READ_TIMEOUT_MS = 8_000
    const val HTTPS_OVERALL_TIMEOUT_MS = 20_000L
    const val MAX_RESPONSE_BYTES = 4 * 1024
  }

  suspend fun run(onStep: (DiagnosticStep) -> Unit): DiagnosticReport {
    val startedAt = System.currentTimeMillis()
    val finished = mutableListOf<DiagnosticStep>()
    var cancelled = false

    fun emit(step: DiagnosticStep) {
      val index = finished.indexOfFirst { it.id == step.id }
      if (index >= 0) finished[index] = step else finished.add(step)
      onStep(step)
    }

    val probes: List<Pair<String, suspend () -> DiagnosticStep>> = listOf(
      DiagnosticsSupport.STEP_ACTIVE_NETWORK to ::checkActiveNetwork,
      DiagnosticsSupport.STEP_LINK to ::checkLink,
      DiagnosticsSupport.STEP_GATEWAY to ::checkGateway,
      DiagnosticsSupport.STEP_DNS to ::checkDns,
      DiagnosticsSupport.STEP_HTTPS to ::checkHttps,
    )

    try {
      for ((id, probe) in probes) {
        val running = DiagnosticStep(
          id = id,
          title = titleFor(id),
          status = CheckStatus.RUNNING,
          detail = "Checking…",
        )
        emit(running)
        try {
          emit(probe())
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          emit(
            DiagnosticStep(
              id = id,
              title = titleFor(id),
              status = CheckStatus.FAILED,
              detail = "Check could not complete (${e.message ?: e.javaClass.simpleName}).",
            ),
          )
        }
      }
    } catch (e: CancellationException) {
      cancelled = true
      val last = finished.lastOrNull()
      if (last != null && last.status == CheckStatus.RUNNING) {
        emit(DiagnosticsSupport.markStepCancelled(last))
      }
      // Intentionally not rethrown: the caller (ViewModel) treats a returned
      // cancelled report and a thrown CancellationException identically.
    }

    return DiagnosticReport(
      steps = finished.toList(),
      startedAtMillis = startedAt,
      finishedAtMillis = System.currentTimeMillis(),
      cancelled = cancelled,
    )
  }

  private fun titleFor(id: String): String = when (id) {
    DiagnosticsSupport.STEP_ACTIVE_NETWORK -> "Active network"
    DiagnosticsSupport.STEP_LINK -> "Wi-Fi link"
    DiagnosticsSupport.STEP_GATEWAY -> "Gateway"
    DiagnosticsSupport.STEP_DNS -> "DNS resolution"
    DiagnosticsSupport.STEP_HTTPS -> "Internet check"
    else -> id
  }

  /**
   * Null-on-error helper that never swallows coroutine cancellation.
   * Replaces `runCatching { }.getOrNull()` for every platform query.
   */
  private inline fun <T> queryOrNull(block: () -> T): T? {
    return try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }
  }

  private fun activeNetwork(): Network? = queryOrNull { connectivity.activeNetwork }

  private fun capabilitiesOf(network: Network?): NetworkCapabilities? {
    if (network == null) return null
    return queryOrNull { connectivity.getNetworkCapabilities(network) }
  }

  private suspend fun checkActiveNetwork(): DiagnosticStep {
    val id = DiagnosticsSupport.STEP_ACTIVE_NETWORK
    val started = SystemClock.elapsedRealtime()
    val network = activeNetwork()
    if (network == null) {
      return DiagnosticStep(
        id = id,
        title = titleFor(id),
        status = CheckStatus.FAILED,
        detail = "No active network. Turn on Wi-Fi or mobile data, then run again.",
        durationMs = SystemClock.elapsedRealtime() - started,
      )
    }
    val caps = capabilitiesOf(network)
    if (caps == null) {
      return DiagnosticStep(
        id = id,
        title = titleFor(id),
        status = CheckStatus.WARNING,
        detail = "A network is active but its capabilities are unavailable; later steps may be limited.",
        durationMs = SystemClock.elapsedRealtime() - started,
      )
    }
    val transports = buildList {
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
    }.ifEmpty { listOf("unknown transport") }
    val vpnActive = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    val detail = buildString {
      append("Active: ${transports.joinToString(", ")}. ")
      append(if (metered) "Metered. " else "Unmetered. ")
      if (vpnActive) append("VPN is active; checks use the normal route and do not bypass it. ")
      append(
        if (validated) "Platform reports this network as validated."
        else "Platform has not marked this network validated; see Internet check below.",
      )
    }
    return DiagnosticStep(
      id = id,
      title = titleFor(id),
      status = CheckStatus.PASSED,
      detail = detail,
      durationMs = SystemClock.elapsedRealtime() - started,
    )
  }

  private suspend fun checkLink(): DiagnosticStep {
    val id = DiagnosticsSupport.STEP_LINK
    val started = SystemClock.elapsedRealtime()
    val caps = capabilitiesOf(activeNetwork())
    if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
      return DiagnosticStep(
        id = id,
        title = titleFor(id),
        status = CheckStatus.WARNING,
        detail = "Active network is not Wi-Fi; Wi-Fi link details do not apply.",
        durationMs = SystemClock.elapsedRealtime() - started,
      )
    }
    val info = queryOrNull { caps?.transportInfo as? WifiInfo }
      ?: queryOrNull { wifi?.connectionInfo }
    if (info == null || info.networkId == -1) {
      return DiagnosticStep(
        id = id,
        title = titleFor(id),
        status = CheckStatus.WARNING,
        detail = "No Wi-Fi link info available (not associated, or permission/location state hides it).",
        durationMs = SystemClock.elapsedRealtime() - started,
      )
    }
    val ssid = queryOrNull { info.ssid?.removeSurrounding("\"") }
    return DiagnosticStep(
      id = id,
      title = titleFor(id),
      status = CheckStatus.PASSED,
      detail = buildString {
        if (ssid != null && ssid != "<unknown ssid>") append("SSID $ssid. ")
        append("RSSI ${info.rssi} dBm")
        val tx = queryOrNull { info.txLinkSpeedMbps }
        val rx = queryOrNull { info.rxLinkSpeedMbps }
        if (tx != null && tx > 0) append(", Tx $tx Mbps")
        if (rx != null && rx > 0) append(", Rx $rx Mbps")
        append(". Radio measurement at check time, not internet throughput.")
      },
      durationMs = SystemClock.elapsedRealtime() - started,
    )
  }

  @SuppressLint("MissingPermission")
  private suspend fun checkGateway(): DiagnosticStep {
    val id = DiagnosticsSupport.STEP_GATEWAY
    val started = SystemClock.elapsedRealtime()
    val caps = capabilitiesOf(activeNetwork())
    if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
      return DiagnosticStep(
        id = id,
        title = titleFor(id),
        status = CheckStatus.WARNING,
        detail = "Gateway reporting here covers Wi-Fi only; the active network is not Wi-Fi.",
        durationMs = SystemClock.elapsedRealtime() - started,
      )
    }
    val gateway = queryOrNull {
      val dhcp = wifi?.dhcpInfo ?: return@queryOrNull null
      if (dhcp.gateway == 0) null else intToIp(dhcp.gateway)
    }
    if (gateway == null) {
      return DiagnosticStep(
        id = id,
        title = titleFor(id),
        status = CheckStatus.WARNING,
        detail = "No DHCP gateway reported. Address observed only; no reachability claim is made.",
        durationMs = SystemClock.elapsedRealtime() - started,
      )
    }
    return DiagnosticStep(
      id = id,
      title = titleFor(id),
      status = CheckStatus.PASSED,
      detail = "Gateway $gateway observed via DHCP. No ping was sent: " +
        "ICMP failure must never be read as no internet.",
      durationMs = SystemClock.elapsedRealtime() - started,
    )
  }

  private suspend fun checkDns(): DiagnosticStep {
    val started = SystemClock.elapsedRealtime()
    val network = activeNetwork()
    return try {
      val addresses: Array<InetAddress> = withTimeout(DNS_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
          if (network != null) network.getAllByName(DiagnosticsSupport.PROBE_HOST)
          else InetAddress.getAllByName(DiagnosticsSupport.PROBE_HOST)
        }
      }
      DiagnosticsSupport.dnsStep(
        addressCount = addresses.size,
        durationMs = SystemClock.elapsedRealtime() - started,
        error = null,
      )
    } catch (e: TimeoutCancellationException) {
      DiagnosticsSupport.dnsStep(
        addressCount = 0,
        durationMs = SystemClock.elapsedRealtime() - started,
        error = "timed out after ${DNS_TIMEOUT_MS} ms",
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      DiagnosticsSupport.dnsStep(
        addressCount = 0,
        durationMs = SystemClock.elapsedRealtime() - started,
        error = e.message ?: e.javaClass.simpleName,
      )
    }
  }

  private suspend fun checkHttps(): DiagnosticStep {
    val started = SystemClock.elapsedRealtime()
    val live = AtomicReference<HttpsURLConnection?>(null)
    val completion: DisposableHandle? = currentCoroutineContext()[Job]?.invokeOnCompletion { cause: Throwable? ->
      if (cause is CancellationException) {
        runCatching { live.get()?.disconnect() }
      }
    }
    return try {
      val (code, error) = withTimeout(HTTPS_OVERALL_TIMEOUT_MS) {
        withContext(Dispatchers.IO) { fetchGenerate204(track = { live.set(it) }) }
      }
      if (error != null && code == null) {
        DiagnosticsSupport.httpsStep(
          code = null,
          error = error,
          durationMs = SystemClock.elapsedRealtime() - started,
        )
      } else {
        DiagnosticsSupport.httpsStep(
          code = code,
          error = null,
          durationMs = SystemClock.elapsedRealtime() - started,
        )
      }
    } catch (e: TimeoutCancellationException) {
      DiagnosticsSupport.httpsStep(
        code = null,
        error = "timed out (connect ${HTTPS_CONNECT_TIMEOUT_MS} ms, read ${HTTPS_READ_TIMEOUT_MS} ms)",
        durationMs = SystemClock.elapsedRealtime() - started,
      )
    } catch (e: CancellationException) {
      runCatching { live.get()?.disconnect() }
      throw e
    } finally {
      runCatching { completion?.dispose() }
    }
  }

  /**
   * Returns (code, error): exactly one is non-null. Default routing; never pinned past VPN.
   * The live connection is reported through [track] so cancellation can disconnect
   * a thread blocked in connect/read. Never swallows [CancellationException].
   */
  private fun fetchGenerate204(track: (HttpsURLConnection?) -> Unit = {}): Pair<Int?, String?> {
    var connection: HttpsURLConnection? = null
    return try {
      connection = (URL(DiagnosticsSupport.PROBE_URL).openConnection() as HttpsURLConnection).apply {
        connectTimeout = HTTPS_CONNECT_TIMEOUT_MS
        readTimeout = HTTPS_READ_TIMEOUT_MS
        instanceFollowRedirects = false
        requestMethod = "GET"
        setRequestProperty("Connection", "close")
      }
      track(connection)
      connection.connect()
      val code = connection.responseCode
      if (code != HttpURLConnection.HTTP_NO_CONTENT) {
        val stream = queryOrNull {
          if (code in 200..399) connection.inputStream else connection.errorStream
        }
        if (stream != null) {
          stream.use {
            val buf = ByteArray(1024)
            var remaining = MAX_RESPONSE_BYTES
            while (remaining > 0) {
              val n = it.read(buf, 0, minOf(buf.size, remaining))
              if (n < 0) break
              remaining -= n
            }
          }
        }
      }
      code to null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null to (e.message ?: e.javaClass.simpleName)
    } finally {
      track(null)
      runCatching { connection?.disconnect() }
    }
  }

  private fun intToIp(addr: Int): String =
    "${addr and 0xFF}.${(addr shr 8) and 0xFF}.${(addr shr 16) and 0xFF}.${(addr shr 24) and 0xFF}"
}
