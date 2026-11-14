package com.samuel.wirelesswhale.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.samuel.wirelesswhale.core.model.DiscoveredDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Scoped local mDNS discovery. User-started only: [start] runs from an
 * explicit UI action and [stop] always tears everything down; nothing scans in
 * the background and there is no always-on networking.
 *
 * Honest visibility contract (surface in UI alongside the results, not just here):
 * - Discovers mDNS advertisers for a small fixed set of common service types
 *   ([SERVICE_TYPES]) on the current local network only.
 * - This is NOT a hotspot-client inventory, NOT an ARP/neighbor-table dump,
 *   and NOT a subnet port sweep. Silent devices, non-mDNS devices, and clients
 *   on another band/VLAN simply do not appear. Show [SCOPE_NOTE] with results.
 * - Entries with null host/port were seen but not resolved: partial
 *   visibility is reported as-is, never filled in or hidden.
 *
 * Session hardening:
 * - Every [start] begins a fresh session: previous results are cleared, the
 *   generation counter advances, and resolve state is dropped. Old callbacks
 *   carry the previous generation and are ignored, so callbacks arriving after
 *   [stop] can never insert stale entries.
 * - Storage is actually bounded: [byKey] never holds more than
 *   [DiagnosticsSupport.DISCOVERY_MAX_DEVICES] entries (oldest evicted first);
 *   the emitted list is that same bounded map, not a truncated view.
 * - Resolution is serialized with dedup through [DiagnosticsSupport.ResolveQueue]:
 *   one outstanding `resolveService` at a time, repeat finds for the same
 *   instance coalesced, and a service lost while queued is dropped so a late
 *   resolve can never resurrect it.
 * - Sessions are bounded in time: [DiagnosticsSupport.DISCOVERY_TIMEOUT_MS]
 *   after [start] the session auto-stops, releasing listeners and the
 *   multicast lock. Explicit [stop], total start failure, and last-listener
 *   failure all release the lock on the same path, so no lock can leak.
 *
 * Platform notes:
 * - Classic `discoverServices(String, Int, DiscoveryListener)` +
 *   `resolveService` + `stopServiceDiscovery` are used deliberately. They are
 *   stable public APIs from API 16 through 36, so behavior is identical on the
 *   API-30 primary device and the API-36 target with no version branch. The
 *   Executor/Network overloads (API 33+) and the combined
 *   `registerServiceInfoCallback(DiscoveryRequest, …)` discovery tracker
 *   (Android 14+) are intentionally not used: they would need version guards
 *   for zero benefit on this device.
 * - No socket is held by this class: all NSD sockets live in the framework
 *   service. [stop] releases every discovery listener plus the multicast lock,
 *   so repeated start/stop cycles cannot leak registrations.
 * - Discovery needs multicast reception; a short-lived multicast lock is held
 *   only while [running] is true (requires CHANGE_WIFI_MULTICAST_STATE, normal
 *   permission; parent adds it to the manifest). Lock acquisition failure
 *   degrades to a warning-free best effort, never a crash.
 *
 * Error reporting: [error] is an optional extra that never changes the shared
 * contract (`devices`, `running`, `start`, `stop`). When NSD is unavailable or
 * every service type fails to start, `running` stays false, devices stay
 * empty, and [error] names the cause. No fake success is ever emitted.
 */
class LanDiscoveryRunner(context: Context) {

  private val appContext = context.applicationContext
  private val nsd: NsdManager? =
    runCatching { appContext.getSystemService(NsdManager::class.java) }.getOrNull()
  private val wifi: WifiManager? =
    runCatching { appContext.getSystemService(WifiManager::class.java) }.getOrNull()

  private val lock = Any()
  private var generation = 0
  private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
  private val byKey = LinkedHashMap<String, DiscoveredDevice>()
  private val pendingInfo = mutableMapOf<String, NsdServiceInfo>()
  private val resolveQueue = DiagnosticsSupport.ResolveQueue()
  private var multicastLock: WifiManager.MulticastLock? = null
  private var timeoutHandler: Handler? = null
  private var timeoutToken: Runnable? = null

  private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
  val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

