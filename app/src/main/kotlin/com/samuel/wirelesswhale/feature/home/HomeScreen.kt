package com.samuel.wirelesswhale.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.samuel.wirelesswhale.core.model.HistoryEntry
import com.samuel.wirelesswhale.core.model.HotspotSnapshot
import com.samuel.wirelesswhale.core.model.HotspotStatus
import com.samuel.wirelesswhale.core.model.InternetState
import com.samuel.wirelesswhale.core.model.ScanState
import com.samuel.wirelesswhale.core.model.WifiSnapshot
import com.samuel.wirelesswhale.navigation.WhaleRoute
import com.samuel.wirelesswhale.ui.clientCountLine
import com.samuel.wirelesswhale.ui.enhancedAccessLabel
import com.samuel.wirelesswhale.ui.formatAge
import com.samuel.wirelesswhale.ui.formatUpdatedStamp
import com.samuel.wirelesswhale.ui.hotspotTitle
import com.samuel.wirelesswhale.ui.internetLabel
import com.samuel.wirelesswhale.ui.signalBars
import com.samuel.wirelesswhale.ui.signalLabel
import com.samuel.wirelesswhale.ui.components.AppTopBar
import com.samuel.wirelesswhale.ui.components.CapabilityNotice
import com.samuel.wirelesswhale.ui.components.CardKicker
import com.samuel.wirelesswhale.ui.components.FreshnessLine
import com.samuel.wirelesswhale.ui.components.MetricRow
import com.samuel.wirelesswhale.ui.components.NoticeTone
import com.samuel.wirelesswhale.ui.components.SectionTitle
import com.samuel.wirelesswhale.ui.components.SignalBars
import com.samuel.wirelesswhale.ui.components.StatusChip
import com.samuel.wirelesswhale.ui.components.ChipTone
import com.samuel.wirelesswhale.ui.components.SummaryCard
import com.samuel.wirelesswhale.ui.components.WhalePrimaryButton
import com.samuel.wirelesswhale.ui.components.WhaleSecondaryButton
import com.samuel.wirelesswhale.ui.components.WhaleMark
import com.samuel.wirelesswhale.ui.theme.WhaleBackground
import com.samuel.wirelesswhale.ui.theme.WhaleInk
import com.samuel.wirelesswhale.ui.theme.WhaleSecondary
import com.samuel.wirelesswhale.ui.theme.WirelessWhaleTheme

/** Minimal QR mark: three finder squares. Action affordance, not a code. */
@Composable
private fun QrMark(tint: Color = Color.White, modifier: Modifier = Modifier) {
  Canvas(modifier = modifier.size(22.dp)) {
    val s = size.width / 22f
    val stroke = Stroke(width = 1.8f * s)
    drawRect(tint, topLeft = androidx.compose.ui.geometry.Offset(2f * s, 2f * s), size = androidx.compose.ui.geometry.Size(7f * s, 7f * s), style = stroke)
    drawRect(tint, topLeft = androidx.compose.ui.geometry.Offset(13f * s, 2f * s), size = androidx.compose.ui.geometry.Size(7f * s, 7f * s), style = stroke)
    drawRect(tint, topLeft = androidx.compose.ui.geometry.Offset(2f * s, 13f * s), size = androidx.compose.ui.geometry.Size(7f * s, 7f * s), style = stroke)
    drawCircle(tint, radius = 1.4f * s, center = androidx.compose.ui.geometry.Offset(16.5f * s, 16.5f * s))
    drawCircle(tint, radius = 1.4f * s, center = androidx.compose.ui.geometry.Offset(13.5f * s, 13.5f * s))
  }
}

