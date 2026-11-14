package com.samuel.wirelesswhale.platform.shizuku

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.samuel.wirelesswhale.bridge.IHotspotBridge
import com.samuel.wirelesswhale.bridge.IHotspotEventCallback
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.BRIDGE_VERSION
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.DESTROY_TRANSACTION
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.EXPECTED_SHELL_UID
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_AP_STATE
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_CLIENT_BLOCKED
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_CLIENT_MAC
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_CODE
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_CONTROL_AVAILABLE
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_MESSAGE
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_OK
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_OP
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_PACKAGE
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_PROFILE_BAND
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_PROFILE_PASSWORD
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_PROFILE_SSID
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_SHELL_IDENTITY
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_SOFTAP_DIRECT
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_TETHER_EVENTS
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_UID
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.KEY_VERSION
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.STATUS_ERROR
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.STATUS_OFF
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.STATUS_ON
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.STATUS_STARTING
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.STATUS_STOPPING
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeProtocol.BridgeSnapshot
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shell-identity hotspot service, launched by Shizuku as UID 2000.
 *
 * Non-root. All hidden-platform access lives in this shell process; the
 * app process references only Shizuku + AIDL stubs and public SDK.
 *
 * Verified against android-11.0.0_r1:
 * - WifiManager line 3608: `public interface SoftApCallback`
 * - WifiManager line 3771: `registerSoftApCallback(Executor, SoftApCallback)`
 *   requires NETWORK_SETTINGS (held by shell, see device-audit).
 * The service reaches that method reflectively and implements the
 * callback with java.lang.reflect.Proxy (interface, so Proxy applies).
 * No manual ISoftApCallback Parcel dispatch is used.
 *
 * Tethering control was verified against a readonly UID 2000 framework
 * probe of the actual API 30 device (no control calls made): the request
 * type is the nested `android.net.TetheringManager$TetheringRequest`
 * (there is no top-level `android.net.TetheringRequest` on this build),
 * built via `TetheringRequest$Builder(int)` whose build() returns exactly
 * the nested request. startTethering has (int, Executor,
 * StartTetheringCallback) and (TetheringRequest, Executor,
 * StartTetheringCallback) overloads; stop has stopTethering(int) plus a
 * (TetheringRequest, Executor, StopTetheringCallback) overload that is
 * never used. Method/Builder selection lives in the pure
 * [TetheringControlResolver] (also JVM-tested) and the request/Builder
 * descriptors are derived from the chosen start method's parameters, so
 * a request object can never be passed to the int overload or vice
 * versa. canToggle stays false until this exact shape probes usable.
 *
 * Boundaries:
 * - Process.myUid() == 2000 is checked before any hidden-API init; the
 *   app-side handshake alone is never the security boundary.
 * - Every entry validates the caller (app UID or self) and privileged
 *   outbound Binder calls run with the incoming identity cleared, so
 *   they execute as shell, not as the calling app UID.
 * - Only API 30 is supported; other levels report unsupported and the
 *   repository stays in standard mode.
 */
class HotspotUserService : IHotspotBridge.Stub {

  private var serviceContext: Context? = null

  constructor(context: Context) : super() {
    serviceContext = context
    initReflection()
  }

  /** Fallback for pre-v13 Shizuku: reports not-ready on every call. */
  constructor() : super()

  override fun onTransact(
    code: Int,
    data: android.os.Parcel,
    reply: android.os.Parcel?,
    flags: Int,
  ): Boolean {
    if (code == DESTROY_TRANSACTION) {
      data.enforceInterface(DESCRIPTOR)
      // Same caller gate as destroy(): only owner or self may kill us.
      try {
        checkCaller()
      } catch (e: SecurityException) {
        return false
      }
      shutdown("server-destroy")
      System.exit(0)
      return true
    }
    return super.onTransact(code, data, reply, flags)
  }

  // ------------------------------------------------------------------
  // IPC entry points. Small typed Bundles only, never raw dumps.
  // ------------------------------------------------------------------

  private fun serviceContextOrThrow(): Context =
    serviceContext ?: throw IllegalStateException("no-context")

  private fun checkCaller() {
    val ctx = serviceContextOrThrow()
    val callingUid = Binder.getCallingUid()
    val appUid = ctx.applicationInfo.uid
    val selfUid = Process.myUid()
    if (!HotspotBridgeProtocol.isCallerAuthorized(callingUid, appUid, selfUid)) {
      throw SecurityException("unauthorized-caller")
    }
  }

  private inline fun <T> asShell(block: () -> T): T {
    val token = Binder.clearCallingIdentity()
    try {
      return block()
    } finally {
      Binder.restoreCallingIdentity(token)
    }
  }

  private fun supportedOrErr(op: String): Bundle? {
    if (!HotspotBridgeProtocol.isSupportedSdk(Build.VERSION.SDK_INT)) {
      return err(
        op,
        HotspotBridgeContract.CODE_UNSUPPORTED,
        "Enhanced hotspot is verified on API 30 only; this device reports API " +
          "${Build.VERSION.SDK_INT}. Use system hotspot settings.",
      )
    }
    if (Process.myUid() != EXPECTED_SHELL_UID) {
      return err(
        op,
        HotspotBridgeContract.CODE_NOT_READY,
        "Bridge is not running as shell UID $EXPECTED_SHELL_UID.",
      )
    }
    return null
  }

  override fun getBridgeVersion(): Int {
    checkCaller()
    return BRIDGE_VERSION
  }

  override fun handshake(request: Bundle?): Bundle {
    checkCaller()
    val out = Bundle()
    out.putInt(KEY_VERSION, BRIDGE_VERSION)
    val uid = Process.myUid()
    out.putInt(KEY_UID, uid)
    out.putBoolean(KEY_SHELL_IDENTITY, uid == EXPECTED_SHELL_UID)
    out.putString(KEY_PACKAGE, serviceContext?.packageName)
    out.putBoolean(KEY_SOFTAP_DIRECT, softApDirect.get())
    out.putBoolean(KEY_TETHER_EVENTS, tetherEvents.get())
    out.putInt(KEY_AP_STATE, lastApState)
    // Live control-shape probe (side-effect free); false until the exact
    // API 30 start/request/Builder/stop shape verifies.
    out.putBoolean(
      KEY_CONTROL_AVAILABLE,
      runCatching { asShell { isControlAvailable() } }.getOrDefault(false),
    )
    return out
  }

