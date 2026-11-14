package com.samuel.wirelesswhale

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samuel.wirelesswhale.core.model.WifiCredentials
import com.samuel.wirelesswhale.core.model.WifiSecurity
import com.samuel.wirelesswhale.feature.scanner.ScannerScreen
import com.samuel.wirelesswhale.qr.WifiQrCodec
import com.samuel.wirelesswhale.qr.createWifiQrBitmap
import com.samuel.wirelesswhale.ui.WirelessWhaleApp
import com.samuel.wirelesswhale.ui.theme.WirelessWhaleTheme

/**
 * Single app entry point. Wires the [WorkstationViewModel] to the polished UI
 * and owns every system handoff: contextual permission requests, the Android
 * network approval flow, settings panels, QR preview and share, Quick
 * Settings help, and tile intent routing.
 *
 * No root, no system mutation from this host: every control action is user
 * triggered, secrets stay transient in memory, and a network is reported as
 * saved only when the per-network result list confirms it for that network
 * (RESULT_OK alone proves nothing), never as connected until the repository
 * observes the connection.
 */
class MainActivity : ComponentActivity() {

  private val workstation: WorkstationViewModel by viewModels {
    object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T =
        WorkstationViewModel(application) as T
    }
  }

  private val wifiPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      workstation.onResumeReconcile()
    }

  private val addNetworksLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val pending = workstation.pendingConnect.value
      workstation.clearPendingConnect()
      val codes = result.data?.getIntegerArrayListExtra(Settings.EXTRA_WIFI_NETWORK_RESULT_LIST)
      when (AddNetworkSaveMapper.outcome(result.resultCode == RESULT_OK, codes?.toList())) {
        AddNetworkSaveMapper.SaveOutcome.SavedNew -> {
          val ssid = pending?.ssid ?: "the network"
          Toast.makeText(
            this,
            "Saved $ssid. It counts as connected only once the system connects.",
            Toast.LENGTH_LONG,
          ).show()
        }
        AddNetworkSaveMapper.SaveOutcome.AlreadyExists -> {
          val ssid = pending?.ssid ?: "the network"
          Toast.makeText(
            this,
            "$ssid is already saved on this device. Nothing changed.",
            Toast.LENGTH_LONG,
          ).show()
        }
        AddNetworkSaveMapper.SaveOutcome.Failed -> {
          Toast.makeText(
            this,
            "The system could not save this network. Check the details and try again.",
            Toast.LENGTH_LONG,
          ).show()
        }
        AddNetworkSaveMapper.SaveOutcome.Dismissed -> {
          Toast.makeText(
            this,
            "Nothing was confirmed as saved. The system screen closed without per-network confirmation.",
            Toast.LENGTH_LONG,
          ).show()
        }
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Route the launch intent only on first creation. On rotation the
    // Activity recreates but the ViewModel survives, so rehandling here
    // would replay a stale tile event into the fresh collectors.
    if (savedInstanceState == null) {
      workstation.handleTileAction(intent?.action)
    }
    enableEdgeToEdge()
    // White app: dark glyphs on light status and navigation bars.
    runCatching {
      val controller = WindowCompat.getInsetsController(window, window.decorView)
      controller.isAppearanceLightStatusBars = true
      controller.isAppearanceLightNavigationBars = true
    }
    setContent {
      WirelessWhaleTheme {
        WorkstationContent(
          viewModel = workstation,
          initialDestination = initialDestinationFor(intent),
          onRequestWifiPermission = ::requestWifiPermissions,
          onOpenWifiSettings = ::openWifiPanel,
          onConnectNetwork = ::connectNetwork,
          onShareText = ::sharePayloadText,
          onOpenHotspotSettings = ::openHotspotSettings,
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    // Warm path for singleTask: a tile tap while the app lives arrives here.
    // The channel buffers the scanner event until the collector is ready.
    workstation.handleTileAction(intent.action)
  }

  override fun onResume() {
    super.onResume()
    workstation.onResumeReconcile()
  }

  private fun initialDestinationFor(intent: Intent?): String = when (intent?.action) {
    ACTION_HOTSPOT -> "hotspot"
    ACTION_SCAN_QR -> "wifi"
    else -> "home"
  }

  /**
   * Contextual location permission only, requested when a scan needs it.
   * Fine plus coarse are always requested together on every SDK: Android 12
   * and above requires both in one request (this still applies on Android 13
   * and above), and a coarse-only grant still retries for fine. Nearby-devices
   * is never requested for scans; it is reserved for a feature that actually
   * needs peer APIs. Camera stays with the scanner screen itself. No blanket
   * onboarding request.
   */
  private fun requestWifiPermissions() {
    val granted = setOf(
      Manifest.permission.ACCESS_FINE_LOCATION,
      Manifest.permission.ACCESS_COARSE_LOCATION,
    ).filter {
      ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }.toSet()
    val missing = ScanPermissionPolicy.missingForScan(
      granted = granted,
      fine = Manifest.permission.ACCESS_FINE_LOCATION,
      coarse = Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    if (missing.isEmpty()) {
      workstation.onResumeReconcile()
      return
    }
    runCatching { wifiPermissionLauncher.launch(missing.toTypedArray()) }
  }

  /** Wi-Fi panel on Android 10 and above, with a safe settings fallback. */
  private fun openWifiPanel() {
    val panel = Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { startActivity(panel) }.isSuccess) return
    val settings = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { startActivity(settings) }.isFailure) {
      Toast.makeText(this, "Wi-Fi settings are not available here.", Toast.LENGTH_LONG).show()
    }
  }

  /** Hotspot string intent with a safe wireless settings fallback. */
  private fun openHotspotSettings() {
    val tether = Intent(ACTION_TETHER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { startActivity(tether) }.isSuccess) return
    val wireless = Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { startActivity(wireless) }.isSuccess) return
    val plain = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { startActivity(plain) }.isFailure) {
      Toast.makeText(this, "Hotspot settings are not available here.", Toast.LENGTH_LONG).show()
    }
  }

  /**
   * Validates the security the user previewed, then launches the system
   * approval flow. Android 10 falls back to suggestions plus settings, never
   * to a WifiNetworkSpecifier internet path. WEP and enterprise are refused
   * outright, never silently downgraded.
   */
  private fun connectNetwork(credentials: WifiCredentials) {
    if (!workstation.isConnectable(credentials.security)) {
      Toast.makeText(
        this,
        "This network type is unsupported here. Enter the details manually.",
        Toast.LENGTH_LONG,
      ).show()
      workstation.clearPendingConnect()
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val suggestion = runCatching { buildSuggestion(credentials) }.getOrElse {
        Toast.makeText(this, "These details cannot be added by the system.", Toast.LENGTH_LONG).show()
        workstation.clearPendingConnect()
        return
      }
      val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(
        Settings.EXTRA_WIFI_NETWORK_LIST,
        arrayListOf(suggestion),
      )
      runCatching { addNetworksLauncher.launch(intent) }.onFailure {
        workstation.clearPendingConnect()
        openWifiPanel()
      }
      return
    }
    val suggestion = runCatching { buildSuggestion(credentials) }.getOrElse {
      Toast.makeText(this, "These details cannot be added by the system.", Toast.LENGTH_LONG).show()
      workstation.clearPendingConnect()
      return
    }
    val manager = applicationContext.getSystemService(WifiManager::class.java)
    val status = runCatching { manager?.addNetworkSuggestions(listOf(suggestion)) }
      .getOrDefault(WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP)
    workstation.clearPendingConnect()
    if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
      Toast.makeText(
        this,
        "Suggestion saved. It counts as connected only once the system connects.",
        Toast.LENGTH_LONG,
      ).show()
    } else {
      Toast.makeText(this, "The suggestion was not saved. Open Wi-Fi settings.", Toast.LENGTH_LONG).show()
    }
    openWifiPanel()
  }

  private fun buildSuggestion(credentials: WifiCredentials): WifiNetworkSuggestion {
    val builder = WifiNetworkSuggestion.Builder().setSsid(credentials.ssid)
    if (credentials.hidden) builder.setIsHiddenSsid(true)
    when (credentials.security) {
      WifiSecurity.OPEN -> Unit
      WifiSecurity.WPA2_PERSONAL, WifiSecurity.WPA2_WPA3_PERSONAL ->
        builder.setWpa2Passphrase(credentials.password)
      WifiSecurity.WPA3_PERSONAL -> builder.setWpa3Passphrase(credentials.password)
      WifiSecurity.WEP, WifiSecurity.ENTERPRISE, WifiSecurity.OWE, WifiSecurity.UNKNOWN ->
        throw IllegalArgumentException("Unsupported network type for system approval.")
    }
    return builder.build()
  }

  /** Explicit user share of the QR payload text. Image file export is not offered. */
  private fun sharePayloadText(credentials: WifiCredentials) {
    val payload = runCatching { WifiQrCodec.encode(credentials) }.getOrElse {
      Toast.makeText(this, "This network type cannot be shared as a QR code.", Toast.LENGTH_LONG).show()
      return
    }
    val send = Intent(Intent.ACTION_SEND)
      .setType("text/plain")
      .putExtra(Intent.EXTRA_TEXT, payload)
    val chooser = Intent.createChooser(send, "Share Wi-Fi details")
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(chooser) }.onFailure {
      Toast.makeText(this, "Sharing is not available here.", Toast.LENGTH_LONG).show()
    }
  }

  companion object {
    /** Exact tile scan action from the integration contract. */
    const val ACTION_SCAN_QR = "com.samuel.wirelesswhale.action.SCAN_QR"

    /** Exact tile hotspot action from the integration contract. */
    const val ACTION_HOTSPOT = "com.samuel.wirelesswhale.action.HOTSPOT"

    private const val ACTION_TETHER_SETTINGS = "android.settings.TETHER_SETTINGS"
  }
}

