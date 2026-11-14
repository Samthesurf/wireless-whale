package com.samuel.wirelesswhale.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.samuel.wirelesswhale.core.model.DiagnosticReport
import com.samuel.wirelesswhale.core.model.DiscoveredDevice
import com.samuel.wirelesswhale.core.model.HistoryEntry
import com.samuel.wirelesswhale.core.model.HotspotSnapshot
import com.samuel.wirelesswhale.core.model.HotspotStatus
import com.samuel.wirelesswhale.core.model.ScanState
import com.samuel.wirelesswhale.core.model.WifiCredentials
import com.samuel.wirelesswhale.core.model.WifiNetwork
import com.samuel.wirelesswhale.core.model.WifiSnapshot
import com.samuel.wirelesswhale.feature.home.HomeScreen
import com.samuel.wirelesswhale.feature.hotspot.ClientDetailScreen
import com.samuel.wirelesswhale.feature.hotspot.HotspotProfileSheet
import com.samuel.wirelesswhale.feature.hotspot.HotspotScreen
import com.samuel.wirelesswhale.feature.settings.AccessSettingsScreen
import com.samuel.wirelesswhale.feature.settings.LicensesScreen
import com.samuel.wirelesswhale.feature.settings.QuickSettingsHelpScreen
import com.samuel.wirelesswhale.feature.settings.SettingsScreen
import com.samuel.wirelesswhale.feature.tools.ConnectionCheckScreen
import com.samuel.wirelesswhale.feature.tools.HistoryScreen
import com.samuel.wirelesswhale.feature.tools.LanDiscoveryScreen
import com.samuel.wirelesswhale.feature.tools.ToolsScreen
import com.samuel.wirelesswhale.feature.wifi.WifiScreen
import com.samuel.wirelesswhale.navigation.WhaleRoute
import com.samuel.wirelesswhale.navigation.destinationFor
import com.samuel.wirelesswhale.navigation.nextStackForExternalRequest
import com.samuel.wirelesswhale.navigation.routeFromToken
import com.samuel.wirelesswhale.navigation.routeToken
import com.samuel.wirelesswhale.navigation.shouldConsumeNavigationRequest
import com.samuel.wirelesswhale.navigation.stackFromTokens
import kotlinx.coroutines.delay
import com.samuel.wirelesswhale.ui.components.CapsuleNav
import com.samuel.wirelesswhale.ui.components.WhaleDestination
import com.samuel.wirelesswhale.ui.theme.WhaleBackground
import com.samuel.wirelesswhale.ui.theme.WirelessWhaleTheme

/**
 * Stateless function over observations plus callbacks. Navigation and
 * UI-local sheet state stay inside. This is the exact integration contract
 * in docs/implementation-contract.md, extended by one parent-approved
 * trailing optional parameter: [navigationRequestId] carries the monotonic
 * tile intent token wired by MainActivity. Older callers that omit it keep
 * the previous behavior.
 */