@Composable
fun HomeScreen(
  wifi: WifiSnapshot,
  scan: ScanState,
  hotspot: HotspotSnapshot,
  recentHistory: List<HistoryEntry>,
  nowMillis: Long,
  onOpenScanner: () -> Unit,
  onOpenWifiSettings: () -> Unit,
  onRequestWifiPermission: () -> Unit,
  onRefreshNetworks: () -> Unit,
  onToggleHotspot: (Boolean) -> Unit,
  onOpenHotspotSettings: () -> Unit,
  onRunDiagnostics: () -> Unit,
  onOpenSettings: () -> Unit,
  onNavigate: (WhaleRoute) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 116.dp),
  ) {
    item {
      AppTopBar(
        title = "Home",
        subtitle = enhancedAccessLabel(hotspot.enhancedAccess),
        showSettings = true,
        onSettings = onOpenSettings,
        horizontalPadding = 0.dp,
      )
    }
    item {
      Row(verticalAlignment = Alignment.CenterVertically) {
        WhaleMark()
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
          Text("Wireless Whale", style = MaterialTheme.typography.titleLarge)
          Text(
            "Real observations only. Unknown means unknown.",
            style = MaterialTheme.typography.bodySmall,
            color = WhaleSecondary,
          )
        }
      }
      Spacer(Modifier.height(16.dp))
    }
    item {
      SectionTitle("Current connection")
      SummaryCard {
        CardKicker("Current connection")
        val name = wifi.ssid
        Text(
          text = name ?: "Network unknown",
          style = MaterialTheme.typography.headlineMedium,
          modifier = Modifier.padding(top = 6.dp),
        )
        val stateLine = when {
          wifi.permissionRequired || !wifi.locationEnabled ->
            "Hidden by system permission. Location access is needed to read network names."
          !wifi.enabled -> "Wi-Fi is off."
          !wifi.connected -> "Not connected to Wi-Fi."
          else -> internetLabel(wifi.internet)
        }
        Text(stateLine, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        if (wifi.connected && wifi.rssiDbm != null) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 12.dp),
          ) {
            SignalBars(level = signalBars(wifi.rssiDbm))
            Text(
              "${signalLabel(wifi.rssiDbm)} (${wifi.rssiDbm} dBm)",
              style = MaterialTheme.typography.bodySmall,
              color = WhaleSecondary,
              modifier = Modifier.padding(start = 8.dp),
            )
          }
        } else if (wifi.connected) {
          Text(
            "Signal reading unavailable for this connection.",
            style = MaterialTheme.typography.bodySmall,
            color = WhaleSecondary,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
        FreshnessLine(formatUpdatedStamp(wifi.lastUpdatedAtMillis, nowMillis))
        if (wifi.message != null) {
          Text(
            wifi.message,
            style = MaterialTheme.typography.bodySmall,
            color = WhaleSecondary,
            modifier = Modifier.padding(top = 4.dp),
          )
        }
      }
      Spacer(Modifier.height(12.dp))
    }
    if (wifi.permissionRequired || !wifi.locationEnabled) {
      item {
        CapabilityNotice(
          tone = NoticeTone.Setup,
          headline = "Location permission needed",
          cause = "Android only shares network names and scan results when location permission is granted and location is on.",
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
          cause = "Turn Wi-Fi on to see the current connection and nearby networks.",
          actionLabel = "Open Wi-Fi settings",
          onAction = onOpenWifiSettings,
        )
        Spacer(Modifier.height(12.dp))
      }
    }
    item {
      SectionTitle("Actions")
      // Prominent Scan QR action first.
      Surface(
        onClick = onOpenScanner,
        shape = RoundedCornerShape(18.dp),
        color = WhaleInk,
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          QrMark()
          Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text("Scan QR", style = MaterialTheme.typography.labelLarge, color = Color.White)
            Text(
              "Point at a Wi-Fi QR to review it before joining",
              style = MaterialTheme.typography.bodySmall,
              color = Color.White.copy(alpha = 0.78f),
            )
          }
        }
      }
      Spacer(Modifier.height(12.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
          WhaleSecondaryButton("Refresh", onClick = onRefreshNetworks)
        }
        Column(Modifier.weight(1f)) {
          WhaleSecondaryButton("Wi-Fi settings", onClick = onOpenWifiSettings)
        }
      }
      if (scan.throttled) {
        Text(
          "Scan slowed by the system. Waiting clears this automatically.",
          style = MaterialTheme.typography.bodySmall,
          color = WhaleSecondary,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
      scan.message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = WhaleSecondary, modifier = Modifier.padding(top = 4.dp))
      }
    }
    item {
      SectionTitle("Hotspot")
      SummaryCard {
        CardKicker("Hotspot session")
        Text(hotspotTitle(hotspot.status), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 6.dp))
        Text(
          clientCountLine(hotspot.clients?.size),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(top = 4.dp),
        )
        if (!hotspot.clientUsageAvailable && hotspot.status == HotspotStatus.ON) {
          Text(
            "Per-client usage is unavailable on this backend. No estimates are shown.",
            style = MaterialTheme.typography.bodySmall,
            color = WhaleSecondary,
            modifier = Modifier.padding(top = 4.dp),
          )
        }
        hotspot.message?.let {
          Text(it, style = MaterialTheme.typography.bodySmall, color = WhaleSecondary, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(12.dp))
        if (hotspot.canToggle && (hotspot.status == HotspotStatus.OFF || hotspot.status == HotspotStatus.ON)) {
          if (hotspot.status == HotspotStatus.ON) {
            WhaleSecondaryButton("Stop hotspot", onClick = { onToggleHotspot(false) })
          } else {
            WhalePrimaryButton("Start hotspot", onClick = { onToggleHotspot(true) })
          }
        } else {
          WhaleSecondaryButton("Hotspot controls", onClick = onOpenHotspotSettings)
        }
      }
    }
    item {
      SectionTitle("Recent activity")
      val recent = recentHistory.take(3)
      if (recent.isEmpty()) {
        Text(
          "No recent sessions yet. Run a connection check to record the first entry.",
          style = MaterialTheme.typography.bodyMedium,
          color = WhaleSecondary,
        )
        Spacer(Modifier.height(12.dp))
        WhaleSecondaryButton("Run check", onClick = { onNavigate(WhaleRoute.ConnectionCheck) })
      } else {
        SummaryCard {
          recent.forEachIndexed { index, entry ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            Text(entry.title, style = MaterialTheme.typography.bodyMedium)
            Text(
              formatAge(entry.atMillis, nowMillis) ?: entry.detail,
              style = MaterialTheme.typography.bodySmall,
              color = WhaleSecondary,
            )
          }
        }
        Spacer(Modifier.height(12.dp))
        // Navigate to the results screen as well as triggering the run,
        // so the check never executes in the background with no visible
        // results while the user stays on Home.
        WhaleSecondaryButton("Run check", onClick = { onNavigate(WhaleRoute.ConnectionCheck); onRunDiagnostics() })
      }
    }
    item {
      SectionTitle("Connection details")
      SummaryCard {
        MetricRow("Link speed", wifi.linkSpeedMbps?.let { "$it Mbps" }, mono = true)
        MetricRow("IPv4", wifi.ipv4.firstOrNull(), mono = true)
        MetricRow("Gateway", wifi.gateway, mono = true)
        MetricRow(
          "DNS",
          wifi.dnsServers.firstOrNull(),
          mono = true,
          unavailableCause = if (wifi.dnsServers.isEmpty() && wifi.connected) "Not reported by the system." else null,
        )
        MetricRow("Metered", wifi.metered?.let { if (it) "Yes" else "No" })
        MetricRow("VPN active", if (wifi.vpnActive) "Yes" else null)
      }
      Row(modifier = Modifier.padding(top = 12.dp)) {
        StatusChip(
          tone = when (wifi.internet) {
            InternetState.VALIDATED -> ChipTone.Live
            InternetState.CAPTIVE_PORTAL, InternetState.LOCAL_ONLY -> ChipTone.Warning
            InternetState.DISCONNECTED -> ChipTone.Neutral
            InternetState.UNKNOWN -> ChipTone.Neutral
          },
          text = internetLabel(wifi.internet),
        )
      }
    }
  }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 820)
