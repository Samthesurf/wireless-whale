package com.samuel.wirelesswhale.platform.publicapi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.samuel.wirelesswhale.core.domain.LOCATION_DISABLED_MESSAGE
import com.samuel.wirelesswhale.core.domain.SCAN_PERMISSION_MESSAGE
import com.samuel.wirelesswhale.core.domain.ScanBudget
import com.samuel.wirelesswhale.core.domain.canRequestScan
import com.samuel.wirelesswhale.core.domain.internetStateFor
import com.samuel.wirelesswhale.core.domain.nextScanAllowedInMillis
import com.samuel.wirelesswhale.core.domain.pruneScanHistory
import com.samuel.wirelesswhale.core.domain.refreshedScanMessage
import com.samuel.wirelesswhale.core.domain.resolveConnectionSsid
import com.samuel.wirelesswhale.core.domain.sanitizeBssid
import com.samuel.wirelesswhale.core.domain.sanitizeScanSsid
import com.samuel.wirelesswhale.core.domain.scanDeliveryMessage
import com.samuel.wirelesswhale.core.domain.scanResultWallSeenAtMillis
import com.samuel.wirelesswhale.core.domain.securityFromCapabilities
import com.samuel.wirelesswhale.core.domain.shouldPreserveNetworksOnDelivery
import com.samuel.wirelesswhale.core.domain.shouldRefreshLastScanAt
import com.samuel.wirelesswhale.core.domain.sortWifiNetworks
import com.samuel.wirelesswhale.core.domain.wifiScanPermissionGranted
import com.samuel.wirelesswhale.core.model.InternetState
import com.samuel.wirelesswhale.core.model.ScanState
import com.samuel.wirelesswhale.core.model.WifiNetwork
import com.samuel.wirelesswhale.core.model.WifiSnapshot
import com.samuel.wirelesswhale.core.platform.WifiRepository
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Real Wi-Fi repository backed by public Android APIs (API 29+, verified
 * target device API 30). Connection state flows from a
 * ConnectivityManager NetworkCallback; scan results flow from the
 * SCAN_RESULTS_AVAILABLE_ACTION broadcast. No polling loops and no
 * persistent background scanning: scans run only on explicit [requestScan].
 *
 * Reported timestamps are wall-clock times of actual scan delivery, never
 * redraw times. RSSI and link rates are raw radio measurements, not
 * throughput. No SSID, BSSID, password, or credential payload is logged.
 */