@Composable
fun WirelessWhaleApp(
  wifi: WifiSnapshot,
  networks: List<WifiNetwork>,
  scanState: ScanState,
  hotspot: HotspotSnapshot,
  diagnosticReport: DiagnosticReport,
  diagnosticRunning: Boolean,
  discoveredDevices: List<DiscoveredDevice>,
  discoveryRunning: Boolean,
  history: List<HistoryEntry>,
  initialDestination: String = "home",
  onRequestWifiPermission: () -> Unit,
  onOpenWifiSettings: () -> Unit,
  onRefreshNetworks: () -> Unit,
  onConnectNetwork: (WifiCredentials) -> Unit,
  onOpenScanner: () -> Unit,
  onShareCredentials: (WifiCredentials) -> Unit,
  onOpenHotspotSettings: () -> Unit,
  onToggleHotspot: (Boolean) -> Unit,
  onRequestEnhancedAccess: () -> Unit,
  onBlockClient: (String, Boolean) -> Unit,
  onRunDiagnostics: () -> Unit,
  onCancelDiagnostics: () -> Unit,
  onStartDiscovery: () -> Unit,
  onStopDiscovery: () -> Unit,
  onClearHistory: () -> Unit,
  onExportHistory: () -> Unit,
  onOpenQuickSettingsHelp: () -> Unit,
  navigationRequestId: Long = 0L,
) {
  // Non-secret backstack only: tokens carry tab names and row ids, never
  // passwords or credential drafts. Rotation restores this list instead of
  // rebuilding from the launch destination, so the user's tab survives.
  val stack = rememberSaveable(saver = WhaleBackstackSaver) {
    mutableStateListOf<WhaleRoute>(WhaleRoute.Tab(destinationFor(initialDestination)))
  }
  var lastConsumedRequestId by rememberSaveable { mutableStateOf(0L) }
  var showProfile by rememberSaveable { mutableStateOf(false) }
  // Retains each route's scroll state while another route is on screen.
  // The scanner overlay lives outside this composable; the parent holds
  // this whole tree so a scanner round trip restores it with state intact.
  val tabHolder = rememberSaveableStateHolder()
  // Foreground clock for age stamps only. Never drives scans or stores.
  val now = rememberForegroundNow()

  // External tile intents arrive as a new initialDestination plus a
  // monotonic request token from MainActivity. A new token navigates even
  // when the destination text matches the last request, so a repeated
  // hotspot tap still drops back to the hotspot tab. Ordinary
  // recomposition and rotation reuse the saved token and keep the user's
  // current tab untouched.
  LaunchedEffect(navigationRequestId) {
    if (shouldConsumeNavigationRequest(navigationRequestId, lastConsumedRequestId)) {
      lastConsumedRequestId = navigationRequestId
      val next = nextStackForExternalRequest(stack.toList(), initialDestination)
      if (next != stack.toList()) {
        stack.clear()
        stack.addAll(next)
      }
      showProfile = false
    }
  }

  fun navigate(route: WhaleRoute) {
    if (route is WhaleRoute.Tab) {
      stack.clear()
      stack.add(route)
    } else {
      if (stack.lastOrNull() != route) stack.add(route)
    }
  }
  fun goBack(): Boolean {
    if (showProfile) {
      showProfile = false
      return true
    }
    if (stack.size > 1) {
      stack.removeAt(stack.lastIndex)
      return true
    }
    return false
  }
  BackHandler(enabled = stack.size > 1 || showProfile) { goBack() }

  val current = stack.last()
  val tab = (current as? WhaleRoute.Tab)?.destination ?: when (current) {
    is WhaleRoute.NetworkDetail -> WhaleDestination.Wifi
    is WhaleRoute.ClientDetail -> WhaleDestination.Hotspot
    is WhaleRoute.HotspotProfile -> WhaleDestination.Hotspot
    is WhaleRoute.ConnectionCheck, is WhaleRoute.LanDiscovery, is WhaleRoute.History -> WhaleDestination.Tools
    is WhaleRoute.Settings, is WhaleRoute.AccessSettings,
    is WhaleRoute.QuickSettingsHelp, is WhaleRoute.Licenses -> tabForSettingsReturn(stack)
    is WhaleRoute.Tab -> current.destination
  }

  WirelessWhaleTheme {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(WhaleBackground),
    ) {
      val navigateFn: (WhaleRoute) -> Unit = { navigate(it) }
      // Per-route retained state: switching tabs or pushing a detail keeps
      // each route's scroll position while it is off screen.
      tabHolder.SaveableStateProvider(routeToken(current)) {
      when (current) {
        is WhaleRoute.Tab -> when (current.destination) {
          WhaleDestination.Home -> HomeScreen(
            wifi = wifi,
            scan = scanState,
            hotspot = hotspot,
            recentHistory = history,
            nowMillis = now,
            onOpenScanner = onOpenScanner,
            onOpenWifiSettings = onOpenWifiSettings,
            onRequestWifiPermission = onRequestWifiPermission,
            onRefreshNetworks = onRefreshNetworks,
            onToggleHotspot = onToggleHotspot,
            onOpenHotspotSettings = onOpenHotspotSettings,
            onRunDiagnostics = onRunDiagnostics,
            onOpenSettings = { navigate(WhaleRoute.Settings) },
            onNavigate = navigateFn,
          )
          WhaleDestination.Wifi -> WifiScreen(
            wifi = wifi,
            networks = networks,
            scan = scanState,
            nowMillis = now,
            onRefreshNetworks = onRefreshNetworks,
            onConnectNetwork = onConnectNetwork,
            onShareCredentials = onShareCredentials,
            onOpenScanner = onOpenScanner,
            onOpenWifiSettings = onOpenWifiSettings,
            onRequestWifiPermission = onRequestWifiPermission,
            onOpenSettings = { navigate(WhaleRoute.Settings) },
          )
          WhaleDestination.Hotspot -> HotspotScreen(
            hotspot = hotspot,
            lanDevices = discoveredDevices,
            nowMillis = now,
            onToggleHotspot = onToggleHotspot,
            onOpenHotspotSettings = onOpenHotspotSettings,
            onRequestEnhancedAccess = onRequestEnhancedAccess,
            onBlockClient = onBlockClient,
            onOpenSettings = { navigate(WhaleRoute.Settings) },
            onNavigate = {
              if (it is WhaleRoute.HotspotProfile) showProfile = true else navigate(it)
            },
          )
          WhaleDestination.Tools -> ToolsScreen(
            report = diagnosticReport,
            diagnosticRunning = diagnosticRunning,
            discoveredDevices = discoveredDevices,
            discoveryRunning = discoveryRunning,
            history = history,
            onNavigate = navigateFn,
            onOpenSettings = { navigate(WhaleRoute.Settings) },
          )
        }
        is WhaleRoute.NetworkDetail -> WifiScreen(
          wifi = wifi,
          networks = networks,
          scan = scanState,
          nowMillis = now,
          onRefreshNetworks = onRefreshNetworks,
          onConnectNetwork = onConnectNetwork,
          onShareCredentials = onShareCredentials,
          onOpenScanner = onOpenScanner,
          onOpenWifiSettings = onOpenWifiSettings,
          onRequestWifiPermission = onRequestWifiPermission,
          onOpenSettings = { navigate(WhaleRoute.Settings) },
        )
        is WhaleRoute.ClientDetail -> ClientDetailScreen(
          client = hotspot.clients?.firstOrNull { it.id == current.clientId },
          clientUsageAvailable = hotspot.clientUsageAvailable,
          canBlockClients = hotspot.canBlockClients,
          onBlockClient = onBlockClient,
          onOpenHotspotSettings = onOpenHotspotSettings,
          onBack = { goBack() },
        )
        is WhaleRoute.HotspotProfile -> HotspotScreen(
          hotspot = hotspot,
          lanDevices = discoveredDevices,
          nowMillis = now,
          onToggleHotspot = onToggleHotspot,
          onOpenHotspotSettings = onOpenHotspotSettings,
          onRequestEnhancedAccess = onRequestEnhancedAccess,
          onBlockClient = onBlockClient,
          onOpenSettings = { navigate(WhaleRoute.Settings) },
          onNavigate = {
            if (it is WhaleRoute.HotspotProfile) showProfile = true else navigate(it)
          },
        )
        is WhaleRoute.ConnectionCheck -> ConnectionCheckScreen(
          report = diagnosticReport,
          running = diagnosticRunning,
          onRunDiagnostics = onRunDiagnostics,
          onCancelDiagnostics = onCancelDiagnostics,
          onBack = { goBack() },
        )
        is WhaleRoute.LanDiscovery -> LanDiscoveryScreen(
          devices = discoveredDevices,
          running = discoveryRunning,
          onStartDiscovery = onStartDiscovery,
          onStopDiscovery = onStopDiscovery,
          onBack = { goBack() },
        )
        is WhaleRoute.History -> HistoryScreen(
          history = history,
          nowMillis = now,
          onClearHistory = onClearHistory,
          onExportHistory = onExportHistory,
          onBack = { goBack() },
        )
        is WhaleRoute.Settings -> SettingsScreen(
          hotspot = hotspot,
          onRequestEnhancedAccess = onRequestEnhancedAccess,
          onNavigate = navigateFn,
          onBack = { goBack() },
        )
        is WhaleRoute.AccessSettings -> AccessSettingsScreen(
          hotspot = hotspot,
          onRequestEnhancedAccess = onRequestEnhancedAccess,
          onBack = { goBack() },
        )
        is WhaleRoute.QuickSettingsHelp -> QuickSettingsHelpScreen(
          onBack = { goBack() },
          onOpenSystemSettings = onOpenQuickSettingsHelp,
        )
        is WhaleRoute.Licenses -> LicensesScreen(onBack = { goBack() })
      }
      }

      if (showProfile) {
        HotspotProfileSheet(
          hotspot = hotspot,
          onOpenHotspotSettings = onOpenHotspotSettings,
          onDismiss = { showProfile = false },
        )
      }

      CapsuleNav(
        current = tab,
        onSelect = { navigate(WhaleRoute.Tab(it)) },
        modifier = Modifier.align(Alignment.BottomCenter),
      )
    }
  }
}

