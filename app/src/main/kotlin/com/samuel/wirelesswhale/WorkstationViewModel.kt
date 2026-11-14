package com.samuel.wirelesswhale

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.samuel.wirelesswhale.core.model.CheckStatus
import com.samuel.wirelesswhale.core.model.DiagnosticReport
import com.samuel.wirelesswhale.core.model.HotspotSnapshot
import com.samuel.wirelesswhale.core.model.OperationResult
import com.samuel.wirelesswhale.core.model.WifiCredentials
import com.samuel.wirelesswhale.core.model.WifiSecurity
import com.samuel.wirelesswhale.core.platform.HotspotRepository
import com.samuel.wirelesswhale.core.platform.WifiRepository
import com.samuel.wirelesswhale.data.HistoryStore
import com.samuel.wirelesswhale.diagnostics.ConnectionCheckRunner
import com.samuel.wirelesswhale.diagnostics.LanDiscoveryRunner
import com.samuel.wirelesswhale.platform.hotspot.AndroidHotspotRepository
import com.samuel.wirelesswhale.platform.publicapi.AndroidWifiRepository
import com.samuel.wirelesswhale.qr.WifiQrCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * App assembly ViewModel. Wires the shared real repositories and owns every
 * user triggered action behind the polished UI callbacks.
 *
 * Real state only: Wi-Fi observations come from [AndroidWifiRepository],
 * hotspot state and control from the hotspot worker's [HotspotRepository]
 * (a required typed dependency, never a reflective stub), diagnostics from
 * [ConnectionCheckRunner], nearby device visibility from
 * [LanDiscoveryRunner], and local notes from [HistoryStore]. Standard-mode
 * hotspot changes the backend cannot perform itself route to the real
 * system settings instead of pretending to work.
 *
 * Secrets are transient: pending and shared credentials live in plain
 * in-memory StateFlows, are never placed in a SavedStateHandle, and are never
 * written to history. History entries are redacted by [HistoryStore]. Only
 * non-secret UI flags (station route, scanner open) survive rotation via
 * saved instance state; credentials never do.
 */
class WorkstationViewModel(application: Application) : AndroidViewModel(application) {

  private val wifiRepo: WifiRepository = AndroidWifiRepository(application)
  private val hotspotRepo: HotspotRepository = AndroidHotspotRepository(application)
  private val diagnosticsRunner = ConnectionCheckRunner(application)
  private val discoveryRunner = LanDiscoveryRunner(application)
  private val historyStore = HistoryStore(application)

  /**
   * The hotspot backend is a required typed dependency. If the hotspot
   * worker has not landed its repository this fails at compile time instead
   * of quietly shipping a settings-only stub.
   */
  val hotspotAvailable: Boolean get() = true

  val wifi = wifiRepo.connection
  val networks = wifiRepo.networks
  val scanState = wifiRepo.scanState

  val hotspot: StateFlow<HotspotSnapshot> = hotspotRepo.state

  val discoveredDevices = discoveryRunner.devices
  val discoveryRunning = discoveryRunner.running
  val history = historyStore.entries

  private val _diagnosticReport = MutableStateFlow(DiagnosticReport())
  val diagnosticReport: StateFlow<DiagnosticReport> = _diagnosticReport.asStateFlow()

  private val _diagnosticRunning = MutableStateFlow(false)
  val diagnosticRunning: StateFlow<Boolean> = _diagnosticRunning.asStateFlow()

  /** Scanned credentials awaiting explicit user action. Transient, never saved. */
  private val _pendingConnect = MutableStateFlow<WifiCredentials?>(null)
  val pendingConnect: StateFlow<WifiCredentials?> = _pendingConnect.asStateFlow()

  /** Credentials the user explicitly chose to share. Transient, never saved. */
  private val _shareRequest = MutableStateFlow<WifiCredentials?>(null)
  val shareRequest: StateFlow<WifiCredentials?> = _shareRequest.asStateFlow()

  private val _userMessage = MutableStateFlow<String?>(null)
  val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

  /** Tile or intent requested a destination tab. Consumed once by the Activity. */
  private val _stationOverride = MutableStateFlow<String?>(null)
  val stationOverride: StateFlow<String?> = _stationOverride.asStateFlow()

