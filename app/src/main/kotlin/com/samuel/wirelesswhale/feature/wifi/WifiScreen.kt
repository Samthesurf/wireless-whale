package com.samuel.wirelesswhale.feature.wifi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.samuel.wirelesswhale.core.domain.sanitizeBssid
import com.samuel.wirelesswhale.core.domain.sortWifiNetworks
import com.samuel.wirelesswhale.core.model.ScanState
import com.samuel.wirelesswhale.core.model.WifiCredentials
import com.samuel.wirelesswhale.core.model.WifiNetwork
import com.samuel.wirelesswhale.core.model.WifiSecurity
import com.samuel.wirelesswhale.core.model.WifiSnapshot
import com.samuel.wirelesswhale.ui.bandLabel
import com.samuel.wirelesswhale.ui.formatAge
import com.samuel.wirelesswhale.ui.securityLabel
import com.samuel.wirelesswhale.ui.signalBars
import com.samuel.wirelesswhale.ui.signalLabel
import com.samuel.wirelesswhale.ui.validatePassword
import com.samuel.wirelesswhale.ui.validateSsid
import com.samuel.wirelesswhale.ui.components.AppTopBar
import com.samuel.wirelesswhale.ui.components.CapabilityNotice
import com.samuel.wirelesswhale.ui.components.MetricRow
import com.samuel.wirelesswhale.ui.components.NetworkRow
import com.samuel.wirelesswhale.ui.components.NoticeTone
import com.samuel.wirelesswhale.ui.components.SectionTitle
import com.samuel.wirelesswhale.ui.components.SummaryCard
import com.samuel.wirelesswhale.ui.components.WhaleEmptyState
import com.samuel.wirelesswhale.ui.components.WhalePrimaryButton
import com.samuel.wirelesswhale.ui.components.WhaleSecondaryButton
import com.samuel.wirelesswhale.ui.components.WhaleSecretField
import com.samuel.wirelesswhale.ui.components.WhaleTextField
import com.samuel.wirelesswhale.ui.theme.WhaleBackground
import com.samuel.wirelesswhale.ui.theme.WhaleInk
import com.samuel.wirelesswhale.ui.theme.WhaleSecondary
import com.samuel.wirelesswhale.ui.theme.WirelessWhaleTheme