private val WhaleBackstackSaver: Saver<SnapshotStateList<WhaleRoute>, ArrayList<String>> = Saver(
  save = { stack -> ArrayList(stack.map(::routeToken)) },
  restore = { saved -> stackFromTokens(saved)?.toMutableStateList() },
)

private fun tabForSettingsReturn(stack: List<WhaleRoute>): WhaleDestination {
  val lastTab = stack.filterIsInstance<WhaleRoute.Tab>().lastOrNull()
  return lastTab?.destination ?: WhaleDestination.Home
}

/**
 * Foreground-only clock for age stamps. Ticks while RESUMED so "Last
 * updated" lines stay fresh without re-running scans or touching
 * repositories; a backgrounded UI simply keeps showing the last value.
 */
@Composable
private fun rememberForegroundNow(tickMillis: Long = 15_000L): Long {
  var now by remember { mutableStateOf(System.currentTimeMillis()) }
  val owner = LocalLifecycleOwner.current
  LaunchedEffect(owner) {
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) {
        now = System.currentTimeMillis()
        delay(tickMillis)
      }
    }
  }
  return now
}

@Preview(showBackground = true, widthDp = 360, heightDp = 820)
@Composable
private fun WirelessWhaleAppPreview() {
  // Design fixture: static preview data only.
  val now = System.currentTimeMillis()
  WirelessWhaleApp(
    wifi = WifiSnapshot(
      enabled = true,
      connected = true,
      ssid = "Home Fibre",
      bssid = "aa:bb:cc:dd:ee:ff",
      rssiDbm = -62,
      linkSpeedMbps = 240,
      ipv4 = listOf("192.168.1.42"),
      gateway = "192.168.1.1",
      internet = com.samuel.wirelesswhale.core.model.InternetState.VALIDATED,
      lastUpdatedAtMillis = now - 240_000,
    ),
    networks = listOf(
      WifiNetwork("1", "Home Fibre", "aa:bb:cc:dd:ee:ff", -58, 5180, com.samuel.wirelesswhale.core.model.WifiSecurity.WPA2_PERSONAL, lastSeenAtMillis = now - 20_000),
    ),
    scanState = ScanState(lastScanAtMillis = now - 20_000),
    hotspot = HotspotSnapshot(status = HotspotStatus.OFF),
    diagnosticReport = DiagnosticReport(),
    diagnosticRunning = false,
    discoveredDevices = emptyList(),
    discoveryRunning = false,
    history = emptyList(),
    onRequestWifiPermission = {},
    onOpenWifiSettings = {},
    onRefreshNetworks = {},
    onConnectNetwork = {},
    onOpenScanner = {},
    onShareCredentials = {},
    onOpenHotspotSettings = {},
    onToggleHotspot = {},
    onRequestEnhancedAccess = {},
    onBlockClient = { _, _ -> },
    onRunDiagnostics = {},
    onCancelDiagnostics = {},
    onStartDiscovery = {},
    onStopDiscovery = {},
    onClearHistory = {},
    onExportHistory = {},
    onOpenQuickSettingsHelp = {},
  )
}
