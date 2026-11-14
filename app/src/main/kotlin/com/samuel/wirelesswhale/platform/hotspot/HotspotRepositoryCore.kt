package com.samuel.wirelesswhale.platform.hotspot

import com.samuel.wirelesswhale.core.model.EnhancedAccessState
import com.samuel.wirelesswhale.core.model.HotspotClient
import com.samuel.wirelesswhale.core.model.HotspotProfile
import com.samuel.wirelesswhale.core.model.HotspotSnapshot
import com.samuel.wirelesswhale.core.model.HotspotStatus
import com.samuel.wirelesswhale.core.model.OperationResult
import com.samuel.wirelesswhale.core.platform.HotspotRepository
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.CODE_NEEDS_SYSTEM_UI
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.SOURCE_SOFTAP
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.SOURCE_SYSTEM
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.SOURCE_TETHERING
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.STATUS_ERROR
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.STATUS_OFF
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.STATUS_ON
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeProtocol
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeProtocol.BridgeSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hotspot policy core with two honest modes.
 *
 * Standard mode: read-only observation plus NeedsSystemUi handoffs.
 * clients is null while unknown, emptyList only for an authoritatively
 * observed zero.
 *
 * Enhanced mode: state and membership flow from the typed shell bridge.
 * canToggle is *eligibility to request* (READY + proven service
 * availability + a real ON/OFF observation), never an optimistic On and
 * never gated on a past round-trip. A completed start/stop round-trip is
 * reported as the OperationResult (confirmation), not as a sticky bit.
 * canConfigure and canBlockClients stay false until a vendor mechanism
 * is proven live. Usage and hard-quota fields are always false: no
 * per-client accounting source exists on this path.
 */