@Composable
private fun HomePreviewConnected() {
  // Design fixture: static preview data only.
  WirelessWhaleTheme {
    Surface(color = WhaleBackground) {
      HomeScreen(
        wifi = WifiSnapshot(
          enabled = true,
          connected = true,
          ssid = "Home Fibre",
          rssiDbm = -62,
          linkSpeedMbps = 240,
          ipv4 = listOf("192.168.1.42"),
          gateway = "192.168.1.1",
          dnsServers = listOf("8.8.8.8"),
          internet = InternetState.VALIDATED,
          lastUpdatedAtMillis = System.currentTimeMillis() - 4 * 60 * 1000,
        ),
        scan = ScanState(),
        hotspot = HotspotSnapshot(status = HotspotStatus.ON, clients = emptyList()),
        recentHistory = emptyList(),
        nowMillis = System.currentTimeMillis(),
        onOpenScanner = {}, onOpenWifiSettings = {}, onRequestWifiPermission = {},
        onRefreshNetworks = {}, onToggleHotspot = {}, onOpenHotspotSettings = {},
        onRunDiagnostics = {}, onOpenSettings = {}, onNavigate = {},
      )
    }
  }
}

@Preview(showBackground = true, widthDp = 320, heightDp = 700)
@Composable
private fun HomePreviewDenied() {
  // Design fixture: permission denied state.
  WirelessWhaleTheme {
    Surface(color = WhaleBackground) {
      HomeScreen(
        wifi = WifiSnapshot(permissionRequired = true, locationEnabled = false),
        scan = ScanState(permissionRequired = true, locationRequired = true),
        hotspot = HotspotSnapshot(status = HotspotStatus.UNKNOWN),
        recentHistory = emptyList(),
        nowMillis = System.currentTimeMillis(),
        onOpenScanner = {}, onOpenWifiSettings = {}, onRequestWifiPermission = {},
        onRefreshNetworks = {}, onToggleHotspot = {}, onOpenHotspotSettings = {},
        onRunDiagnostics = {}, onOpenSettings = {}, onNavigate = {},
      )
    }
  }
}