  private val _running = MutableStateFlow(false)
  val running: StateFlow<Boolean> = _running.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  companion object {
    /** Small fixed mDNS browse set. Local advertisers only, no sweeping. */
    val SERVICE_TYPES = listOf("_http._tcp.", "_https._tcp.", "_ipp._tcp.", "_printer._tcp.")

    /** UI must show this next to discovery results. */
    const val SCOPE_NOTE =
      "Shows nearby devices advertising common local services (mDNS). " +
        "Not a complete list of hotspot clients or network devices: silent or " +
        "non-advertising devices do not appear."
  }

  /** Idempotent. Starts a fresh session; clears previous results. */
  fun start() {
    val timeoutGen: Int
    synchronized(lock) {
      if (_running.value) return
      generation += 1
      timeoutGen = generation
      byKey.clear()
      pendingInfo.clear()
      resolveQueue.clear()
      _devices.value = emptyList()
      _error.value = null
      val manager = nsd
      if (manager == null) {
        _error.value = "Discovery is not available on this device."
        return
      }
      acquireMulticastLock()
      var anyStarted = false
      for (type in SERVICE_TYPES) {
        val listener = discoveryListener(type, timeoutGen)
        try {
          manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
          listeners.add(listener)
          anyStarted = true
        } catch (e: Exception) {
          runCatching { manager.stopServiceDiscovery(listener) }
        }
      }
      _running.value = anyStarted
      if (!anyStarted) {
        releaseMulticastLock()
        _error.value = "Discovery could not start. No services are listening yet."
        return
      }
      scheduleTimeoutLocked(timeoutGen)
    }
  }

  /** Idempotent. Stops every discovery, releases the multicast lock, keeps last results. */
  fun stop() {
    synchronized(lock) {
      if (!_running.value && listeners.isEmpty()) return
      stopLocked()
    }
  }

  private fun stopLocked() {
    generation += 1
    cancelTimeoutLocked()
    val manager = nsd
    for (listener in listeners) {
      runCatching { manager?.stopServiceDiscovery(listener) }
    }
    listeners.clear()
    pendingInfo.clear()
    resolveQueue.clear()
    releaseMulticastLock()
    _running.value = false
  }

  private fun onTimeout(gen: Int) {
    synchronized(lock) {
      if (DiagnosticsSupport.isStaleDiscoveryCallback(gen, generation)) return
      if (!_running.value) return
      stopLocked()
    }
  }

  private fun scheduleTimeoutLocked(gen: Int) {
    cancelTimeoutLocked()
    val handler = runCatching { Handler(Looper.getMainLooper()) }.getOrNull() ?: run {
      timeoutHandler = null
      return
    }
    timeoutHandler = handler
    val token = Runnable { onTimeout(gen) }
    timeoutToken = token
    runCatching { handler.postDelayed(token, DiagnosticsSupport.DISCOVERY_TIMEOUT_MS) }
  }

  private fun cancelTimeoutLocked() {
    val handler = timeoutHandler
    val token = timeoutToken
    if (handler != null && token != null) {
      runCatching { handler.removeCallbacks(token) }
    }
    timeoutToken = null
  }

  private fun discoveryListener(serviceType: String, gen: Int): NsdManager.DiscoveryListener = object : NsdManager.DiscoveryListener {
    override fun onDiscoveryStarted(regType: String) = Unit

    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
      synchronized(lock) {
        if (DiagnosticsSupport.isStaleDiscoveryCallback(gen, generation)) return
        listeners.remove(this)
        if (listeners.isEmpty()) {
          pendingInfo.clear()
          resolveQueue.clear()
          cancelTimeoutLocked()
          releaseMulticastLock()
          _running.value = false
          _error.value = "Discovery could not start (error $errorCode). No services are listening yet."
        }
      }
    }

    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

    override fun onDiscoveryStopped(serviceType: String) {
      synchronized(lock) {
        if (DiagnosticsSupport.isStaleDiscoveryCallback(gen, generation)) return
        listeners.remove(this)
        if (listeners.isEmpty() && _running.value) {
          pendingInfo.clear()
          resolveQueue.clear()
          cancelTimeoutLocked()
          releaseMulticastLock()
          _running.value = false
        }
      }
    }

    override fun onServiceFound(service: NsdServiceInfo) {
      val id = DiagnosticsSupport.discoveryKey(serviceType, service.serviceName)
      synchronized(lock) {
        if (DiagnosticsSupport.isStaleDiscoveryCallback(gen, generation)) return
        if (!_running.value) return
        if (!byKey.containsKey(id)) {
          DiagnosticsSupport.insertDiscoveryBounded(
            byKey,
            DiscoveredDevice(
              id = id,
              name = service.serviceName,
              host = null,
              port = null,
              serviceType = serviceType,
            ),
          )
          publishLocked()
        }
        pendingInfo[id] = service
        if (resolveQueue.offer(id)) pumpResolveLocked(gen)
      }
    }

