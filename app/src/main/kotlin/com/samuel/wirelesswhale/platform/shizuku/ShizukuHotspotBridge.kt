package com.samuel.wirelesswhale.platform.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.util.Log
import com.samuel.wirelesswhale.bridge.IHotspotBridge
import com.samuel.wirelesswhale.bridge.IHotspotEventCallback
import com.samuel.wirelesswhale.core.model.EnhancedAccessState
import com.samuel.wirelesswhale.platform.hotspot.EnhancedHotspotBridge
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.BRIDGE_TAG
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.BRIDGE_VERSION
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.EXPECTED_SHELL_UID
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_PACKAGE
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_SHELL_IDENTITY
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_UID
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_VERSION
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeProtocol.BridgeResult
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeProtocol.BridgeSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * App-process client for the shell-identity hotspot UserService.
 *
 * This class references only the Shizuku client library and the generated
 * AIDL stubs: zero hidden APIs in the app process. Every Shizuku call is
 * guarded (binder presence, API level, permission) and every failure
 * surfaces as an [EnhancedAccessState] with a message instead of a crash.
 *
 * The bridge never installs, starts, or configures Shizuku itself; it only
 * reacts to binder/permission state and binds an explicitly authorized
 * UserService.
 */
class ShizukuHotspotBridge(
  context: Context,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EnhancedHotspotBridge {
  private val appContext: Context = context.applicationContext

  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val started = AtomicBoolean(false)

  private val _accessState = MutableStateFlow(EnhancedAccessState.NOT_INSTALLED)
  override val accessState: StateFlow<EnhancedAccessState> = _accessState.asStateFlow()

  private val _accessMessage = MutableStateFlow<String?>(null)
  override val accessMessage: StateFlow<String?> = _accessMessage.asStateFlow()

  private val _pushedSnapshot = MutableStateFlow<BridgeSnapshot?>(null)
  override val pushedSnapshot: StateFlow<BridgeSnapshot?> = _pushedSnapshot.asStateFlow()

  @Volatile private var service: IHotspotBridge? = null

  // Epoch guards late IPC after close(): every start/close bumps it, and
  // every suspend IPC captures it. A payload that returns after close (or
  // after a restart) is dropped, never applied. Superseded binds within
  // one epoch are guarded by service/binder identity, not by epoch alone.
  @Volatile private var epoch = 0

  // Binding vs readiness are separate: CONNECTING means permission is
  // granted and a bind is in flight; READY requires a UID 2000 + package +
  // version handshake. HELPER_UNAVAILABLE means granted but no usable
  // binder arrived (helper bootstrap failure, timeout, or death).
  @Volatile private var bindInFlight = false
  @Volatile private var lastBindStartedAtMillis = 0L
  private var bindTimeoutJob: Job? = null

  private val deathRecipient = DeathRecipient {
    service = null
    _pushedSnapshot.value = null
    bindInFlight = false
    if (!started.get()) return@DeathRecipient
    scope.launch {
      // The helper process died (e.g. OEM bootstrap NPE before our code
      // runs). Report it honestly instead of looping back to permission.
      _accessState.value = EnhancedAccessState.HELPER_UNAVAILABLE
      _accessMessage.value =
        "Access was granted but the hotspot helper stopped. Nothing was changed. Retry, or use system settings."
      updateAccess()
    }
  }

  private val eventCallback = object : IHotspotEventCallback.Stub() {
    override fun onSnapshotChanged(snapshot: Bundle?) {
      if (!started.get()) return
      // Drop late pushes after a demotion/close cleared the service; the
      // repository already treats a missing push as no fresh observation.
      if (service == null) return
      val parsed = BundleCodec.snapshotFromBundle(snapshot)
      _pushedSnapshot.value = parsed
    }
  }

  private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
      if (!started.get()) return
      if (binder == null || !binder.pingBinder()) {
        scope.launch {
          bindInFlight = false
          _accessState.value = EnhancedAccessState.HELPER_UNAVAILABLE
          _accessMessage.value =
            "Access was granted but the hotspot helper did not provide a binder. Nothing was changed. Retry, or use system settings."
          updateAccess()
        }
        return
      }
      scope.launch {
        if (!started.get()) return@launch
        val capturedEpoch = epoch
        try {
          binder.linkToDeath(deathRecipient, 0)
        } catch (e: Exception) {
          Log.w(TAG, "linkToDeath failed: ${e.javaClass.simpleName}")
        }
        val svc = IHotspotBridge.Stub.asInterface(binder)
        var handshakeFailure: BridgeIpcPolicy.IpcFailure? = null
        val handshake = try {
          withTimeoutOrNull(HANDSHAKE_TIMEOUT_MILLIS) {
            try {
              svc.handshake(null)
            } catch (e: kotlinx.coroutines.CancellationException) {
              throw e
            } catch (e: SecurityException) {
              Log.w(TAG, "handshake auth failed: ${e.javaClass.simpleName}")
              handshakeFailure = BridgeIpcPolicy.IpcFailure.AUTH
              null
            } catch (e: Exception) {
              Log.w(TAG, "handshake failed: ${e.javaClass.simpleName}")
              handshakeFailure = BridgeIpcPolicy.IpcFailure.TRANSPORT
              null
            }
          }?.also {
            // Non-null reply with no recorded failure stays success.
          } ?: run {
            if (handshakeFailure == null) {
              handshakeFailure = BridgeIpcPolicy.IpcFailure.TRANSPORT
            }
            null
          }
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        }
        // Late IPC after close/restart must never mutate state.
        if (!started.get() || capturedEpoch != epoch) {
          runCatching { binder.unlinkToDeath(deathRecipient, 0) }
          return@launch
        }
        if (handshake == null) {
          // Transport failure: the helper loader died before answering
          // (observed on Transsion/Infinix where Shizuku's
          // LoadedApk.makeApplication hits an OEM null getProcessName()).
          // This is not a permission problem: never show PERMISSION_REQUIRED.
          // A stale failure must not clobber a newer success.
          val cur = service
          if (cur != null && cur.asBinder() !== binder) {
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            return@launch
          }
          val failure = handshakeFailure ?: BridgeIpcPolicy.IpcFailure.TRANSPORT
          service = null
          _pushedSnapshot.value = null
          bindInFlight = false
          cancelBindTimeout()
          _accessState.value = BridgeIpcPolicy.accessStateFor(failure)
          _accessMessage.value = BridgeIpcPolicy.messageFor(failure)
          runCatching { binder.unlinkToDeath(deathRecipient, 0) }
          return@launch
        }
        if (!handshakeOk(handshake)) {
          val cur = service
          if (cur != null && cur.asBinder() !== binder) {
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            return@launch
          }
          service = null
          _pushedSnapshot.value = null
          bindInFlight = false
          cancelBindTimeout()
          _accessState.value = EnhancedAccessState.REVOKED
          _accessMessage.value =
            "The hotspot helper answered with an unexpected identity. " +
              "Expected shell UID $EXPECTED_SHELL_UID. Re-authorize Shizuku access to continue."
          runCatching { binder.unlinkToDeath(deathRecipient, 0) }
          return@launch
        }
        // Late callback after close must not revive: drop the binder instead.
        if (!started.get() || capturedEpoch != epoch) {
          runCatching { binder.unlinkToDeath(deathRecipient, 0) }
          return@launch
        }
        service = svc
        bindInFlight = false
        cancelBindTimeout()
        runCatching { svc.registerEventCallback(eventCallback) }
        _accessState.value = EnhancedAccessState.READY
        _accessMessage.value = null
        fetchInitialSnapshot(svc, capturedEpoch)
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      service = null
      _pushedSnapshot.value = null
      bindInFlight = false
      if (!started.get()) return
      scope.launch {
        _accessState.value = EnhancedAccessState.HELPER_UNAVAILABLE
        _accessMessage.value =
          "The hotspot helper disconnected. Nothing was changed. Retry, or use system settings."
        updateAccess()
      }
    }
  }

  private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
    if (!started.get()) return@OnBinderReceivedListener
    scope.launch { updateAccess(force = true) }
  }
  private val binderDeadListener = Shizuku.OnBinderDeadListener {
    service = null
    _pushedSnapshot.value = null
    bindInFlight = false
    cancelBindTimeout()
    if (!started.get()) return@OnBinderDeadListener
    scope.launch {
      _accessState.value = EnhancedAccessState.HELPER_UNAVAILABLE
      _accessMessage.value =
        "Shizuku stopped. The hotspot helper is unavailable until Shizuku runs again. Nothing was changed."
      updateAccess()
    }
  }
  private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
    if (!started.get()) return@OnRequestPermissionResultListener
    scope.launch {
      if (grantResult == PackageManager.PERMISSION_GRANTED) {
        // Granted is not READY: move to CONNECTING and wait for the
        // UID 2000 handshake. A helper that never answers stays
        // HELPER_UNAVAILABLE via the bind timeout, never PERMISSION_REQUIRED.
        // Single entry: updateAccess(force=true) binds when granted, so no
        // separate bindService() call that would start two binds.
        updateAccess(force = true)
      } else {
        cancelBindTimeout()
        bindInFlight = false
        _accessState.value = EnhancedAccessState.PERMISSION_REQUIRED
        _accessMessage.value = "Wireless Whale was not granted Shizuku access. Hotspot controls stay on system settings."
      }
    }
  }

  // ------------------------------------------------------------------
  // Lifecycle.
  // ------------------------------------------------------------------

  override fun start() {
    if (!started.compareAndSet(false, true)) return
    epoch++
    runCatching { Shizuku.addBinderReceivedListener(binderReceivedListener) }
    runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
    runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
    scope.launch { updateAccess() }
  }

  override fun refreshAccess() {
    scope.launch { updateAccess(force = true) }
  }

  /**
   * Best-effort authorization prompt. Shizuku shows its own grant UI; from
   * an app context this needs a foreground moment, so the repository calls
   * this from a user gesture. When already granted this only re-binds and
   * never re-prompts; the permission listener moves granted -> CONNECTING.
   */
  override fun requestEnhancedAccess() {
    scope.launch {
      if (!shizukuAlive()) {
        updateAccess(force = true)
        return@launch
      }
      if (selfPermissionGranted()) {
        // Already granted: a single forced update re-binds without
        // re-prompting; no separate bindService() that would double-bind.
        updateAccess(force = true)
        return@launch
      }
      _accessState.value = EnhancedAccessState.PERMISSION_REQUIRED
      _accessMessage.value = "Grant Wireless Whale access in the Shizuku prompt to enable enhanced hotspot state."
      runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }
  }

  override fun close() {
    if (!started.compareAndSet(true, false)) return
    epoch++
    cancelBindTimeout()
    bindInFlight = false
    // Snapshot synchronously; never block the caller (often Main) on Binder.
    val svcSnapshot = service
    val argsSnapshot = serviceArgs()
    service = null
    _pushedSnapshot.value = null
    runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
    runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
    runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
    // Teardown runs on dispatcher IO with its own lifetime so the
    // scope.cancel() below cannot cancel it. Unregister-before-unbind
    // so the shell side drops our callback first. No radio changes here.
    val cleanup = CoroutineScope(SupervisorJob() + dispatcher)
    cleanup.launch {
      try {
        svcSnapshot?.let { svc ->
          runCatching { svc.unregisterEventCallback(eventCallback) }
          runCatching { svc.asBinder().unlinkToDeath(deathRecipient, 0) }
        }
        runCatching { Shizuku.unbindUserService(argsSnapshot, serviceConnection, false) }
      } finally {
        cleanup.cancel()
      }
    }
    scope.cancel()
  }

  // ------------------------------------------------------------------
  // Typed calls. Null means transport/auth failure after the bridge has
  // already demoted itself (caller maps to a message). A fresh read never
  // falls back to the last pushed snapshot: null service, failed IPC, or
  // a decode failure returns null so callers report unavailability instead
  // of confirming against stale state. The pushed flow stays separate.
  // All blocking Binder IPC runs on [dispatcher] (IO), never the caller
  // thread, so a Main caller stays free. Cancellation is never
  // swallowed: CancellationException is rethrown, never mapped to null.
  // withTimeoutOrNull is cooperative: it cannot interrupt an
  // already-blocking Binder call, it only stops waiting for the answer;
  // the underlying call may still occupy its IO thread until Binder returns.
  // Transport timeout/failure demotes to HELPER_UNAVAILABLE, auth failure
  // (SecurityException) to REVOKED, clearing the cached service and the
  // stale pushed snapshot. Demotion is skipped after close() or when a
  // newer binder already replaced this call's service.
  // ------------------------------------------------------------------

  private suspend fun <T> ioBounded(
    svc: IHotspotBridge,
    timeoutMillis: Long,
    op: String,
    block: () -> T?,
  ): T? {
    val capturedEpoch = epoch
    var failure: BridgeIpcPolicy.IpcFailure? = null
    val result: T? = try {
      withContext(dispatcher) {
        withTimeoutOrNull(timeoutMillis) {
          try {
            block()
          } catch (e: CancellationException) {
            throw e
          } catch (e: SecurityException) {
            Log.w(TAG, "$op auth failed: ${e.javaClass.simpleName}")
            failure = BridgeIpcPolicy.IpcFailure.AUTH
            null
          } catch (e: Exception) {
            Log.w(TAG, "$op IPC failed: ${e.javaClass.simpleName}")
            failure = BridgeIpcPolicy.IpcFailure.TRANSPORT
            null
          }
        } ?: run {
          if (failure == null) failure = BridgeIpcPolicy.IpcFailure.TRANSPORT
          null
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: SecurityException) {
      failure = BridgeIpcPolicy.IpcFailure.AUTH
      null
    } catch (e: Exception) {
      Log.w(TAG, "$op IPC failed: ${e.javaClass.simpleName}")
      if (failure == null) failure = BridgeIpcPolicy.IpcFailure.TRANSPORT
      null
    }
    if (result == null) {
      val f = failure ?: BridgeIpcPolicy.classify(null)
      if (!BridgeIpcPolicy.shouldApplyResult(started.get(), capturedEpoch, epoch, service === svc)) {
        return null
      }
      demoteOnIpcFailure(f)
      return null
    }
    return result
  }

  private fun demoteOnIpcFailure(failure: BridgeIpcPolicy.IpcFailure) {
    service = null
    _pushedSnapshot.value = null
    bindInFlight = false
    cancelBindTimeout()
    _accessState.value = BridgeIpcPolicy.accessStateFor(failure)
    _accessMessage.value = BridgeIpcPolicy.messageFor(failure)
  }

  override suspend fun fetchSnapshot(): BridgeSnapshot? {
    val svc = service ?: return null
    val bundle = ioBounded(svc, IPC_TIMEOUT_MILLIS, "snapshot") {
      svc.snapshot
    } ?: return null
    return try {
      BundleCodec.snapshotFromBundle(bundle)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "snapshot decode failed: ${e.javaClass.simpleName}")
      null
    }
  }

  override suspend fun startTetheredHotspot(): BridgeResult? {
    val svc = service ?: return null
    val bundle = ioBounded(svc, IPC_TIMEOUT_MILLIS, "start") {
      svc.startTetheredHotspot(null)
    } ?: return null
    return BundleCodec.resultFromBundle("start", bundle)
  }

  override suspend fun stopTethering(): BridgeResult? {
    val svc = service ?: return null
    val bundle = ioBounded(svc, IPC_TIMEOUT_MILLIS, "stop") {
      svc.stopTethering()
    } ?: return null
    return BundleCodec.resultFromBundle("stop", bundle)
  }

  // ------------------------------------------------------------------
  // Internals.
  // ------------------------------------------------------------------

  private fun serviceArgs(): Shizuku.UserServiceArgs =
    Shizuku.UserServiceArgs(
      ComponentName(appContext.packageName, HotspotUserService::class.java.name),
    ).daemon(false)
      .processNameSuffix(HotspotBridgeContract.PROCESS_SUFFIX)
      .version(BRIDGE_VERSION)
      .tag(BRIDGE_TAG)

  private fun shizukuAlive(): Boolean =
    runCatching {
      if (Shizuku.isPreV11()) return@runCatching false
      Shizuku.pingBinder()
    }.getOrDefault(false)

  private fun selfPermissionGranted(): Boolean =
    runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
      .getOrDefault(false)

  private fun shizukuInstalled(): Boolean {
    return try {
      @Suppress("DEPRECATION")
      appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
      true
    } catch (e: PackageManager.NameNotFoundException) {
      false
    } catch (e: Exception) {
      false
    }
  }

  private suspend fun updateAccess(force: Boolean = false) {
    if (!started.get()) return
    if (!shizukuAlive()) {
      service = null
      bindInFlight = false
      cancelBindTimeout()
      _accessState.value = if (shizukuInstalled()) {
        EnhancedAccessState.NOT_RUNNING
      } else {
        EnhancedAccessState.NOT_INSTALLED
      }
      _accessMessage.value =
        "Shizuku is not running. Start it with your approved method, then retry. Wireless Whale never starts it for you."
      return
    }
    if (!selfPermissionGranted()) {
      service = null
      bindInFlight = false
      cancelBindTimeout()
      _accessState.value = EnhancedAccessState.PERMISSION_REQUIRED
      _accessMessage.value = "Wireless Whale needs your Shizuku authorization for enhanced hotspot state."
      return
    }
    if (service != null && _accessState.value == EnhancedAccessState.READY) return
    bindService(force = force)
  }

  private fun bindService(force: Boolean = false) {
    if (!started.get()) return
    if (!shizukuAlive() || !selfPermissionGranted()) return
    if (service != null && _accessState.value == EnhancedAccessState.READY) return
    val now = System.currentTimeMillis()
    // Bounded retry: automatic re-binds (death, disconnect) respect a
    // minimum interval so a crashing helper cannot spin a tight bind loop.
    // Manual Retry (force=true) bypasses the interval but still never
    // re-prompts when already granted.
    if (!force && bindInFlight && now - lastBindStartedAtMillis < MIN_REBIND_INTERVAL_MILLIS) return
    if (!force && !bindInFlight && lastBindStartedAtMillis != 0L &&
      now - lastBindStartedAtMillis < MIN_REBIND_INTERVAL_MILLIS &&
      (_accessState.value == EnhancedAccessState.CONNECTING ||
        _accessState.value == EnhancedAccessState.HELPER_UNAVAILABLE)
    ) {
      return
    }
    bindInFlight = true
    lastBindStartedAtMillis = now
    if (_accessState.value != EnhancedAccessState.READY) {
      _accessState.value = EnhancedAccessState.CONNECTING
      _accessMessage.value =
        "Access was granted. Connecting to the hotspot helper; this clears when it answers."
    }
    val startedOk = runCatching { Shizuku.bindUserService(serviceArgs(), serviceConnection) }.isSuccess
    if (!startedOk) {
      bindInFlight = false
      cancelBindTimeout()
      _accessState.value = EnhancedAccessState.HELPER_UNAVAILABLE
      _accessMessage.value =
        "Access was granted but the helper bind could not start. Nothing was changed. Retry, or use system settings."
      return
    }
    // No-callback failure: the helper loader can die before any
    // onServiceConnected (e.g. OEM LoadedApk NPE), which yields no callback
    // at all. Bound the wait, then report HELPER_UNAVAILABLE explicitly.
    bindTimeoutJob?.cancel()
    bindTimeoutJob = scope.launch {
      delay(BIND_TIMEOUT_MILLIS)
      if (!started.get()) return@launch
      if (!bindInFlight) return@launch
      if (service != null && _accessState.value == EnhancedAccessState.READY) return@launch
      if (!selfPermissionGranted()) return@launch
      bindInFlight = false
      service = null
      _accessState.value = EnhancedAccessState.HELPER_UNAVAILABLE
      _accessMessage.value =
        "Access was granted but the hotspot helper did not start. Nothing was changed. " +
          "Retry, or use system settings."
    }
  }

  private fun cancelBindTimeout() {
    bindTimeoutJob?.cancel()
    bindTimeoutJob = null
  }

  private fun handshakeOk(bundle: Bundle?): Boolean {
    if (bundle == null) return false
    if (bundle.getInt(KEY_VERSION, -1) != BRIDGE_VERSION) return false
    if (bundle.getString(KEY_PACKAGE) != appContext.packageName) return false
    val uid = bundle.getInt(KEY_UID, -1)
    return uid == EXPECTED_SHELL_UID && bundle.getBoolean(KEY_SHELL_IDENTITY, false)
  }

  private suspend fun fetchInitialSnapshot(svc: IHotspotBridge, capturedEpoch: Int) {
    if (!started.get() || capturedEpoch != epoch) return
    if (service !== svc) return
    val bundle = ioBounded(svc, IPC_TIMEOUT_MILLIS, "initial-snapshot") {
      svc.snapshot
    } ?: return
    // Late payload after close/restart or a superseded bind must never
    // publish: close() nulls the service and bumps the epoch.
    if (!started.get() || capturedEpoch != epoch) return
    if (service !== svc) return
    try {
      _pushedSnapshot.value = BundleCodec.snapshotFromBundle(bundle)
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "initial snapshot decode failed: ${e.javaClass.simpleName}")
    }
  }

  companion object {
    private const val TAG = "WwShizukuBridge"
    private const val REQUEST_CODE = 0x7757
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val IPC_TIMEOUT_MILLIS = 15_000L
    private const val HANDSHAKE_TIMEOUT_MILLIS = 8_000L
    // No-callback bound: longer than the handshake wait so a slow helper
    // still answers first; short enough that a dead loader (OEM bootstrap
    // NPE yields zero callbacks) surfaces without a grant loop.
    private const val BIND_TIMEOUT_MILLIS = 12_000L
    private const val MIN_REBIND_INTERVAL_MILLIS = 2_000L
  }
}