internal class HotspotRepositoryCore(
  private val enhanced: EnhancedHotspotBridge?,
  private val apReader: ApStateReader,
  dispatcher: CoroutineDispatcher,
) : HotspotRepository {

  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val started = AtomicBoolean(false)

  /** Serializes start/stop so confirmations cannot interleave. */
  private val opMutex = Mutex()

  private val _state = MutableStateFlow(
    HotspotSnapshot(
      status = HotspotStatus.UNKNOWN,
      enhancedAccess = EnhancedAccessState.NOT_INSTALLED,
      source = "System",
    ),
  )
  override val state: StateFlow<HotspotSnapshot> = _state.asStateFlow()

  override fun start() {
    if (!started.compareAndSet(false, true)) return
    val bridge = enhanced
    if (bridge == null) {
      readStandard()
      return
    }
    bridge.start()
    scope.launch {
      bridge.accessState.collect { access ->
        _state.update { it.copy(enhancedAccess = access) }
        if (access == EnhancedAccessState.READY) {
          val snap = try {
            bridge.fetchSnapshot()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            null
          }
          if (snap != null) {
            emitEnhanced(snap, bridge)
          } else if (bridge.accessState.value == EnhancedAccessState.READY) {
            // READY claimed but no fresh observation (transport failure,
            // decode failure, or buggy bridge): disable controls, keep the
            // last trustworthy status/clients with their age, surface stale.
            // Never invent OFF or zero clients here.
            _state.update { cur ->
              HotspotStalePolicy.markStale(
                current = cur,
                bridgeAccess = bridge.accessState.value,
                bridgeMessage = bridge.accessMessage.value,
              )
            }
          } else {
            readStandard()
          }
        } else {
          readStandard()
        }
      }
    }
    scope.launch {
      // Reactive message propagation: a grant/failure message can arrive
      // without a state transition, or after the state collector already
      // ran. Re-read standard (non-READY) so the UI updates immediately
      // instead of after a fixed delay.
      bridge.accessMessage.collect {
        if (bridge.accessState.value != EnhancedAccessState.READY) {
          readStandard()
        }
      }
    }
    scope.launch {
      bridge.pushedSnapshot.collect { snap ->
        if (snap != null && bridge.accessState.value == EnhancedAccessState.READY) {
          emitEnhanced(snap, bridge)
        }
      }
    }
    scope.launch { readStandard() }
  }

  override fun refresh() {
    val bridge = enhanced
    if (bridge == null) {
      readStandard()
      return
    }
    bridge.refreshAccess()
    scope.launch {
      if (bridge.accessState.value == EnhancedAccessState.READY) {
        val snap = try {
          bridge.fetchSnapshot()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          null
        }
        if (snap != null) {
          emitEnhanced(snap, bridge)
        } else if (bridge.accessState.value == EnhancedAccessState.READY) {
          // Same stale rule as the READY collector: readStandard() is a
          // NOOP while READY, so mark stale explicitly instead.
          _state.update { cur ->
            HotspotStalePolicy.markStale(
              current = cur,
              bridgeAccess = bridge.accessState.value,
              bridgeMessage = bridge.accessMessage.value,
            )
          }
        } else {
          readStandard()
        }
      } else {
        readStandard()
      }
    }
  }

  override fun requestEnhancedAccess() {
    // Reactive: start() collectors observe accessState + accessMessage, so
    // a grant, CONNECTING wait, or HELPER_UNAVAILABLE failure updates the
    // UI immediately with no fixed-delay sample.
    enhanced?.requestEnhancedAccess()
  }

  override suspend fun setEnabled(enabled: Boolean): OperationResult {
    val bridge = enhanced
    if (bridge == null || bridge.accessState.value != EnhancedAccessState.READY) {
      note("Open system hotspot settings to change the hotspot. Direct control needs enhanced access.")
      return OperationResult.NeedsSystemUi(HotspotSystemIntents.TETHER_SETTINGS_ACTION)
    }
    return opMutex.withLock {
      _state.update {
        it.copy(
          status = if (enabled) HotspotStatus.STARTING else HotspotStatus.STOPPING,
          message = if (enabled) "Requesting hotspot start." else "Requesting hotspot stop.",
        )
      }
      val result = if (enabled) bridge.startTetheredHotspot() else bridge.stopTethering()
      if (result == null) {
        // Transport failure: the helper did not answer. Do not claim
        // anything about whether the radio changed; re-read and report
        // the uncertainty.
        refresh()
        return@withLock OperationResult.Failure(
          "The hotspot helper did not answer; the hotspot state could not be confirmed. " +
            "State was re-read; check system settings.",
        )
      }
      if (!result.ok) {
        refresh()
        if (result.code == CODE_NEEDS_SYSTEM_UI) {
          return@withLock OperationResult.NeedsSystemUi(HotspotSystemIntents.TETHER_SETTINGS_ACTION)
        }
        return@withLock OperationResult.Failure(result.message.ifEmpty { "Hotspot request failed." })
      }
      // A success return code is not proof: confirm by re-reading with a
      // bounded poll. Start is slow (radio bring-up), stop is fast
      // (teardown is near instant), so stop uses a short timeout.
      val want = if (enabled) STATUS_ON else STATUS_OFF
      val timeout = if (enabled) START_CONFIRM_TIMEOUT_MILLIS else STOP_CONFIRM_TIMEOUT_MILLIS
      val deadline = System.currentTimeMillis() + timeout
      var observed: String? = null
      var last: BridgeSnapshot? = null
      while (System.currentTimeMillis() < deadline) {
        delay(CONFIRM_POLL_MILLIS)
        last = try {
          bridge.fetchSnapshot()
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          null
        } ?: continue
        emitEnhanced(last, bridge)
        observed = last.status
        if (observed == want) break
      }
      if (observed == want) {
        refresh()
        OperationResult.Success(
          if (enabled) "Hotspot on (observed)." else "Hotspot off (observed).",
        )
      } else {
        OperationResult.Failure(
          "The request was accepted but the hotspot state did not confirm " +
            "(${observed ?: last?.status ?: "unknown"}). State was re-read; check system settings.",
        )
      }
    }
  }

  override suspend fun blockClient(id: String, blocked: Boolean): OperationResult {
    val mac = HotspotBridgeProtocol.normalizeMac(id.removePrefix("mac:"))
      ?: HotspotBridgeProtocol.normalizeMac(id)
    if (mac == null) {
      return OperationResult.Failure("Unknown client id. Blocking needs an observed associated client.")
    }
    // No vendor block mechanism is proven live on this device; the only
    // honest answer until then.
    return OperationResult.Failure(
      "Client ${if (blocked) "blocking" else "unblocking"} for $mac is not proven live on this device yet, " +
        "so nothing was changed. Manage access in system hotspot settings.",
    )
  }

  override suspend fun applyProfile(profile: HotspotProfile): OperationResult {
    val problems = HotspotBridgeProtocol.validateProfile(profile.ssid, profile.password, profile.band)
    if (problems.isNotEmpty()) {
      return OperationResult.Failure(problems.joinToString(" "))
    }
    // Valid input, but no configuration path is proven live: hand off to
    // the real system screen instead of pretending to apply.
    note("Profile is valid. Apply it in system hotspot settings until a proven on-device path exists.")
    return OperationResult.NeedsSystemUi(HotspotSystemIntents.TETHER_SETTINGS_ACTION)
  }

  override fun close() {
    if (!started.compareAndSet(true, false)) return
    runCatching { enhanced?.close() }
    scope.cancel()
  }

  // ------------------------------------------------------------------
  // Mapping.
  // ------------------------------------------------------------------

  private fun emitEnhanced(snap: BridgeSnapshot, bridge: EnhancedHotspotBridge) {
    val ready = bridge.accessState.value == EnhancedAccessState.READY
    val eligible = HotspotBridgeProtocol.isToggleRequestEligible(
      status = snap.status,
      tetherSupported = snap.tetherSupported,
      accessReady = ready,
      controlAvailable = snap.controlAvailable,
    )
    _state.update {
      it.copy(
        status = when (snap.status) {
          STATUS_ON -> HotspotStatus.ON
          STATUS_OFF -> HotspotStatus.OFF
          STATUS_ERROR -> HotspotStatus.ERROR
          HotspotBridgeContract.STATUS_STARTING -> HotspotStatus.STARTING
          HotspotBridgeContract.STATUS_STOPPING -> HotspotStatus.STOPPING
          else -> HotspotStatus.UNKNOWN
        },
        ssid = snap.ssid,
        security = snap.security,
        clients = snap.clients?.map { c ->
          HotspotClient(
            id = c.id,
            macAddress = c.mac,
            name = c.hostname,
            addresses = c.addresses,
            connected = true,
            lastSeenAtMillis = c.lastSeenAtMillis,
          )
        },
        enhancedAccess = bridge.accessState.value,
        canToggle = eligible,
        canConfigure = false,
        canBlockClients = false,
        clientUsageAvailable = false,
        hardQuotaAvailable = false,
        message = snap.message ?: bridge.accessMessage.value,
        source = when (snap.source) {
          SOURCE_SOFTAP -> "SoftAp"
          SOURCE_TETHERING -> "Tethering"
          SOURCE_SYSTEM -> "System"
          else -> "Unknown"
        },
        lastUpdatedAtMillis = snap.atMillis,
      )
    }
  }

  private fun readStandard() {
    if (enhanced?.accessState?.value == EnhancedAccessState.READY) return
    val read = try {
      apReader.read()
    } catch (e: Exception) {
      ApRead(null, false)
    }
    _state.update {
      it.copy(
        status = when {
          read.enabled == true -> HotspotStatus.ON
          read.enabled == false -> HotspotStatus.OFF
          else -> HotspotStatus.UNKNOWN
        },
        clients = null,
        enhancedAccess = enhanced?.accessState?.value ?: EnhancedAccessState.NOT_INSTALLED,
        canToggle = false,
        canConfigure = false,
        canBlockClients = false,
        clientUsageAvailable = false,
        hardQuotaAvailable = false,
        message = when {
          read.permissionRequired -> "Hotspot state needs Wi-Fi state access. Grant it or open system hotspot settings."
          // Granted-but-not-ready helper status stays visible even when the
          // standard read has a value: CONNECTING/HELPER_UNAVAILABLE explain
          // why direct control is missing. Other non-READY states only
          // supply the message when the switch itself is unknown.
          enhanced?.accessState?.value == EnhancedAccessState.CONNECTING ||
            enhanced?.accessState?.value == EnhancedAccessState.HELPER_UNAVAILABLE ->
            enhanced?.accessMessage?.value
              ?: "Hotspot state is unknown. Open system hotspot settings for the real switch."
          read.enabled == null -> enhanced?.accessMessage?.value
            ?: "Hotspot state is unknown. Open system hotspot settings for the real switch."
          else -> null
        },
        source = "Unknown",
        lastUpdatedAtMillis = System.currentTimeMillis(),
      )
    }
  }

  private fun note(message: String) {
    _state.update { it.copy(message = message) }
  }

  companion object {
    private const val START_CONFIRM_TIMEOUT_MILLIS = 20_000L
    private const val STOP_CONFIRM_TIMEOUT_MILLIS = 6_000L
    private const val CONFIRM_POLL_MILLIS = 1_000L
  }
}