private enum class SecurityFilter { All, Open, Secured }
private enum class SortMode { Signal, Name }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiScreen(
  wifi: WifiSnapshot,
  networks: List<WifiNetwork>,
  scan: ScanState,
  nowMillis: Long,
  onRefreshNetworks: () -> Unit,
  onConnectNetwork: (WifiCredentials) -> Unit,
  onShareCredentials: (WifiCredentials) -> Unit,
  onOpenScanner: () -> Unit,
  onOpenWifiSettings: () -> Unit,
  onRequestWifiPermission: () -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var query by remember { mutableStateOf("") }
  var filter by remember { mutableStateOf(SecurityFilter.All) }
  var sort by remember { mutableStateOf(SortMode.Signal) }
  var detailId by remember { mutableStateOf<String?>(null) }
  var connectId by remember { mutableStateOf<String?>(null) }
  var shareId by remember { mutableStateOf<String?>(null) }

  val ordered = remember(networks, wifi.bssid) {
    sortWifiNetworks(networks, connectedBssid = wifi.bssid)
  }
  val visible = remember(ordered, query, filter, sort) {
    var list = ordered
    if (filter == SecurityFilter.Open) list = list.filter { it.security == WifiSecurity.OPEN || it.security == WifiSecurity.OWE }
    if (filter == SecurityFilter.Secured) list = list.filter { it.security != WifiSecurity.OPEN && it.security != WifiSecurity.OWE }
    val q = query.trim()
    if (q.isNotEmpty()) list = list.filter { it.ssid.contains(q, ignoreCase = true) }
    if (sort == SortMode.Name) list = list.sortedBy { it.ssid.lowercase() }
    list
  }
  val connectedBssid = sanitizeBssid(wifi.bssid)
  val (connected, nearby) = remember(visible, connectedBssid, wifi.connected) {
    if (!wifi.connected || connectedBssid == null) emptyList<WifiNetwork>() to visible
    else visible.partition { sanitizeBssid(it.bssid) == connectedBssid }
  }
  val detail = remember(detailId, networks) { networks.firstOrNull { it.id == detailId } }
  val connectTarget = remember(connectId, networks) { networks.firstOrNull { it.id == connectId } }
  val shareTarget = remember(shareId, networks) { networks.firstOrNull { it.id == shareId } }

  LazyColumn(
    modifier = modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 116.dp),
  ) {
    item {
      AppTopBar(
        title = "Wi-Fi",
        subtitle = scanSubtitle(scan, networks.size, nowMillis),
        showSettings = true,
        onSettings = onOpenSettings,
        horizontalPadding = 0.dp,
      )
    }
    if (wifi.permissionRequired || scan.permissionRequired || !wifi.locationEnabled) {
      item {
        CapabilityNotice(
          tone = NoticeTone.Setup,
          headline = "Location permission needed",
          cause = "Scan results and network names stay hidden until location permission is granted and location is on.",
          actionLabel = "Grant permission",
          onAction = onRequestWifiPermission,
        )
        Spacer(Modifier.height(12.dp))
      }
    }
    if (!wifi.enabled) {
      item {
        CapabilityNotice(
          tone = NoticeTone.Setup,
          headline = "Wi-Fi is off",
          cause = "Turn Wi-Fi on to scan. Saved details stay where they are until then.",
          actionLabel = "Open Wi-Fi settings",
          onAction = onOpenWifiSettings,
        )
        Spacer(Modifier.height(12.dp))
      }
    }
    item {
      TextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("Search networks") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
          focusedIndicatorColor = Color.Transparent,
          unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
      )
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp),
      ) {
        FilterChip(
          selected = filter == SecurityFilter.All,
          onClick = { filter = SecurityFilter.All },
          label = { Text("All") },
          colors = chipColors(),
        )
        FilterChip(
          selected = filter == SecurityFilter.Open,
          onClick = { filter = SecurityFilter.Open },
          label = { Text("Open") },
          colors = chipColors(),
        )
        FilterChip(
          selected = filter == SecurityFilter.Secured,
          onClick = { filter = SecurityFilter.Secured },
          label = { Text("Secured") },
          colors = chipColors(),
        )
        FilterChip(
          selected = sort == SortMode.Signal,
          onClick = { sort = if (sort == SortMode.Signal) SortMode.Name else SortMode.Signal },
          label = { Text(if (sort == SortMode.Signal) "Strongest first" else "Name A to Z") },
          colors = chipColors(),
        )
      }
      Spacer(Modifier.height(4.dp))
    }
    if (scan.throttled) {
      item {
        CapabilityNotice(
          tone = NoticeTone.Unsupported,
          headline = "Scans are slowed",
          cause = "Android limits how often apps can scan. The list below is the latest real result, not a fresh scan.",
          actionLabel = "Refresh anyway",
          onAction = onRefreshNetworks,
        )
        Spacer(Modifier.height(12.dp))
      }
    }
    if (connected.isNotEmpty()) {
      item { SectionTitle("Current connection") }
      items(connected, key = { it.id }) { network ->
        NetworkRow(
          ssid = network.ssid,
          signalText = signalLabel(network.rssiDbm),
          securityText = securityLabel(network.security),
          bandText = bandLabel(network.frequencyMhz),
          level = signalBars(network.rssiDbm),
          subtitle = lastSeenLine(network.lastSeenAtMillis, nowMillis, scan.scanning),
          connected = true,
          onClick = { detailId = network.id },
        )
      }
    }
    item { SectionTitle(if (connected.isNotEmpty()) "Nearby networks" else "Networks") }
    if (!wifi.enabled) {
      item {
        Text(
          "Scanning is paused while Wi-Fi is off.",
          style = MaterialTheme.typography.bodyMedium,
          color = WhaleSecondary,
        )
      }
    } else if (nearby.isEmpty() && networks.isEmpty()) {
      item {
        WhaleEmptyState(
          title = "No scan results yet",
          reason = if (scan.scanning) "A scan is running. Results appear here when the system reports them."
          else "Nothing observed in range. Refresh to ask the system for a new scan.",
          actionLabel = "Refresh",
          onAction = onRefreshNetworks,
        )
      }
    } else if (nearby.isEmpty()) {
      item {
        WhaleEmptyState(
          title = "No networks match",
          reason = "Try a different search or clear the security filter.",
          actionLabel = "Clear search",
          onAction = { query = ""; filter = SecurityFilter.All },
        )
      }
    }
    items(nearby, key = { it.id }) { network ->
      NetworkRow(
        ssid = network.ssid,
        signalText = signalLabel(network.rssiDbm),
        securityText = securityLabel(network.security),
        bandText = bandLabel(network.frequencyMhz),
        level = signalBars(network.rssiDbm),
        subtitle = lastSeenLine(network.lastSeenAtMillis, nowMillis, scan.scanning),
        connected = false,
        onClick = { detailId = network.id },
      )
    }
    item {
      Spacer(Modifier.height(16.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { WhaleSecondaryButton("Refresh", onClick = onRefreshNetworks) }
        Column(Modifier.weight(1f)) { WhaleSecondaryButton("Scan QR", onClick = onOpenScanner) }
      }
    }
  }

  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  if (detail != null) {
    val network = detail
    ModalBottomSheet(onDismissRequest = { detailId = null }, sheetState = sheetState) {
      NetworkDetailSheet(
        network = network,
        nowMillis = nowMillis,
        connected = connectedBssid != null && sanitizeBssid(network.bssid) == connectedBssid,
        onConnect = { detailId = null; connectId = network.id },
        onShare = { detailId = null; shareId = network.id },
        onOpenWifiSettings = onOpenWifiSettings,
      )
    }
  }
  if (connectTarget != null) {
    val network = connectTarget
    ModalBottomSheet(onDismissRequest = { connectId = null }, sheetState = sheetState) {
      CredentialFormSheet(
        title = "Connect",
        headline = network.ssid,
        initialSsid = network.ssid,
        ssidEditable = false,
        security = network.security,
        confirmLabel = "Connect",
        note = "Connecting uses the system approval flow. Nothing happens silently.",
        onConfirm = { credentials ->
          connectId = null
          onConnectNetwork(credentials)
        },
      )
    }
  }
  if (shareTarget != null) {
    val network = shareTarget
    ModalBottomSheet(onDismissRequest = { shareId = null }, sheetState = sheetState) {
      CredentialFormSheet(
        title = "Share",
        headline = network.ssid,
        initialSsid = network.ssid,
        ssidEditable = false,
        security = network.security,
        confirmLabel = "Share QR",
        note = "Sharing hands these details to the QR screen. The code itself is created there, not stored here.",
        onConfirm = { credentials ->
          shareId = null
          onShareCredentials(credentials)
        },
      )
    }
  }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
  selectedContainerColor = WhaleInk,
  selectedLabelColor = Color.White,
)