@Composable
private fun WorkstationContent(
  viewModel: WorkstationViewModel,
  initialDestination: String,
  onRequestWifiPermission: () -> Unit,
  onOpenWifiSettings: () -> Unit,
  onConnectNetwork: (WifiCredentials) -> Unit,
  onShareText: (WifiCredentials) -> Unit,
  onOpenHotspotSettings: () -> Unit,
) {
  val wifi by viewModel.wifi.collectAsStateWithLifecycle()
  val networks by viewModel.networks.collectAsStateWithLifecycle()
  val scanState by viewModel.scanState.collectAsStateWithLifecycle()
  val hotspot by viewModel.hotspot.collectAsStateWithLifecycle()
  val diagnosticReport by viewModel.diagnosticReport.collectAsStateWithLifecycle()
  val diagnosticRunning by viewModel.diagnosticRunning.collectAsStateWithLifecycle()
  val discoveredDevices by viewModel.discoveredDevices.collectAsStateWithLifecycle()
  val discoveryRunning by viewModel.discoveryRunning.collectAsStateWithLifecycle()
  val history by viewModel.history.collectAsStateWithLifecycle()
  val pending by viewModel.pendingConnect.collectAsStateWithLifecycle()
  val shareRequest by viewModel.shareRequest.collectAsStateWithLifecycle()
  val message by viewModel.userMessage.collectAsStateWithLifecycle()
  val stationOverride by viewModel.stationOverride.collectAsStateWithLifecycle()
  val context = LocalContext.current

  var station by rememberSaveable { mutableStateOf(initialDestination) }
  var showScanner by rememberSaveable { mutableStateOf(false) }
  var showQsHelp by rememberSaveable { mutableStateOf(false) }
  var navigationRequestId by rememberSaveable { mutableLongStateOf(0L) }
  val mainUiState = rememberSaveableStateHolder()

  LaunchedEffect(stationOverride) {
    val next = stationOverride
    if (next != null) {
      station = next
      navigationRequestId += 1L
      viewModel.consumeStationOverride()
    }
  }
  LaunchedEffect(Unit) {
    viewModel.openScanner.collect { showScanner = true }
  }
  LaunchedEffect(Unit) {
    viewModel.openHotspotSettings.collect { onOpenHotspotSettings() }
  }
  LaunchedEffect(message) {
    if (message != null) {
      Toast.makeText(context, message, Toast.LENGTH_LONG).show()
      viewModel.consumeMessage()
    }
  }

  if (showScanner) {
    // System back closes the scanner instead of exiting the app. The scanner
    // screen itself is owned by the QR worker; this only owns the routing.
    BackHandler(onBack = { showScanner = false })
    ScannerScreen(
      onResult = { credentials ->
        if (showScanner) {
          showScanner = false
          viewModel.previewScanned(credentials)
        }
      },
      onClose = { showScanner = false },
    )
    return
  }

  // Preserve only the main UI's saveable navigation/scroll state while the
  // external scanner temporarily replaces its composition. Credentials stay
  // transient in the ViewModel and non-saveable form state.
  mainUiState.SaveableStateProvider("main-ui") {
  WirelessWhaleApp(
    wifi = wifi,
    networks = networks,
    scanState = scanState,
    hotspot = hotspot,
    diagnosticReport = diagnosticReport,
    diagnosticRunning = diagnosticRunning,
    discoveredDevices = discoveredDevices,
    discoveryRunning = discoveryRunning,
    history = history,
    initialDestination = station,
    onRequestWifiPermission = onRequestWifiPermission,
    onOpenWifiSettings = onOpenWifiSettings,
    onRefreshNetworks = viewModel::requestWifiScan,
    onConnectNetwork = { credentials -> viewModel.previewScanned(credentials) },
    onOpenScanner = { showScanner = true },
    onShareCredentials = viewModel::requestShare,
    onOpenHotspotSettings = onOpenHotspotSettings,
    onToggleHotspot = viewModel::toggleHotspot,
    onRequestEnhancedAccess = viewModel::requestEnhancedAccess,
    onBlockClient = viewModel::blockClient,
    onRunDiagnostics = viewModel::runDiagnostics,
    onCancelDiagnostics = viewModel::cancelDiagnostics,
    onStartDiscovery = viewModel::startDiscovery,
    onStopDiscovery = viewModel::stopDiscovery,
    onClearHistory = viewModel::clearHistory,
    onExportHistory = {
      val exported = viewModel.exportHistory()
      val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, exported)
      val chooser = Intent.createChooser(send, "Share history export")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      runCatching { context.startActivity(chooser) }
    },
    onOpenQuickSettingsHelp = { showQsHelp = true },
    navigationRequestId = navigationRequestId,
  )
  }

  pending?.let { credentials ->
    ConnectPreviewDialog(
      credentials = credentials,
      onConnect = { onConnectNetwork(credentials) },
      onShare = {
        viewModel.clearPendingConnect()
        viewModel.requestShare(credentials)
      },
      onDismiss = viewModel::clearPendingConnect,
    )
  }

  shareRequest?.let { credentials ->
    ShareQrDialog(
      credentials = credentials,
      onShare = {
        viewModel.clearShare()
        onShareText(credentials)
      },
      onDismiss = viewModel::clearShare,
    )
  }

  if (showQsHelp) {
    AlertDialog(
      onDismissRequest = { showQsHelp = false },
      title = { Text("Add the Quick Settings tiles") },
      text = {
        Text(
          "Swipe down twice to open Quick Settings, tap Edit, then drag " +
            "the Wireless Whale hotspot and scan tiles into place. " +
            "Tile actions unlock the phone first and never toggle anything silently.",
        )
      },
      confirmButton = {
        TextButton(onClick = { showQsHelp = false }) { Text("Done") }
      },
    )
  }
}