  /**
   * One-shot scanner open requests. A buffered channel (not a replay-zero
   * shared flow) so a cold tile event emitted before the Activity collects
   * is still delivered instead of lost.
   */
  private val _openScanner = Channel<Unit>(Channel.BUFFERED)
  val openScanner: Flow<Unit> = _openScanner.receiveAsFlow()

  private val _openHotspotSettings = Channel<Unit>(Channel.BUFFERED)
  val openHotspotSettings: Flow<Unit> = _openHotspotSettings.receiveAsFlow()

  private var diagnosticsJob: Job? = null

  init {
    wifiRepo.start()
    hotspotRepo.start()
  }

  override fun onCleared() {
    diagnosticsJob?.cancel()
    discoveryRunner.stop()
    wifiRepo.close()
    hotspotRepo.close()
  }

  /** Reconciles live state when returning to the foreground or from Settings. */
  fun onResumeReconcile() {
    wifiRepo.refreshPermissions()
    hotspotRepo.refresh()
  }

  fun requestWifiScan() {
    wifiRepo.requestScan()
  }

  fun consumeMessage() {
    _userMessage.value = null
  }

  /**
   * Handles a tile or launcher intent action. SCAN_QR opens the wifi tab and
   * the scanner; HOTSPOT opens the hotspot tab. Unknown actions are ignored.
   */
  fun handleTileAction(action: String?) {
    when (action) {
      MainActivity.ACTION_SCAN_QR -> {
        _stationOverride.value = "wifi"
        _openScanner.trySend(Unit)
      }
      MainActivity.ACTION_HOTSPOT -> {
        _stationOverride.value = "hotspot"
      }
      else -> Unit
    }
  }

  fun requestStation(destination: String) {
    _stationOverride.value = destination
  }

  fun consumeStationOverride() {
    _stationOverride.value = null
  }

  fun openScanner() {
    _openScanner.trySend(Unit)
  }

  /**
   * Stages scanned credentials for the user preview. Unsupported types are
   * rejected with an explicit message, never silently downgraded.
   */
  fun previewScanned(credentials: WifiCredentials) {
    if (!isConnectable(credentials.security)) {
      showMessage(
        "This network type cannot be added here. " +
          "WEP and enterprise networks are unsupported, so enter the details manually.",
      )
      historyStore.add(
        "QR scanned, unsupported type",
        "A scanned code named an unsupported network type. No connection was attempted.",
        "qr",
      )
      return
    }
    _pendingConnect.value = credentials
  }

  fun clearPendingConnect() {
    _pendingConnect.value = null
  }

  /**
   * Stages an explicit user share request. Only types the encoder supports
   * are accepted; anything else is reported, never downgraded.
   */
  fun requestShare(credentials: WifiCredentials) {
    val encodable = runCatching { WifiQrCodec.encode(credentials) }.isSuccess
    if (!encodable) {
      showMessage("This network type cannot be shared as a QR code.")
      return
    }
    _shareRequest.value = credentials
  }

  fun clearShare() {
    _shareRequest.value = null
  }

  fun toggleHotspot(enabled: Boolean) {
    val repo = hotspotRepo
    viewModelScope.launch {
      val result = try {
        repo.setEnabled(enabled)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        OperationResult.Failure("Hotspot control failed before it reported a result.")
      }
      when (result) {
        is OperationResult.Success -> {
          showMessage(result.message)
          historyStore.add(
            if (enabled) "Hotspot start requested" else "Hotspot stop requested",
            result.message,
            "hotspot",
          )
        }
        is OperationResult.Failure -> {
          showMessage(result.message)
          historyStore.add("Hotspot request failed", result.message, "hotspot")
        }
        is OperationResult.NeedsSystemUi -> {
          showMessage("The system must confirm this hotspot change. Opening system controls.")
          historyStore.add(
            if (enabled) "Hotspot start needs system confirmation"
            else "Hotspot stop needs system confirmation",
            "The backend deferred to system UI instead of toggling directly.",
            "hotspot",
          )
          _openHotspotSettings.trySend(Unit)
        }
      }
    }
  }