class AndroidWifiRepository(
  context: Context,
  private val budget: ScanBudget = ScanBudget(),
  private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
  private val wallClock: () -> Long = System::currentTimeMillis,
  private val monotonicClock: () -> Long = SystemClock::elapsedRealtime,
  private val scanTimeoutMillis: Long = SCAN_TIMEOUT_MILLIS,
) : WifiRepository {

  private val appContext: Context = context.applicationContext
  private val wifiManager: WifiManager? =
    appContext.getSystemService(WifiManager::class.java)
  private val connectivityManager: ConnectivityManager? =
    appContext.getSystemService(ConnectivityManager::class.java)
  private val locationManager: LocationManager? =
    appContext.getSystemService(LocationManager::class.java)

  private val started = AtomicBoolean(false)
  private val historyLock = Any()
  // Scan attempt history uses the monotonic clock so wall-clock shifts never
  // corrupt throttling; only displayed timestamps use wall time.
  private val scanHistory = mutableListOf<Long>()
  private val scanAttempt = AtomicLong(0L)
  @Volatile private var scanTimeoutJob: Job? = null

  private val _connection = MutableStateFlow(WifiSnapshot())
  override val connection: StateFlow<WifiSnapshot> = _connection.asStateFlow()

  private val _networks = MutableStateFlow<List<WifiNetwork>>(emptyList())
  override val networks: StateFlow<List<WifiNetwork>> = _networks.asStateFlow()

  private val _scanState = MutableStateFlow(ScanState())
  override val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

  private var scope = CoroutineScope(SupervisorJob() + dispatcher)

  private val networkCallback =
    object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        refreshConnectionSnapshot()
      }

      override fun onLost(network: Network) {
        refreshConnectionSnapshot()
      }

      override fun onCapabilitiesChanged(
        network: Network,
        networkCapabilities: NetworkCapabilities,
      ) {
        refreshConnectionSnapshot()
      }

      override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
        refreshConnectionSnapshot()
      }
    }

  private val scanReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
          WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
            val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)
            onScanResultsDelivered(wallClock(), monotonicClock() * 1_000L, updated)
          }
          WifiManager.WIFI_STATE_CHANGED_ACTION,
          WifiManager.NETWORK_STATE_CHANGED_ACTION,
          WifiManager.RSSI_CHANGED_ACTION -> refreshConnectionSnapshot()
        }
      }
    }

  override fun start() {
    if (!started.compareAndSet(false, true)) return
    scope = CoroutineScope(SupervisorJob() + dispatcher)
    try {
      connectivityManager?.registerDefaultNetworkCallback(networkCallback)
    } catch (e: Exception) {
      Log.w(TAG, "NetworkCallback registration failed")
    }
    try {
      // The default network alone misses local-only Wi-Fi and hides the
      // underlying Wi-Fi while a VPN carries the default route, so observe
      // the Wi-Fi transport directly as well. One unregister call below
      // releases both registrations for this shared callback.
      val wifiRequest =
        NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
      connectivityManager?.registerNetworkCallback(wifiRequest, networkCallback)
    } catch (e: Exception) {
      Log.w(TAG, "Wi-Fi network callback registration failed")
    }
    try {
      val filter =
        IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION).apply {
          addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
          addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
          addAction(WifiManager.RSSI_CHANGED_ACTION)
        }
      ContextCompat.registerReceiver(
        appContext,
        scanReceiver,
        filter,
        ContextCompat.RECEIVER_NOT_EXPORTED,
      )
    } catch (e: Exception) {
      Log.w(TAG, "Scan receiver registration failed")
    }
    refreshPermissions()
  }

  override fun refreshPermissions() {
    val hasPermission = hasScanPermission()
    val locationOn = isLocationEnabled()
    _scanState.update {
      it.copy(
        permissionRequired = !hasPermission,
        locationRequired = hasPermission && !locationOn,
        throttled = false,
        message =
          refreshedScanMessage(
            previous = it.message,
            previouslyBlocked = it.permissionRequired || it.locationRequired,
            hasPermission = hasPermission,
            locationEnabled = locationOn,
          ),
      )
    }
    if (!hasPermission) {
      _networks.value = emptyList()
    }
    _connection.update {
      it.copy(
        permissionRequired = !hasPermission,
        locationEnabled = locationOn,
        lastUpdatedAtMillis = wallClock(),
      )
    }
    // Permission and location changes also change which identifiers are
    // visible, so re-read the actual connection instead of keeping a stale
    // snapshot or a stale permission message.
    refreshConnectionSnapshot()
  }

  override fun requestScan() {
    if (!started.get()) {
      _scanState.update { it.copy(message = "Wi-Fi observation is not started.") }
      return
    }
    val hasPermission = hasScanPermission()
    val locationOn = isLocationEnabled()
    if (!hasPermission) {
      _scanState.update {
        it.copy(
          scanning = false,
          permissionRequired = true,
          locationRequired = false,
          throttled = false,
          message = SCAN_PERMISSION_MESSAGE,
        )
      }
      _networks.value = emptyList()
      return
    }
    if (!locationOn) {
      _scanState.update {
        it.copy(
          scanning = false,
          permissionRequired = false,
          locationRequired = true,
          throttled = false,
          message = LOCATION_DISABLED_MESSAGE,
        )
      }
      return
    }
    val manager = wifiManager
    if (manager == null) {
      _scanState.update {
        it.copy(scanning = false, message = "Wi-Fi service is unavailable on this device.")
      }
      return
    }
    val now = monotonicClock()
    val allowed =
      synchronized(historyLock) {
        canRequestScan(now, scanHistory.toList(), budget)
      }
    if (!allowed) {
      val waitMillis =
        synchronized(historyLock) {
          nextScanAllowedInMillis(now, scanHistory.toList(), budget)
        }
      val waitSecs = (waitMillis + 999L) / 1000L
      _scanState.update {
        it.copy(
          scanning = false,
          throttled = true,
          message = "Scan throttled to protect system quota. Retry in about $waitSecs seconds.",
        )
      }
      return
    }
    val startedScan =
      try {
        manager.startScan()
      } catch (e: SecurityException) {
        Log.w(TAG, "Scan blocked by missing permission")
        _scanState.update {
          it.copy(
            scanning = false,
            permissionRequired = true,
            message = SCAN_PERMISSION_MESSAGE,
          )
        }
        _networks.value = emptyList()
        return
      } catch (e: Exception) {
        Log.w(TAG, "Scan request failed")
        false
      }
    if (!startedScan) {
      _scanState.update {
        it.copy(
          scanning = false,
          throttled = true,
          message = "The system throttled this scan. Wait a little and try again.",
        )
      }
      return
    }
    synchronized(historyLock) {
      scanHistory.add(monotonicClock())
      val pruned = pruneScanHistory(scanHistory, monotonicClock(), budget)
      scanHistory.clear()
      scanHistory.addAll(pruned)
    }
    _scanState.update {
      it.copy(
        scanning = true,
        throttled = false,
        permissionRequired = false,
        locationRequired = false,
        message = null,
      )
    }
    armScanTimeout()
  }

  override fun close() {
    if (!started.compareAndSet(true, false)) return
    cancelPendingScan()
    try {
      connectivityManager?.unregisterNetworkCallback(networkCallback)
    } catch (e: IllegalArgumentException) {
      Log.w(TAG, "NetworkCallback was not registered")
    } catch (e: Exception) {
      Log.w(TAG, "NetworkCallback unregister failed")
    }
    try {
      appContext.unregisterReceiver(scanReceiver)
    } catch (e: IllegalArgumentException) {
      Log.w(TAG, "Scan receiver was not registered")
    } catch (e: Exception) {
      Log.w(TAG, "Scan receiver unregister failed")
    }
    scope.cancel()
    scope = CoroutineScope(SupervisorJob() + dispatcher)
    _scanState.update { it.copy(scanning = false) }
  }

  /**
   * Bounds every scan with a timeout so a lost broadcast can never leave the
   * spinner on forever. Each attempt carries a token; late deliveries and
   * newer attempts invalidate older timeouts instead of racing them.
   */
  private fun armScanTimeout() {
    val attempt = scanAttempt.incrementAndGet()
    scanTimeoutJob?.cancel()
    scanTimeoutJob = scope.launch {
      delay(scanTimeoutMillis)
      if (scanAttempt.get() == attempt && _scanState.value.scanning) {
        _scanState.update {
          it.copy(
            scanning = false,
            throttled = false,
            message = "The scan timed out waiting for results. Try again.",
          )
        }
      }
    }
  }

  private fun cancelPendingScan() {
    scanAttempt.incrementAndGet()
    scanTimeoutJob?.cancel()
    scanTimeoutJob = null
  }

  private fun onScanResultsDelivered(
    receivedWallMillis: Long,
    receivedBootMicros: Long,
    updated: Boolean,
  ) {
    if (!started.get()) return
    if (!hasScanPermission()) {
      _networks.value = emptyList()
      _scanState.update {
        it.copy(
          scanning = false,
          permissionRequired = true,
          message = SCAN_PERMISSION_MESSAGE,
        )
      }
      return
    }
    val results: List<ScanResult> =
      try {
        wifiManager?.scanResults.orEmpty()
      } catch (e: SecurityException) {
        Log.w(TAG, "Scan results blocked by missing permission")
        _networks.value = emptyList()
        _scanState.update {
          it.copy(
            scanning = false,
            permissionRequired = true,
            message = SCAN_PERMISSION_MESSAGE,
          )
        }
        return
      } catch (e: Exception) {
        Log.w(TAG, "Reading scan results failed")
        _scanState.update {
          it.copy(scanning = false, message = "Could not read scan results.")
        }
        return
      }
    val connectedBssid = _connection.value.bssid
    cancelPendingScan()
    val mapped =
      results
        .mapNotNull { result ->
          toWifiNetwork(
            result,
            scanResultWallSeenAtMillis(receivedWallMillis, receivedBootMicros, result.timestamp),
          )
        }
        .groupBy { it.id }
        .map { (_, group) -> group.maxBy { it.rssiDbm } }
    if (shouldPreserveNetworksOnDelivery(updated, results.size)) {
      // An empty cached delivery carries no new observation: keep the previous
      // networks and timestamp instead of stamping them fresh now.
      _scanState.update {
        it.copy(
          scanning = false,
          throttled = false,
          permissionRequired = false,
          locationRequired = false,
          message = scanDeliveryMessage(resultsUpdated = false, resultCount = 0),
        )
      }
      return
    }
    _networks.value = sortWifiNetworks(mapped, connectedBssid)
    _scanState.update {
      it.copy(
        scanning = false,
        lastScanAtMillis =
          if (shouldRefreshLastScanAt(updated)) {
            receivedWallMillis
          } else {
            it.lastScanAtMillis
          },
        throttled = false,
        permissionRequired = false,
        locationRequired = false,
        message = scanDeliveryMessage(updated, mapped.size),
      )
    }
  }

  private fun toWifiNetwork(result: ScanResult, seenAtMillis: Long): WifiNetwork? {
    val ssid = sanitizeScanSsid(result.SSID) ?: return null
    val bssid = sanitizeBssid(result.BSSID)
    val id = bssid ?: "$ssid|${result.frequency}"
    return WifiNetwork(
      id = id,
      ssid = ssid,
      bssid = bssid,
      rssiDbm = result.level,
      frequencyMhz = result.frequency,
      security = securityFromCapabilities(result.capabilities.orEmpty()),
      capabilities = result.capabilities.orEmpty(),
      lastSeenAtMillis = seenAtMillis,
    )
  }

  private fun refreshConnectionSnapshot() {
    if (!started.get()) return
    val now = wallClock()
    val cm = connectivityManager
    if (cm == null) {
      _connection.update {
        it.copy(
          enabled = isWifiEnabled(),
          connected = false,
          internet = InternetState.UNKNOWN,
          lastUpdatedAtMillis = now,
          message = "Connectivity service is unavailable.",
        )
      }
      return
    }
    val (wifiNetwork, caps) = findWifiNetwork(cm)
    val wifiEnabled = isWifiEnabled()
    if (wifiNetwork == null || caps == null) {
      _connection.update {
        it.copy(
          enabled = wifiEnabled,
          connected = false,
          internet = InternetState.DISCONNECTED,
          ssid = null,
          bssid = null,
          rssiDbm = null,
          frequencyMhz = null,
          linkSpeedMbps = null,
          txLinkSpeedMbps = null,
          rxLinkSpeedMbps = null,
          ipv4 = emptyList(),
          ipv6 = emptyList(),
          gateway = null,
          dnsServers = emptyList(),
          metered = null,
          vpnActive = isVpnActive(),
          permissionRequired = !hasScanPermission(),
          locationEnabled = isLocationEnabled(),
          lastUpdatedAtMillis = now,
          message = if (!wifiEnabled) "Wi-Fi is off." else null,
        )
      }
      return
    }
    val transportInfo = caps.getTransportInfo() as? android.net.wifi.WifiInfo
    // On API 30 and below the transport info does not always carry the current
    // SSID, so fall back to the public connectionInfo snapshot. This uses only
    // public APIs and stays gated on the location permission, so no identifier
    // is surfaced without a grant.
    @Suppress("DEPRECATION")
    val fallbackInfo =
      if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && hasScanPermission()) {
        runCatching { wifiManager?.connectionInfo }.getOrNull()
      } else {
        null
      }
    val ssid = resolveConnectionSsid(transportInfo?.ssid, fallbackInfo?.ssid)
    val bssid = sanitizeBssid(transportInfo?.bssid) ?: sanitizeBssid(fallbackInfo?.bssid)
    val linkProps = wifiNetwork.let { runCatching { cm.getLinkProperties(it) }.getOrNull() }
    val v4 = mutableListOf<String>()
    val v6 = mutableListOf<String>()
    linkProps?.linkAddresses?.forEach { link ->
      val host = link.address?.hostAddress ?: return@forEach
      when (link.address) {
        is Inet4Address -> v4.add(host)
        is Inet6Address -> v6.add(host)
      }
    }
    val dns = linkProps?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
    val gateway = linkProps?.routes?.firstOrNull { it.gateway != null }?.gateway?.hostAddress
    val internet =
      internetStateFor(
        hasCapabilities = true,
        hasInternetCapability = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        captivePortal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
      )
    val metered = runCatching { cm.isActiveNetworkMetered }.getOrNull()
    val signalInfo = transportInfo ?: fallbackInfo
    _connection.update {
      it.copy(
        enabled = wifiEnabled,
        connected = true,
        ssid = ssid,
        bssid = bssid,
        rssiDbm = signalInfo?.rssi,
        frequencyMhz = signalInfo?.frequency?.takeIf { freq -> freq > 0 },
        linkSpeedMbps = signalInfo?.linkSpeed?.takeIf { speed -> speed >= 0 },
        txLinkSpeedMbps =
          runCatching {
            transportInfo?.txLinkSpeedMbps
          }.getOrNull()?.takeIf { speed -> speed >= 0 },
        rxLinkSpeedMbps =
          runCatching {
            transportInfo?.rxLinkSpeedMbps
          }.getOrNull()?.takeIf { speed -> speed >= 0 },
        ipv4 = v4,
        ipv6 = v6,
        gateway = gateway,
        dnsServers = dns,
        internet = internet,
        metered = metered,
        vpnActive = isVpnActive(),
        permissionRequired = !hasScanPermission(),
        locationEnabled = isLocationEnabled(),
        lastUpdatedAtMillis = now,
        message = null,
      )
    }
  }

  /**
   * Returns the observed Wi-Fi network plus its capabilities, preferring the
   * active network but falling back to any Wi-Fi transport in allNetworks.
   * The default-network callback alone misses local-only Wi-Fi and hides the
   * underlying Wi-Fi while a VPN carries the default route, so both
   * registrations feed this lookup.
   */
  private fun findWifiNetwork(cm: ConnectivityManager): Pair<Network?, NetworkCapabilities?> {
    val active = cm.activeNetwork
    val activeCaps = active?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
    if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
      return active to activeCaps
    }
    runCatching {
      cm.allNetworks.forEach { network ->
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
          return network to caps
        }
      }
    }
    return null to null
  }

  private fun isWifiEnabled(): Boolean =
    runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)

  private fun isVpnActive(): Boolean {
    val cm = connectivityManager ?: return false
    return runCatching {
      cm.allNetworks.any { network ->
        cm.getNetworkCapabilities(network)
          ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
      }
    }.getOrDefault(false)
  }

  /**
   * Fine location is required for startScan and getScanResults on every API
   * level, including 33 and above: NEARBY_WIFI_DEVICES alone is never a
   * replacement for scan calls. The pure rule lives in
   * [wifiScanPermissionGranted] and is covered by unit tests.
   */
  private fun hasScanPermission(): Boolean {
    val fine =
      ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_FINE_LOCATION,
      ) == PackageManager.PERMISSION_GRANTED
    val nearby =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
          appContext,
          Manifest.permission.NEARBY_WIFI_DEVICES,
        ) == PackageManager.PERMISSION_GRANTED
      } else {
        false
      }
    return wifiScanPermissionGranted(fine, nearby)
  }

  private fun isLocationEnabled(): Boolean {
    val lm = locationManager ?: return true
    return runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        lm.isLocationEnabled
      } else {
        @Suppress("DEPRECATION")
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
          lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
      }
    }.getOrDefault(true)
  }

  companion object {
    private const val TAG = "AndroidWifiRepository"

    /**
     * Upper bound for one scan round trip. A missing or late
     * SCAN_RESULTS_AVAILABLE_ACTION broadcast must never leave the UI scanning
     * forever; the timeout only clears the spinner, it never invents results.
     */
    const val SCAN_TIMEOUT_MILLIS = 15_000L
  }
}