@Composable
private fun ConnectPreviewDialog(
  credentials: WifiCredentials,
  onConnect: () -> Unit,
  onShare: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Connect to this network?") },
    text = {
      // Scrollable so long SSIDs, large fonts, and short landscape heights
      // never clip the message or push actions off screen.
      Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text("Network: ${credentials.ssid}")
        Text("Security: ${securityLabel(credentials.security)}")
        if (credentials.hidden) Text("Hidden network: yes")
        Spacer(Modifier.height(8.dp))
        Text(
          "Connecting opens the Android approval screen. Nothing joins " +
            "until you confirm there.",
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onConnect) { Text("Connect") }
    },
    dismissButton = {
      Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.verticalScroll(rememberScrollState()),
      ) {
        TextButton(onClick = onShare) { Text("Share QR") }
        TextButton(onClick = onDismiss) { Text("Cancel") }
      }
    },
  )
}

@Composable
private fun ShareQrDialog(
  credentials: WifiCredentials,
  onShare: () -> Unit,
  onDismiss: () -> Unit,
) {
  val bitmap = remember(credentials) {
    runCatching { createWifiQrBitmap(credentials) }.getOrNull()
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Share this Wi-Fi QR code") },
    text = {
      // Scrollable content with a width-capped QR image: full width on
      // narrow phones, capped at 320dp on wide or landscape screens.
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
      ) {
        Text("Network: ${credentials.ssid}")
        Text("Security: ${securityLabel(credentials.security)}")
        Spacer(Modifier.height(12.dp))
        if (bitmap != null) {
          Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Wi-Fi QR code",
            modifier = Modifier
              .fillMaxWidth()
              .sizeIn(maxWidth = 320.dp, maxHeight = 320.dp)
              .aspectRatio(1f)
              .align(Alignment.CenterHorizontally),
          )
        } else {
          Text("The QR image could not be rendered on this device.")
        }
        Spacer(Modifier.height(8.dp))
        Text("Sharing sends the details to the app you choose. Files are not exported.")
      }
    },
    confirmButton = {
      TextButton(onClick = onShare) { Text("Share") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Close") }
    },
  )
}

private fun securityLabel(security: WifiSecurity): String = when (security) {
  WifiSecurity.OPEN -> "Open"
  WifiSecurity.WPA2_PERSONAL -> "WPA2"
  WifiSecurity.WPA3_PERSONAL -> "WPA3"
  WifiSecurity.WPA2_WPA3_PERSONAL -> "WPA2/WPA3 transition"
  WifiSecurity.WEP -> "WEP (unsupported)"
  WifiSecurity.ENTERPRISE -> "Enterprise (unsupported)"
  WifiSecurity.OWE -> "OWE (unsupported)"
  WifiSecurity.UNKNOWN -> "Unknown (unsupported)"
}