private fun scanSubtitle(scan: ScanState, count: Int, now: Long): String {
  if (scan.scanning) return "Scanning"
  val age = formatAge(scan.lastScanAtMillis, now)
  return if (age == null) {
    if (count == 0) "No scan yet" else "$count observed"
  } else "$count observed · $age"
}

private fun lastSeenLine(lastSeen: Long?, now: Long, scanning: Boolean): String? {
  val age = formatAge(lastSeen, now) ?: return if (scanning) "Scanning now" else null
  return "Last seen $age"
}

@Composable
private fun NetworkDetailSheet(
  network: WifiNetwork,
  nowMillis: Long,
  connected: Boolean,
  onConnect: () -> Unit,
  onShare: () -> Unit,
  onOpenWifiSettings: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .navigationBarsPadding()
      .imePadding()
      .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
  ) {
    Text(network.ssid, style = MaterialTheme.typography.headlineMedium)
    Text(
      "${signalLabel(network.rssiDbm)}  ·  ${securityLabel(network.security)}  ·  ${bandLabel(network.frequencyMhz)}",
      style = MaterialTheme.typography.bodyMedium,
      color = WhaleSecondary,
      modifier = Modifier.padding(top = 4.dp),
    )
    if (connected) {
      Text("Connected", style = MaterialTheme.typography.labelLarge, color = com.samuel.wirelesswhale.ui.theme.WhaleAccent, modifier = Modifier.padding(top = 4.dp))
    }
    Spacer(Modifier.height(16.dp))
    SummaryCard {
      MetricRow("Signal", "${network.rssiDbm} dBm (${signalLabel(network.rssiDbm)})", mono = true)
      MetricRow("BSSID", network.bssid, mono = true)
      MetricRow(
        "Last seen",
        formatAge(network.lastSeenAtMillis, nowMillis),
        unavailableCause = if (network.lastSeenAtMillis == null) "The system did not stamp this result." else null,
      )
      MetricRow("Security", securityLabel(network.security))
    }
    Spacer(Modifier.height(12.dp))
    when (network.security) {
      WifiSecurity.WEP, WifiSecurity.ENTERPRISE -> CapabilityNotice(
        tone = NoticeTone.Unsupported,
        headline = "Needs system settings",
        cause = "This ${securityLabel(network.security).lowercase()} network cannot be joined from inside this app. Join it in system settings instead.",
        actionLabel = "Open system settings",
        onAction = onOpenWifiSettings,
      )
      WifiSecurity.UNKNOWN -> CapabilityNotice(
        tone = NoticeTone.Unsupported,
        headline = "Security unknown",
        cause = "The system did not report how this network is protected. Confirm the details before connecting.",
        actionLabel = "Open system settings",
        onAction = onOpenWifiSettings,
      )
      WifiSecurity.OWE -> {
        CapabilityNotice(
          tone = NoticeTone.Unsupported,
          headline = "Needs system settings",
          cause = "This enhanced open network cannot be joined from inside this app. Join it in system settings instead.",
          actionLabel = "Open system settings",
          onAction = onOpenWifiSettings,
        )
        Spacer(Modifier.height(12.dp))
        WhaleSecondaryButton("Share", onClick = onShare)
      }
      else -> {
        WhalePrimaryButton(if (connected) "Reconnect" else "Connect", onClick = onConnect)
        Spacer(Modifier.height(12.dp))
        WhaleSecondaryButton("Share", onClick = onShare)
      }
    }
    Spacer(Modifier.height(8.dp))
  }
}