    override fun onServiceLost(service: NsdServiceInfo) {
      synchronized(lock) {
        if (DiagnosticsSupport.isStaleDiscoveryCallback(gen, generation)) return
        val id = DiagnosticsSupport.discoveryKey(serviceType, service.serviceName)
        var changed = false
        if (byKey.remove(id) != null) changed = true
        if (resolveQueue.remove(id)) changed = true
        pendingInfo.remove(id)
        if (changed) publishLocked()
      }
    }
  }

  private fun pumpResolveLocked(gen: Int) {
    val manager = nsd ?: return
    var next = resolveQueue.takeNext()
    while (next != null) {
      val key = next
      val info = pendingInfo[key]
      if (info == null) {
        next = resolveQueue.finish(key)
        continue
      }
      try {
        manager.resolveService(info, resolveListener(gen, key))
        return
      } catch (e: Exception) {
        pendingInfo.remove(key)
        next = resolveQueue.finish(key)
      }
    }
  }

  private fun resolveListener(gen: Int, key: String): NsdManager.ResolveListener = object : NsdManager.ResolveListener {
    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
      synchronized(lock) {
        if (DiagnosticsSupport.isStaleDiscoveryCallback(gen, generation)) return
        pendingInfo.remove(key)
        val next = resolveQueue.finish(key)
        if (next != null) {
          val info = pendingInfo[next]
          if (info != null) {
            try {
              nsd?.resolveService(info, resolveListener(gen, next))
            } catch (e: Exception) {
              pendingInfo.remove(next)
              val followUp = resolveQueue.finish(next)
              if (followUp != null) {
                val followInfo = pendingInfo[followUp]
                if (followInfo != null) {
                  runCatching { nsd?.resolveService(followInfo, resolveListener(gen, followUp)) }
                }
              }
            }
          } else {
            resolveQueue.finish(next)
          }
        }
      }
    }

    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
      // Resolve the host while still on the callback thread; insertion stays under lock.
      val host = runCatching { serviceInfo.host?.hostAddress }.getOrNull()
      val resolved = DiscoveredDevice(
        id = key,
        name = serviceInfo.serviceName,
        host = host,
        port = serviceInfo.port.takeIf { it > 0 },
        serviceType = key.substringBefore('|'),
      )
      synchronized(lock) {
        if (DiagnosticsSupport.isStaleDiscoveryCallback(gen, generation)) {
          promiseNextLocked(gen, key)
          return
        }
        if (!_running.value) {
          pendingInfo.remove(key)
          promiseNextLocked(gen, key)
          return
        }
        // The service was lost while resolving: never resurrect it.
        if (!byKey.containsKey(key)) {
          pendingInfo.remove(key)
          promiseNextLocked(gen, key)
          return
        }
        DiagnosticsSupport.insertDiscoveryBounded(byKey, resolved)
        pendingInfo.remove(key)
        publishLocked()
        promiseNextLocked(gen, key)
      }
    }
  }

  /** Advances the serialized queue after one resolve settles. Callers hold [lock]. */
  private fun promiseNextLocked(gen: Int, finishedKey: String) {
    val next = resolveQueue.finish(finishedKey) ?: return
    val info = pendingInfo[next] ?: run {
      resolveQueue.finish(next)
      return
    }
    try {
      nsd?.resolveService(info, resolveListener(gen, next))
    } catch (e: Exception) {
      pendingInfo.remove(next)
      runCatching { resolveQueue.finish(next) }
    }
  }

  private fun publishLocked() {
    _devices.value = byKey.values.toList()
  }

  @SuppressLint("MissingPermission")
  private fun acquireMulticastLock() {
    releaseMulticastLock()
    multicastLock = runCatching {
      wifi?.createMulticastLock("wireless-whale-discovery")?.apply {
        setReferenceCounted(true)
        acquire()
      }
    }.getOrNull()
  }

  private fun releaseMulticastLock() {
    runCatching {
      multicastLock?.let { if (it.isHeld) it.release() }
    }
    multicastLock = null
  }
}