  fun blockClient(id: String, blocked: Boolean) {
    val repo = hotspotRepo
    viewModelScope.launch {
      val result = try {
        repo.blockClient(id, blocked)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        OperationResult.Failure("The client request failed before it reported a result.")
      }
      when (result) {
        is OperationResult.Success -> {
          showMessage(result.message)
          historyStore.add("Hotspot client request", result.message, "hotspot")
        }
        is OperationResult.Failure -> {
          showMessage(result.message)
          historyStore.add("Hotspot client request failed", result.message, "hotspot")
        }
        is OperationResult.NeedsSystemUi -> {
          showMessage("The system must confirm this client change. Opening system controls.")
          _openHotspotSettings.trySend(Unit)
        }
      }
    }
  }

  /**
   * Forwards an explicit user request for enhanced access. The request is
   * only sent here; approval is confirmed only when the hotspot state later
   * reports ready, never by this call returning.
   */
  fun requestEnhancedAccess() {
    hotspotRepo.requestEnhancedAccess()
    showMessage("Enhanced access request sent. Approve it in the system prompt if one appears.")
    historyStore.add(
      "Enhanced access requested",
      "The request was forwarded after an explicit user action. " +
        "Access counts as granted only when the hotspot screen reports ready.",
      "hotspot",
    )
  }

  /** Starts an explicit user driven diagnostics run. Only this starts probes. */
  fun runDiagnostics() {
    if (_diagnosticRunning.value) return
    diagnosticsJob?.cancel()
    diagnosticsJob = viewModelScope.launch {
      _diagnosticRunning.value = true
      _diagnosticReport.value = DiagnosticReport(startedAtMillis = System.currentTimeMillis())
      try {
        val report = diagnosticsRunner.run { step ->
          _diagnosticReport.update { current ->
            val steps = current.steps.toMutableList()
            val index = steps.indexOfFirst { it.id == step.id }
            if (index >= 0) steps[index] = step else steps.add(step)
            current.copy(steps = steps.toList())
          }
        }
        _diagnosticReport.value = report
        val passed = report.steps.count { it.status == CheckStatus.PASSED }
        historyStore.add(
          if (report.cancelled) "Diagnostics cancelled" else "Diagnostics finished",
          "Ran ${report.steps.size} checks, $passed passed. See the Tools tab for step detail.",
          "diagnostics",
        )
      } catch (e: CancellationException) {
        _diagnosticReport.update {
          it.copy(cancelled = true, finishedAtMillis = System.currentTimeMillis())
        }
        historyStore.add("Diagnostics cancelled", "The run was cancelled by the user.", "diagnostics")
      } finally {
        _diagnosticRunning.value = false
      }
    }
  }

  fun cancelDiagnostics() {
    diagnosticsJob?.cancel()
  }

  fun startDiscovery() {
    discoveryRunner.start()
    if (discoveryRunning.value) {
      historyStore.add(
        "Device discovery started",
        "Scoped local service discovery only. Silent devices do not appear.",
        "discovery",
      )
    } else {
      val reason = discoveryRunner.error.value
      historyStore.add(
        "Device discovery failed to start",
        reason ?: "Discovery could not start on this device. No session is running.",
        "discovery",
      )
      showMessage(reason ?: "Discovery could not start on this device.")
    }
  }

  fun stopDiscovery() {
    discoveryRunner.stop()
  }

  fun clearHistory() {
    historyStore.clear()
    showMessage("Local history was cleared.")
  }

  /** Redacted JSON export of the local history. Safe to hand to a share sheet. */
  fun exportHistory(): String = historyStore.exportRedacted()

  /** True for the types the system approval flow can add. Never downgrades. */
  fun isConnectable(security: WifiSecurity): Boolean = when (security) {
    WifiSecurity.OPEN,
    WifiSecurity.WPA2_PERSONAL,
    WifiSecurity.WPA3_PERSONAL,
    WifiSecurity.WPA2_WPA3_PERSONAL,
    -> true
    WifiSecurity.WEP,
    WifiSecurity.ENTERPRISE,
    WifiSecurity.OWE,
    WifiSecurity.UNKNOWN,
    -> false
  }

  private fun showMessage(message: String) {
    _userMessage.value = message
  }
}