/**
 * Explicit credential form. Owns secret visibility and validation; the
 * caller handles the network request and QR presentation.
 */
@Composable
fun CredentialFormSheet(
  title: String,
  headline: String,
  initialSsid: String,
  ssidEditable: Boolean,
  security: WifiSecurity,
  confirmLabel: String,
  note: String,
  onConfirm: (WifiCredentials) -> Unit,
) {
  var ssid by remember(initialSsid) { mutableStateOf(initialSsid) }
  var password by remember { mutableStateOf("") }
  var attempted by remember { mutableStateOf(false) }
  val ssidError = if (attempted) validateSsid(ssid).error else null
  val passwordError = if (attempted) validatePassword(password, security).error else null
  val needsPassword = security != WifiSecurity.OPEN && security != WifiSecurity.OWE &&
    security != WifiSecurity.WEP && security != WifiSecurity.ENTERPRISE

  Column(
    modifier = Modifier.fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .navigationBarsPadding()
      .imePadding()
      .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
  ) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(headline, style = MaterialTheme.typography.bodyMedium, color = WhaleSecondary, modifier = Modifier.padding(top = 2.dp))
    Spacer(Modifier.height(12.dp))
    if (ssidEditable) {
      WhaleTextField(value = ssid, onValueChange = { ssid = it }, label = "Network name", error = ssidError, maxLength = 32)
    } else {
      MetricRow("Network", ssid)
    }
    MetricRow("Security", securityLabel(security))
    if (needsPassword || security == WifiSecurity.UNKNOWN) {
      WhaleSecretField(
        value = password,
        onValueChange = { password = it },
        label = "Password",
        error = passwordError,
        helper = "8 to 63 characters. Stays on this screen until you confirm.",
      )
    }
    Text(note, style = MaterialTheme.typography.bodySmall, color = WhaleSecondary, modifier = Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(12.dp))
    WhalePrimaryButton(
      label = confirmLabel,
      onClick = {
        attempted = true
        if (validateSsid(ssid).error == null && validatePassword(password, security).error == null) {
          onConfirm(WifiCredentials(ssid = ssid.trim(), password = password, security = security))
        }
      },
    )
    Spacer(Modifier.height(8.dp))
  }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 820)
@Composable
private fun WifiPreviewFresh() {
  // Design fixture: static preview data only.
  val now = System.currentTimeMillis()
  WirelessWhaleTheme {
    Surface(color = WhaleBackground) {
      WifiScreen(
        wifi = WifiSnapshot(enabled = true, connected = true, ssid = "Home Fibre", bssid = "aa:bb:cc:dd:ee:ff"),
        networks = listOf(
          WifiNetwork("1", "Home Fibre", "aa:bb:cc:dd:ee:ff", -58, 5180, WifiSecurity.WPA2_PERSONAL, lastSeenAtMillis = now - 20_000),
          WifiNetwork("2", "Cafe Guest With A Very Long Name For Wrapping", "11:22:33:44:55:66", -71, 2412, WifiSecurity.OPEN, lastSeenAtMillis = now - 5 * 60_000),
          WifiNetwork("3", "Office Secure", "77:88:99:aa:bb:cc", -80, 5745, WifiSecurity.ENTERPRISE, lastSeenAtMillis = now - 40 * 60_000),
        ),
        scan = ScanState(lastScanAtMillis = now - 20_000),
        nowMillis = now,
        onRefreshNetworks = {}, onConnectNetwork = {}, onShareCredentials = {},
        onOpenScanner = {}, onOpenWifiSettings = {}, onRequestWifiPermission = {}, onOpenSettings = {},
      )
    }
  }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 820)
@Composable
private fun WifiPreviewOff() {
  // Design fixture: Wi-Fi off state.
  WirelessWhaleTheme {
    Surface(color = WhaleBackground) {
      WifiScreen(
        wifi = WifiSnapshot(enabled = false),
        networks = emptyList(),
        scan = ScanState(),
        nowMillis = System.currentTimeMillis(),
        onRefreshNetworks = {}, onConnectNetwork = {}, onShareCredentials = {},
        onOpenScanner = {}, onOpenWifiSettings = {}, onRequestWifiPermission = {}, onOpenSettings = {},
      )
    }
  }
}