  override fun getSnapshot(): Bundle {
    checkCaller()
    return asShell { BundleCodec.snapshotToBundle(buildSnapshot(null)) }
  }

  override fun startTetheredHotspot(config: Bundle?): Bundle {
    checkCaller()
    val op = "start"
    supportedOrErr(op)?.let { return it }
    val ctx = try {
      serviceContextOrThrow()
    } catch (e: IllegalStateException) {
      return err(op, HotspotBridgeContract.CODE_NOT_READY, "Bridge has no Context.")
    }
    val ssid = config?.getString(KEY_PROFILE_SSID)
    if (ssid != null) {
      val problems = HotspotBridgeProtocol.validateProfile(
        ssid,
        config.getString(KEY_PROFILE_PASSWORD) ?: "",
        if (config.containsKey(KEY_PROFILE_BAND)) config.getInt(KEY_PROFILE_BAND) else null,
      )
      if (problems.isNotEmpty()) {
        return err(op, HotspotBridgeContract.CODE_INVALID, problems.joinToString(" "))
      }
      return err(
        op,
        HotspotBridgeContract.CODE_UNVERIFIED,
        "Profile changes are not applied until a vendor mechanism is proven live. " +
          "Start with the current system configuration instead.",
      )
    }
    val tm = asShell { tetheringManager(ctx) }
      ?: return err(op, HotspotBridgeContract.CODE_UNSUPPORTED, "TetheringManager is unavailable in this process.")
    synchronized(opLock) {
      if (pendingOp != null) {
        return err(op, HotspotBridgeContract.CODE_FAILED, "Another hotspot operation is already running.")
      }
      pendingOp = op
      pendingOpAt = System.currentTimeMillis()
    }
    pushSoon()
    return try {
      val startMethod = asShell { selectStartTetheringMethod(tm) }
        ?: return failOp(op, HotspotBridgeContract.CODE_UNSUPPORTED, "startTethering(TetheringRequest, Executor, StartTetheringCallback) is unavailable on this build.")
      // Derive the request from the chosen method's own parameter so the
      // object can never mismatch the overload (never the int overload
      // with a request object, never a top-level request class).
      val requestClass = startMethod.parameterTypes[0]
      val request = asShell { buildTetherRequest(tm, requestClass) }
        ?: return failOp(op, HotspotBridgeContract.CODE_UNSUPPORTED, "Could not build a Wi-Fi tethering request.")
      val callbackClass = startMethod.parameterTypes[2]
      val startCb = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(callbackClass),
        startCallbackHandler(),
      )
      asShell { startMethod.invoke(tm, request, DIRECT, startCb) }
      ok(op, "Tether start requested; confirming state.")
    } catch (e: Exception) {
      if (isDenial(e)) {
        failOp(op, HotspotBridgeContract.CODE_DENIED, "Tether start was denied (provisioning or permission).")
      } else {
        failOp(op, HotspotBridgeContract.CODE_FAILED, "Tether start failed: ${rootCause(e).javaClass.simpleName}.")
      }
    }
  }

  override fun stopTethering(): Bundle {
    checkCaller()
    val op = "stop"
    supportedOrErr(op)?.let { return it }
    val ctx = try {
      serviceContextOrThrow()
    } catch (e: IllegalStateException) {
      return err(op, HotspotBridgeContract.CODE_NOT_READY, "Bridge has no Context.")
    }
    val tm = asShell { tetheringManager(ctx) }
      ?: return err(op, HotspotBridgeContract.CODE_UNSUPPORTED, "TetheringManager is unavailable in this process.")
    synchronized(opLock) {
      if (pendingOp != null) {
        return err(op, HotspotBridgeContract.CODE_FAILED, "Another hotspot operation is already running.")
      }
      pendingOp = op
      pendingOpAt = System.currentTimeMillis()
    }
    pushSoon()
    return try {
      // Wi-Fi-only stop. Never fall back to stopAllTethering: that could
      // disconnect unrelated USB/Bluetooth consumers. The
      // (TetheringRequest, Executor, StopTetheringCallback) overload is
      // never picked either.
      val wifiType = tetherTypeWifi(tm)
      val stopMethod = selectWifiOnlyStopMethod(tm)
        ?: return failOp(
          op,
          HotspotBridgeContract.CODE_UNSUPPORTED,
          "Wi-Fi-only stop is unavailable on this build; no unrelated tethers were touched.",
        )
      asShell { stopMethod.invoke(tm, wifiType) }
      ok(op, "Tether stop requested; confirming state.")
    } catch (e: Exception) {
      if (isDenial(e)) {
        failOp(op, HotspotBridgeContract.CODE_DENIED, "Tether stop was denied.")
      } else {
        failOp(op, HotspotBridgeContract.CODE_FAILED, "Tether stop failed: ${rootCause(e).javaClass.simpleName}.")
      }
    }
  }

  override fun applyProfile(profile: Bundle?): Bundle {
    checkCaller()
    val op = "profile"
    val ssid = profile?.getString(KEY_PROFILE_SSID) ?: ""
    val problems = HotspotBridgeProtocol.validateProfile(
      ssid,
      profile?.getString(KEY_PROFILE_PASSWORD) ?: "",
      if (profile != null && profile.containsKey(KEY_PROFILE_BAND)) profile.getInt(KEY_PROFILE_BAND) else null,
    )
    if (problems.isNotEmpty()) {
      return err(op, HotspotBridgeContract.CODE_INVALID, problems.joinToString(" "))
    }
    return err(
      op,
      HotspotBridgeContract.CODE_UNVERIFIED,
      "Profile is valid, but no configuration path is proven live on this device yet. " +
        "Change it in system hotspot settings.",
    )
  }

  override fun setClientBlocked(request: Bundle?): Bundle {
    checkCaller()
    val op = "block"
    val mac = HotspotBridgeProtocol.normalizeMac(request?.getString(KEY_CLIENT_MAC))
      ?: return err(op, HotspotBridgeContract.CODE_INVALID, "A valid client MAC is required.")
    val blocked = request?.getBoolean(KEY_CLIENT_BLOCKED, true) ?: true
    return err(
      op,
      HotspotBridgeContract.CODE_UNVERIFIED,
      "Client ${if (blocked) "blocking" else "unblocking"} for $mac is not proven live on this device yet.",
    )
  }

  override fun registerEventCallback(callback: IHotspotEventCallback?) {
    checkCaller()
    if (callback == null) return
    val binder = callback.asBinder()
    val recipient = IBinder.DeathRecipient { removeEventCallback(callback) }
    try {
      binder.linkToDeath(recipient, 0)
    } catch (e: Exception) {
      Log.w(TAG, "callback linkToDeath failed")
      return
    }
    deathByCallback[callback.asBinder()] = recipient
    if (!eventCallbacks.contains(callback)) eventCallbacks.add(callback)
    pushSoon()
  }

  override fun unregisterEventCallback(callback: IHotspotEventCallback?) {
    checkCaller()
    if (callback != null) removeEventCallback(callback)
  }

  private fun removeEventCallback(callback: IHotspotEventCallback) {
    val binder = try {
      callback.asBinder()
    } catch (e: Exception) {
      return
    }
    deathByCallback.remove(binder)?.let { recipient ->
      runCatching { binder.unlinkToDeath(recipient, 0) }
    }
    eventCallbacks.remove(callback)
  }

  override fun destroy() {
    checkCaller()
    shutdown("client-destroy")
    System.exit(0)
  }

  // ------------------------------------------------------------------
  // Reflection core. Degrades to explicit unavailability.
  // ------------------------------------------------------------------

  private val worker = Executors.newSingleThreadExecutor()
  private val eventCallbacks = CopyOnWriteArrayList<IHotspotEventCallback>()
  private val deathByCallback = ConcurrentHashMap<IBinder, IBinder.DeathRecipient>()
  private val opLock = Any()
  private var pendingOp: String? = null
  private var pendingOpAt: Long = 0L

  private val softApDirect = AtomicBoolean(false)
  private val tetherEvents = AtomicBoolean(false)

  @Volatile private var lastApState: Int = -1
  @Volatile private var apStateObserved: Boolean = false
  @Volatile private var membershipObserved: Boolean = false
  @Volatile private var softApMacs: Set<String> = emptySet()
  @Volatile private var tetherAddrsByMac: Map<String, List<String>> = emptyMap()
  @Volatile private var tetherHostByMac: Map<String, String?> = emptyMap()
  @Volatile private var tetherSupported: Boolean? = null
  @Volatile private var upstreamPresent: Boolean? = null
  @Volatile private var tetheredIfaces: List<String> = emptyList()
  @Volatile private var lastStartError: String? = null
  @Volatile private var lastSnapshotAt: Long = 0L

  private var tetheringManager: Any? = null
  private var tetherEventCallback: Any? = null
  private var softApCallback: Any? = null
  private var reflectionReady = false
  private var shellContextCache: Context? = null

  private fun initReflection() {
    // Hard boundary: only the shell UID may touch hidden services, and
    // only the verified API level is attempted.
    if (Process.myUid() != EXPECTED_SHELL_UID) {
      Log.w(TAG, "not shell uid; enhanced bridge stays unavailable")
      return
    }
    if (!HotspotBridgeProtocol.isSupportedSdk(Build.VERSION.SDK_INT)) {
      Log.w(TAG, "unsupported SDK ${Build.VERSION.SDK_INT}; enhanced bridge stays unavailable")
      return
    }
    val ctx = serviceContext ?: return
    worker.execute {
      try {
        registerTetherEvents(ctx)
      } catch (e: Exception) {
        Log.w(TAG, "tether events unavailable: ${e.javaClass.simpleName}")
      }
      try {
        registerSoftApViaWifiManager(ctx)
      } catch (e: Exception) {
        Log.w(TAG, "softap direct unavailable: ${e.javaClass.simpleName}")
      }
      reflectionReady = true
      pushSoon()
    }
  }

  /** Reflective ServiceManager.getService; android.os.ServiceManager is not public SDK. */
  private fun systemServiceBinder(name: String): IBinder? {
    return try {
      val sm = Class.forName("android.os.ServiceManager")
      val m = sm.getMethod("getService", String::class.java)
      m.invoke(null, name) as? IBinder
    } catch (e: Exception) {
      Log.w(TAG, "ServiceManager.getService($name) failed: ${e.javaClass.simpleName}")
      null
    }
  }

  /**
   * createPackageContext changes resources, not necessarily AppOps attribution.
   * On this API 30 ROM its op-package remains "android". Attribute requests to
   * the real shell package only after validating that it owns this process UID.
   * Never fall back to an app/system package or change any permission/AppOp.
   */
  private fun shellContext(base: Context): Context {
    shellContextCache?.let { return it }
    val shellInfo = base.packageManager.getApplicationInfo(ShellAttributionPolicy.SHELL_PACKAGE, 0)
    check(ShellAttributionPolicy.isEligible(Process.myUid(), shellInfo.uid, shellInfo.packageName)) {
      "Shell package must belong to the current shell UID"
    }
    val packageContext = base.createPackageContext(
      ShellAttributionPolicy.SHELL_PACKAGE,
      Context.CONTEXT_IGNORE_SECURITY,
    )
    val shell = object : ContextWrapper(packageContext) {
      override fun getPackageName(): String = ShellAttributionPolicy.SHELL_PACKAGE
      override fun getOpPackageName(): String = ShellAttributionPolicy.SHELL_PACKAGE
      override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(shellInfo)
      override fun getAttributionTag(): String? = null
    }
    shellContextCache = shell
    return shell
  }

  private fun tetheringManager(ctx: Context): Any? {
    tetheringManager?.let { return it }
    val tmClass = try {
      Class.forName("android.net.TetheringManager")
    } catch (e: ClassNotFoundException) {
      return null
    }
    return try {
      val useCtx = shellContext(ctx)
      // Do not use ContextWrapper.getSystemService(): it delegates to the base
      // ContextImpl and can return a manager with cached "android" attribution.
      // This exact constructor was observed on the target's API 30 framework.
      val ctor = tmClass.getConstructor(Context::class.java, java.util.function.Supplier::class.java)
      // TetheringManager ctor takes a non-null Binder Supplier: resolve the
      // binder first so a null service yields explicit unavailability here,
      // never a null slipped into a non-null parameter.
      val tetherBinder = systemServiceBinder("tethering") ?: return null
      val supplier = java.util.function.Supplier<IBinder> { tetherBinder }
      val svc = ctor.newInstance(useCtx, supplier)
      tetheringManager = svc
      svc
    } catch (e: Exception) {
      Log.w(TAG, "TetheringManager ctor failed: ${e.javaClass.simpleName}")
      null
    }
  }

  /**
   * Selects startTethering(TetheringRequest, Executor,
   * StartTetheringCallback) by exact parameter descriptor, not by
   * overload position: the (int, Executor, StartTetheringCallback)
   * overload and any unrelated request class are rejected by
   * [TetheringControlResolver], so a request object is never passed to
   * the int overload. The nested request descriptor is
   * `android.net.TetheringManager$TetheringRequest`; the top-level
   * `android.net.TetheringRequest` does not exist on API 30 and is
   * rejected.
   */
  private fun selectStartTetheringMethod(tm: Any?): java.lang.reflect.Method? {
    if (tm == null) return null
    return tm.javaClass.methods.firstOrNull { m ->
      m.name == "startTethering" &&
        TetheringControlResolver.isRequestStartOverload(
          TetheringControlResolver.paramNames(m.parameterTypes),
        )
    }
  }

  /**
   * Selects stopTethering(int) only. The (TetheringRequest, Executor,
   * StopTetheringCallback) overload is rejected by arity, and
   * stopAllTethering never matches by name.
   */
  private fun selectWifiOnlyStopMethod(tm: Any?): java.lang.reflect.Method? {
    if (tm == null) return null
    return tm.javaClass.methods.firstOrNull { m ->
      m.name == "stopTethering" &&
        TetheringControlResolver.isWifiOnlyStopOverload(
          TetheringControlResolver.paramNames(m.parameterTypes),
        )
    }
  }

  /**
   * True only when the exact API 30 control shape probes usable: the
   * request start overload exists, its request class maps to a Builder
   * with the (int) constructor whose build() returns exactly that
   * request, and Wi-Fi-only stopTethering(int) exists. Best-effort and
   * side-effect free: builds no tether, stops nothing.
   */
  private fun isControlAvailable(): Boolean {
    val ctx = serviceContext ?: return false
    return try {
      val tm = tetheringManager(ctx) ?: return false
      val start = selectStartTetheringMethod(tm) ?: return false
      val requestClass = start.parameterTypes[0]
      val builderClassName =
        TetheringControlResolver.builderClassNameFor(requestClass.name) ?: return false
      val builderClass = try {
        Class.forName(builderClassName)
      } catch (e: ClassNotFoundException) {
        return false
      }
      val buildReturn = try {
        builderClass.getMethod("build").returnType.name
      } catch (e: NoSuchMethodException) {
        return false
      }
      val ctorOk = builderClass.constructors.any {
        TetheringControlResolver.isSupportedBuilderShape(
          TetheringControlResolver.paramNames(it.parameterTypes),
          buildReturn,
        )
      }
      if (!ctorOk) return false
      selectWifiOnlyStopMethod(tm) != null
    } catch (e: Exception) {
      Log.w(TAG, "control probe failed: ${e.javaClass.simpleName}")
      false
    }
  }

  private fun startCallbackHandler(): InvocationHandler = InvocationHandler { proxy, method, args ->
    worker.execute {
      when (method.name) {
        "onTetheringStarted" -> {
          lastStartError = null
          clearPendingOp("start")
          pushSoon()
        }
        "onTetheringFailed" -> {
          val code = (args?.firstOrNull() as? Number)?.toInt() ?: -1
          lastStartError = "start failed (${tetherErrorName(code, code.toString())})"
          clearPendingOp("start")
          pushSoon()
        }
      }
    }
    proxyObjectMethod(method, proxy, args)
  }

  private fun registerTetherEvents(ctx: Context) {
    val tm = tetheringManager(ctx) ?: return
    val eventClass = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
    val cb = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(eventClass), tetherEventHandler())
    val register = tm.javaClass.methods.firstOrNull {
      it.name == "registerTetheringEventCallback" && it.parameterTypes.size == 2
    } ?: return
    register.invoke(tm, DIRECT, cb)
    tetherEventCallback = cb
    tetherEvents.set(true)
  }

  private fun tetherEventHandler(): InvocationHandler = InvocationHandler { proxy, method, args ->
    worker.execute { handleTetherEvent(method.name, args) }
    proxyObjectMethod(method, proxy, args)
  }

  private fun handleTetherEvent(name: String, args: Array<out Any?>?) {
    when (name) {
      "onTetheringSupported" -> tetherSupported = (args?.firstOrNull() as? Boolean)
      "onUpstreamChanged" -> upstreamPresent = args?.firstOrNull() != null
      "onTetheredInterfacesChanged" -> {
        @Suppress("UNCHECKED_CAST")
        tetheredIfaces = ((args?.firstOrNull() as? Collection<*>)
          ?.mapNotNull { it as? String } ?: emptyList()).take(16)
      }
      "onClientsChanged" -> {
        @Suppress("UNCHECKED_CAST")
        val clients = (args?.firstOrNull() as? Collection<*>) ?: emptyList<Any>()
        val wifiOnly = clients.filter { isWifiTetheredClient(it) }
        val addrs = LinkedHashMap<String, MutableList<String>>()
        val hosts = LinkedHashMap<String, String?>()
        for (client in wifiOnly) {
          if (client == null) continue
          val mac = readClientMac(client) ?: continue
          for (ip in readWifiClientAddresses(client)) {
            addrs.getOrPut(mac) { mutableListOf() }.also { if (ip !in it) it += ip }
          }
          readClientHostname(client)?.let { hosts.putIfAbsent(mac, it) }
          addrs.putIfAbsent(mac, addrs[mac] ?: mutableListOf())
        }
        tetherAddrsByMac = addrs.mapValues { it.value.take(HotspotBridgeProtocol.MAX_ADDR_PER_CLIENT) }
        tetherHostByMac = hosts
      }
      "onError" -> {
        val iface = args?.getOrNull(0) as? String
        val code = (args?.getOrNull(1) as? Number)?.toInt()
        lastStartError = "tether error on ${iface ?: "?"} (${code ?: "?"})"
      }
      "onTetheringFailed", "onOffloadStatusChanged", "onTetherableInterfacesChanged",
      "onTetherableInterfaceRegexpsChanged", "onCallbackStarted", "onCallbackStopped",
      -> Unit
      "toString", "hashCode", "equals" -> Unit
    }
    pushSoon()
  }

  private fun tetherTypeWifi(tm: Any?): Int {
    if (tm == null) return 0
    return runCatching { tm.javaClass.getField("TETHERING_WIFI").getInt(null) }.getOrDefault(0)
  }

  private fun isWifiTetheredClient(client: Any?): Boolean {
    if (client == null) return false
    return try {
      val m = client.javaClass.methods.firstOrNull {
        it.name == "getTetheringType" && it.parameterTypes.isEmpty()
      } ?: return true // shape without type: keep, enrichment stays best-effort
      val type = m.invoke(client) as? Number ?: return true
      val wifi = runCatching {
        client.javaClass.getField("TETHERING_WIFI")
      }.getOrNull()?.getInt(null)
        ?: tetheringManager?.let { tetherTypeWifi(it) } ?: 0
      type.toInt() == wifi
    } catch (e: Exception) {
      true
    }
  }

  private fun readClientMac(client: Any): String? {
    return try {
      val m = client.javaClass.methods.firstOrNull { it.name == "getMacAddress" && it.parameterTypes.isEmpty() }
        ?: return null
      HotspotBridgeProtocol.normalizeMac(m.invoke(client)?.toString())
    } catch (e: Exception) {
      null
    }
  }

  /**
   * Wi-Fi-only enrichment: TetheredClient.getAddresses() yields
   * AddressInfo holders; each AddressInfo.getAddress() yields a
   * LinkAddress whose getAddress() is the InetAddress. Hostname comes
   * from AddressInfo.getHostname() or TetheredClient.getHostname().
   */
  private fun readWifiClientAddresses(client: Any): List<String> {
    val out = mutableListOf<String>()
    fun harvest(value: Any?) {
      when (value) {
        null -> Unit
        is Collection<*> -> value.forEach(::harvest)
        else -> {
          val text = linkAddressToIp(value) ?: value.toString()
          val clean = text.substringBefore("%").trim()
          if (HotspotBridgeProtocol.isPlausibleIp(clean)) out += clean
        }
      }
    }
    try {
      val cls = client.javaClass
      val addresses = runCatching {
        cls.methods.firstOrNull { it.name == "getAddresses" && it.parameterTypes.isEmpty() }
          ?.invoke(client) as? Collection<*>
      }.getOrNull()
      if (addresses != null) {
        for (info in addresses) {
          if (info == null) continue
          // AddressInfo.getAddress() -> LinkAddress.
          val link = runCatching {
            info.javaClass.methods.firstOrNull { it.name == "getAddress" && it.parameterTypes.isEmpty() }
              ?.invoke(info)
          }.getOrNull()
          harvest(link)
        }
        return out.distinct().take(HotspotBridgeProtocol.MAX_ADDR_PER_CLIENT)
      }
      // Older shapes: direct getAddress()/getAddressInfo().
      cls.methods.firstOrNull { it.name == "getAddressInfo" && it.parameterTypes.isEmpty() }
        ?.invoke(client)?.let { info ->
          info.javaClass.methods.firstOrNull { it.name == "getAddresses" && it.parameterTypes.isEmpty() }
            ?.let { harvest(runCatching { it.invoke(info) }.getOrNull()) }
          info.javaClass.methods.firstOrNull { it.name == "getAddress" && it.parameterTypes.isEmpty() }
            ?.let { harvest(runCatching { it.invoke(info) }.getOrNull()) }
        }
      cls.methods.firstOrNull { it.name == "getAddress" && it.parameterTypes.isEmpty() }
        ?.let { harvest(runCatching { it.invoke(client) }.getOrNull()) }
    } catch (e: Exception) {
      Log.w(TAG, "client addr read failed: ${e.javaClass.simpleName}")
    }
    return out.distinct().take(HotspotBridgeProtocol.MAX_ADDR_PER_CLIENT)
  }

  private fun linkAddressToIp(value: Any): String? {
    return try {
      if (value.javaClass.name == "android.net.LinkAddress") {
        val m = value.javaClass.getMethod("getAddress")
        (m.invoke(value) as? java.net.InetAddress)?.hostAddress
      } else {
        null
      }
    } catch (e: Exception) {
      null
    }
  }

  private fun readClientHostname(client: Any): String? {
    return try {
      val direct = client.javaClass.methods
        .firstOrNull { it.name == "getHostname" && it.parameterTypes.isEmpty() }
        ?.let { runCatching { it.invoke(client) as? String }.getOrNull() }
      if (!direct.isNullOrBlank()) return direct.take(253)
      // AddressInfo hostnames.
      val addresses = runCatching {
        client.javaClass.methods.firstOrNull { it.name == "getAddresses" && it.parameterTypes.isEmpty() }
          ?.invoke(client) as? Collection<*>
      }.getOrNull()
      addresses?.forEach { info ->
        if (info == null) return@forEach
        val name = runCatching {
          info.javaClass.methods.firstOrNull { it.name == "getHostname" && it.parameterTypes.isEmpty() }
            ?.invoke(info) as? String
        }.getOrNull()
        if (!name.isNullOrBlank()) return name.take(253)
      }
      null
    } catch (e: Exception) {
      null
    }
  }

  // ------------------------------------------------------------------
  // Soft AP path via WifiManager + Proxy SoftApCallback.
  // ------------------------------------------------------------------

  private fun registerSoftApViaWifiManager(ctx: Context) {
    val useCtx = shellContext(ctx)
    val wm = try {
      useCtx.getSystemService(WifiManager::class.java) ?: return
    } catch (e: Exception) {
      Log.w(TAG, "WifiManager unavailable: ${e.javaClass.simpleName}")
      return
    }
    val cbClass = try {
      Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
    } catch (e: ClassNotFoundException) {
      Log.w(TAG, "SoftApCallback class missing on this build")
      return
    }
    val handler = InvocationHandler { proxy, method, args ->
      worker.execute { handleSoftApCallback(method.name, args) }
      proxyObjectMethod(method, proxy, args)
    }
    val cbProxy = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(cbClass), handler)
    // Verified android-11.0.0_r1: registerSoftApCallback(Executor, SoftApCallback).
    val register = wm.javaClass.methods.firstOrNull { m ->
      m.name == "registerSoftApCallback" &&
        m.parameterTypes.size == 2 &&
        java.util.concurrent.Executor::class.java.isAssignableFrom(m.parameterTypes[0]) &&
        m.parameterTypes[1].isAssignableFrom(cbClass)
    } ?: run {
      Log.w(TAG, "registerSoftApCallback(Executor, SoftApCallback) missing")
      return
    }
    register.invoke(wm, DIRECT, cbProxy)
    softApCallback = cbProxy
    // softApDirect flips true only when a real dispatch arrives (probe).
  }

  private fun handleSoftApCallback(name: String, args: Array<out Any?>?) {
    when (name) {
      "onStateChanged" -> {
        val state = (args?.getOrNull(0) as? Number)?.toInt() ?: return
        lastApState = state
        apStateObserved = true
        softApDirect.set(true)
        clearStalePendingOp()
        pushSoon()
      }
      "onConnectedClientsChanged" -> {
        val macs = harvestSoftApMacs(args?.getOrNull(0))
        softApMacs = macs
        membershipObserved = true
        softApDirect.set(true)
        pushSoon()
      }
      "onInfoChanged", "onCapabilityChanged", "onBlockedClientConnecting" -> Unit
      "toString", "hashCode", "equals" -> Unit
    }
  }

  private fun harvestSoftApMacs(payload: Any?): Set<String> {
    val macs = LinkedHashSet<String>()
    fun addMac(value: Any?) {
      if (value == null) return
      try {
        val m = value.javaClass.methods
          .firstOrNull { it.name == "getMacAddress" && it.parameterTypes.isEmpty() } ?: return
        HotspotBridgeProtocol.normalizeMac(m.invoke(value)?.toString())?.let { macs += it }
      } catch (e: Exception) {
        Log.w(TAG, "wifi client read failed: ${e.javaClass.simpleName}")
      }
    }
    when (payload) {
      null -> Unit
      is Map<*, *> -> {
        // Keys may already be MAC strings; values carry SoftApInfo.
        for ((k, v) in payload) {
          HotspotBridgeProtocol.normalizeMac(k?.toString())?.let { macs += it }
          addMac(v)
        }
      }
      is Collection<*> -> payload.forEach(::addMac)
      else -> addMac(payload)
    }
    return macs
  }

  // ------------------------------------------------------------------
  // Snapshot assembly.
  // ------------------------------------------------------------------

  private fun buildSnapshot(observedStatus: String?): BridgeSnapshot {
    val ctx = serviceContext
    var status = observedStatus
    var message = lastStartError
    var source = HotspotBridgeContract.SOURCE_UNKNOWN

    if (status == null) {
      // Stop has no completion callback, so a held pendingOp would mask an
      // already-OFF radio as STOPPING until timeout. Re-read first: a
      // confirmed OFF clears a pending stop at once, and a confirmed ON
      // clears a pending start at once. Only while the radio still reports
      // transitioning do we show STARTING or STOPPING.
      val observed = statusFromObservation(ctx)
      if (pendingOp == "stop" && observed == STATUS_OFF) {
        clearPendingOp("stop")
        status = STATUS_OFF
      } else if (pendingOp == "start" && observed == STATUS_ON) {
        clearPendingOp("start")
        status = STATUS_ON
      } else {
        status = when {
          pendingOp == "start" && !opTimedOut() -> STATUS_STARTING
          pendingOp == "stop" && !opTimedOut() -> STATUS_STOPPING
          else -> observed
        }
        if (pendingOp != null && opTimedOut()) {
          message = ((message ?: "") + " Operation timed out; state re-read from the system.").trim()
          clearPendingOp(null)
          if (status == STATUS_STARTING || status == STATUS_STOPPING) {
            status = observed
          }
        }
      }
    }

    // Membership is authoritative only after onConnectedClientsChanged.
    // A state callback alone never yields an empty roster while ON.
    val clients = HotspotBridgeProtocol.resolveAuthoritativeClients(
      status ?: HotspotBridgeContract.STATUS_UNKNOWN,
      membershipObserved,
      softApMacs,
      tetherAddrsByMac,
      tetherHostByMac,
      System.currentTimeMillis(),
    )
    if (membershipObserved && status != HotspotBridgeContract.STATUS_UNKNOWN) {
      source = HotspotBridgeContract.SOURCE_SOFTAP
    } else if (status == STATUS_OFF) {
      // Confirmed OFF is an authoritative zero even before any callback.
      source = if (softApDirect.get()) {
        HotspotBridgeContract.SOURCE_SOFTAP
      } else {
        HotspotBridgeContract.SOURCE_UNKNOWN
      }
    } else {
      source = HotspotBridgeContract.SOURCE_UNKNOWN
      if (message.isNullOrEmpty() && status == STATUS_ON) {
        message = "Hotspot is on; membership is not yet observed."
      }
    }

    val (ssid, security) = asShell { readSoftApIdentity(ctx) }
    // Control shape is probed live: the request control is only offered
    // when the exact start/request/Builder/stop shape verified above.
    val controlAvailable = runCatching { asShell { isControlAvailable() } }.getOrDefault(false)
    lastSnapshotAt = System.currentTimeMillis()
    return BridgeSnapshot(
      status = status ?: HotspotBridgeContract.STATUS_UNKNOWN,
      ssid = ssid,
      security = security,
      tetherSupported = tetherSupported,
      upstream = upstreamPresent,
      tetheredIfaces = tetheredIfaces,
      clients = clients,
      message = message?.takeIf { it.isNotBlank() }?.take(280),
      source = source,
      atMillis = lastSnapshotAt,
      controlAvailable = controlAvailable,
    )
  }

  private fun statusFromObservation(ctx: Context?): String {
    if (lastApState >= 0) {
      return apStatusFromState(lastApState)
    }
    if (tetheredIfaces.any { it.startsWith("softap") || it.startsWith("ap") }) return STATUS_ON
    if (ctx != null) {
      return try {
        val wm = shellContext(ctx).getSystemService(WifiManager::class.java)
          ?: return HotspotBridgeContract.STATUS_UNKNOWN
        val m = wm.javaClass.methods.firstOrNull {
          it.name == "isWifiApEnabled" && it.parameterTypes.isEmpty()
        } ?: return HotspotBridgeContract.STATUS_UNKNOWN
        // Reflective read in the shell process only. A real Boolean false
        // is a confirmed OFF; a non-Boolean answer is unknown, never OFF.
        when (runCatching { m.invoke(wm) as? Boolean }.getOrNull()) {
          true -> STATUS_ON
          false -> STATUS_OFF
          null -> HotspotBridgeContract.STATUS_UNKNOWN
        }
      } catch (e: SecurityException) {
        HotspotBridgeContract.STATUS_UNKNOWN
      } catch (e: Exception) {
        HotspotBridgeContract.STATUS_UNKNOWN
      }
    }
    return HotspotBridgeContract.STATUS_UNKNOWN
  }

  /**
   * Maps a Soft AP state int without touching the hidden
   * WifiManager.WIFI_AP_STATE_* constants (not public SDK). Values are read
   * reflectively first, with AOSP android-11.0.0_r1 numbers as fallback
   * (DISABLING=10, DISABLED=11, ENABLING=12, ENABLED=13, FAILED=14, all
   * @hide/@SystemApi). Only confirmed DISABLED maps to OFF; any unmapped
   * raw value is UNKNOWN, never a false OFF.
   */
  private fun apStatusFromState(state: Int): String = when (state) {
    apStateInt("WIFI_AP_STATE_ENABLED", 13) -> STATUS_ON
    apStateInt("WIFI_AP_STATE_ENABLING", 12) -> STATUS_STARTING
    apStateInt("WIFI_AP_STATE_DISABLING", 10) -> STATUS_STOPPING
    apStateInt("WIFI_AP_STATE_FAILED", 14) -> STATUS_ERROR
    apStateInt("WIFI_AP_STATE_DISABLED", 11) -> STATUS_OFF
    else -> HotspotBridgeContract.STATUS_UNKNOWN
  }

  private fun apStateInt(field: String, fallback: Int): Int =
    runCatching { WifiManager::class.java.getField(field).getInt(null) }.getOrDefault(fallback)

  /**
   * SSID + security label only; the passphrase is never read into a
   * snapshot. Reflective; unknown shapes yield nulls.
   */
  private fun readSoftApIdentity(ctx: Context?): Pair<String?, String?> {
    if (ctx == null) return null to null
    return try {
      val wm = shellContext(ctx).getSystemService(WifiManager::class.java) ?: return null to null
      val getConfig = wm.javaClass.methods
        .firstOrNull { it.name == "getSoftApConfiguration" && it.parameterTypes.isEmpty() }
        ?: return null to null
      val config = getConfig.invoke(wm) ?: return null to null
      val ssid = runCatching {
        config.javaClass.getMethod("getSsid").invoke(config) as? String
      }.getOrNull()?.takeIf { it.isNotEmpty() }
      val security = runCatching {
        val type = config.javaClass.getMethod("getSecurityType").invoke(config) as? Int
        mapSecurityType(config.javaClass, type)
      }.getOrNull()
      ssid to security
    } catch (e: Exception) {
      null to null
    }
  }

  private fun mapSecurityType(configClass: Class<*>, type: Int?): String? {
    if (type == null) return null
    for (field in configClass.fields) {
      if (!field.name.startsWith("SECURITY_TYPE_")) continue
      if (field.type != Int::class.javaPrimitiveType) continue
      if (runCatching { field.getInt(null) }.getOrNull() == type) {
        return field.name.removePrefix("SECURITY_TYPE_")
      }
    }
    return null
  }

  /**
   * Builds a Wi-Fi tethering request from the exact request class of the
   * chosen start method: the Builder descriptor is derived from that
   * class (never a hardcoded top-level path), and the (int) constructor
   * plus exact build() return type are validated through
   * [TetheringControlResolver] before anything is instantiated.
   */
  private fun buildTetherRequest(tm: Any, requestClass: Class<*>): Any? {
    return try {
      val builderClassName =
        TetheringControlResolver.builderClassNameFor(requestClass.name) ?: return null
      val builderClass = Class.forName(builderClassName)
      val buildReturn = try {
        builderClass.getMethod("build").returnType.name
      } catch (e: NoSuchMethodException) {
        return null
      }
      // Builder(int) on API 30; the constructor shape and the exact
      // build() return type are validated together.
      val ctor = builderClass.constructors.firstOrNull {
        TetheringControlResolver.isSupportedBuilderShape(
          TetheringControlResolver.paramNames(it.parameterTypes),
          buildReturn,
        )
      } ?: return null
      val tetherInt = tetherTypeWifi(tm)
      val builder = ctor.newInstance(tetherInt)
      builderClass.getMethod("build").invoke(builder)
    } catch (e: Exception) {
      Log.w(TAG, "tether request build failed: ${e.javaClass.simpleName}")
      null
    }
  }

  private fun tetherErrorName(code: Int, fallback: String): String {
    return try {
      tetheringManager?.javaClass?.fields
        ?.firstOrNull { it.name.startsWith("TETHER_ERROR_") && runCatching { it.getInt(null) }.getOrNull() == code }
        ?.name ?: fallback
    } catch (e: Exception) {
      fallback
    }
  }

  // ------------------------------------------------------------------
  // Housekeeping.
  // ------------------------------------------------------------------

  private fun opTimedOut(): Boolean =
    pendingOp != null && System.currentTimeMillis() - pendingOpAt > OP_TIMEOUT_MILLIS

  private fun clearStalePendingOp() {
    synchronized(opLock) {
      if (opTimedOut()) pendingOp = null
    }
  }

  private fun clearPendingOp(op: String?) {
    synchronized(opLock) {
      if (op == null || pendingOp == op) pendingOp = null
    }
  }

  private fun failOp(op: String, code: String, message: String): Bundle {
    clearPendingOp(op)
    pushSoon()
    return err(op, code, message)
  }

  /** Reflective calls wrap service denials; unwrap before classifying. */
  private fun rootCause(e: Exception): Throwable {
    var root: Throwable = e
    while (root is InvocationTargetException && root.targetException != null) {
      root = root.targetException!!
    }
    return root
  }

  private fun isDenial(e: Exception): Boolean = rootCause(e) is SecurityException

  private fun ok(op: String, message: String): Bundle {
    val out = Bundle()
    out.putBoolean(KEY_OK, true)
    out.putString(KEY_CODE, HotspotBridgeContract.CODE_OK)
    out.putString(KEY_OP, op)
    out.putString(KEY_MESSAGE, message)
    return out
  }

  private fun err(op: String, code: String, message: String): Bundle {
    val out = Bundle()
    out.putBoolean(KEY_OK, false)
    out.putString(KEY_CODE, code)
    out.putString(KEY_OP, op)
    out.putString(KEY_MESSAGE, message)
    return out
  }

  private fun pushSoon() {
    worker.execute {
      if (eventCallbacks.isEmpty()) return@execute
      val snap = try {
        asShell { buildSnapshot(null) }
      } catch (e: Exception) {
        Log.w(TAG, "snapshot push failed: ${e.javaClass.simpleName}")
        return@execute
      }
      val bundle = BundleCodec.snapshotToBundle(snap)
      val dead = mutableListOf<IHotspotEventCallback>()
      for (cb in eventCallbacks) {
        try {
          cb.onSnapshotChanged(bundle)
        } catch (e: Exception) {
          dead += cb
        }
      }
      for (cb in dead) removeEventCallback(cb)
    }
  }

  private fun shutdown(reason: String) {
    Log.i(TAG, "shutdown: $reason")
    val tm = tetheringManager
    val tetherCb = tetherEventCallback
    if (tm != null && tetherCb != null) {
      runCatching {
        asShell {
          tm.javaClass.methods.firstOrNull {
            it.name == "unregisterTetheringEventCallback" && it.parameterTypes.size == 1
          }?.invoke(tm, tetherCb)
        }
      }
    }
    val ctx = serviceContext
    val apCb = softApCallback
    if (ctx != null && apCb != null) {
      runCatching {
        asShell {
          val wm = shellContext(ctx).getSystemService(WifiManager::class.java) ?: return@asShell
          val unregister = wm.javaClass.methods.firstOrNull { m ->
            m.name == "unregisterSoftApCallback" && m.parameterTypes.size == 1
          } ?: return@asShell
          unregister.invoke(wm, apCb)
        }
      }
    }
    softApCallback = null
    tetherEventCallback = null
    eventCallbacks.clear()
    deathByCallback.clear()
    worker.shutdownNow()
  }

  companion object {
    private const val TAG = "WwHotspotService"
    private const val OP_TIMEOUT_MILLIS = 20_000L

    /** Callbacks run on binder threads here; work is posted to [worker]. */
    @JvmStatic
    val DIRECT: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() }

    /** Safe defaults for Object methods routed through dynamic proxies. */
    @JvmStatic
    fun proxyObjectMethod(method: java.lang.reflect.Method, proxy: Any?, args: Array<out Any?>?): Any? =
      when (method.name) {
        "toString" -> "WwProxy(${method.declaringClass.simpleName})"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> (args?.getOrNull(0) === proxy)
        else -> when (method.returnType) {
          Boolean::class.javaPrimitiveType -> false
          Byte::class.javaPrimitiveType -> 0.toByte()
          Short::class.javaPrimitiveType -> 0.toShort()
          Char::class.javaPrimitiveType -> 0.toChar()
          Int::class.javaPrimitiveType -> 0
          Long::class.javaPrimitiveType -> 0L
          Float::class.javaPrimitiveType -> 0f
          Double::class.javaPrimitiveType -> 0.0
          Void.TYPE -> null
          else -> null
        }
      }
  }
}
