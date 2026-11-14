package com.samuel.wirelesswhale.feature.hotspot

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.samuel.wirelesswhale.core.model.DiscoveredDevice
import com.samuel.wirelesswhale.core.model.EnhancedAccessState
import com.samuel.wirelesswhale.core.model.HotspotClient
import com.samuel.wirelesswhale.core.model.HotspotSnapshot
import com.samuel.wirelesswhale.core.model.HotspotStatus
import com.samuel.wirelesswhale.navigation.WhaleRoute
import com.samuel.wirelesswhale.ui.clientCountLine
import com.samuel.wirelesswhale.ui.enhancedAccessLabel
import com.samuel.wirelesswhale.ui.formatAge
import com.samuel.wirelesswhale.ui.formatUpdatedStamp
import com.samuel.wirelesswhale.ui.hotspotTitle
import com.samuel.wirelesswhale.ui.components.AppTopBar
import com.samuel.wirelesswhale.ui.components.CapabilityNotice
import com.samuel.wirelesswhale.ui.components.CardKicker
import com.samuel.wirelesswhale.ui.components.ChipTone
import com.samuel.wirelesswhale.ui.components.FreshnessLine
import com.samuel.wirelesswhale.ui.components.MetricRow
import com.samuel.wirelesswhale.ui.components.NoticeTone
import com.samuel.wirelesswhale.ui.components.SectionTitle
import com.samuel.wirelesswhale.ui.components.StatusChip
import com.samuel.wirelesswhale.ui.components.SummaryCard
import com.samuel.wirelesswhale.ui.components.WhaleEmptyState
import com.samuel.wirelesswhale.ui.components.WhaleDestructiveButton
import com.samuel.wirelesswhale.ui.components.WhalePrimaryButton
import com.samuel.wirelesswhale.ui.components.WhaleSecondaryButton
import com.samuel.wirelesswhale.ui.theme.WhaleAccent
import com.samuel.wirelesswhale.ui.theme.WhaleBackground
import com.samuel.wirelesswhale.ui.theme.WhaleBorder
import com.samuel.wirelesswhale.ui.theme.WhaleInk
import com.samuel.wirelesswhale.ui.theme.WhaleSecondary
import com.samuel.wirelesswhale.ui.theme.WhaleSubtle
import com.samuel.wirelesswhale.ui.theme.WirelessWhaleTheme

@Composable
fun HotspotScreen(
  hotspot: HotspotSnapshot,
  lanDevices: List<DiscoveredDevice>,
  nowMillis: Long,
  onToggleHotspot: (Boolean) -> Unit,
  onOpenHotspotSettings: () -> Unit,
  onRequestEnhancedAccess: () -> Unit,
  onBlockClient: (String, Boolean) -> Unit,
  onOpenSettings: () -> Unit,
  onNavigate: (WhaleRoute) -> Unit,
  modifier: Modifier = Modifier,
) {
  var confirmStop by remember { mutableStateOf(false) }
  val online = hotspot.clients?.filter { it.connected } ?: emptyList()
  val remembered = hotspot.clients?.filter { !it.connected } ?: emptyList()

  LazyColumn(
    modifier = modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 116.dp),
  ) {
    item {
      AppTopBar(
        title = "Hotspot",
        subtitle = enhancedAccessLabel(hotspot.enhancedAccess),
        showSettings = true,
        onSettings = onOpenSettings,
        horizontalPadding = 0.dp,
      )
    }
    item {
      SectionTitle("Session")
      SummaryCard {
        CardKicker("Hotspot session")
        Text(hotspotTitle(hotspot.status), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 6.dp))
        Text(clientCountLine(hotspot.clients?.size), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        if (hotspot.ssid != null) {
          Text(
            "Name ${hotspot.ssid} · reported by ${hotspot.source}",
            style = MaterialTheme.typography.bodySmall,
            color = WhaleSecondary,
            modifier = Modifier.padding(top = 4.dp),
          )
        } else if (hotspot.status == HotspotStatus.ON) {
          Text(
            "Session name unknown. The system did not share it.",
            style = MaterialTheme.typography.bodySmall,
            color = WhaleSecondary,
            modifier = Modifier.padding(top = 4.dp),
          )
        }
        hotspot.security?.let {
          Text("Security $it", style = MaterialTheme.typography.bodySmall, color = WhaleSecondary)
        }
        FreshnessLine(formatUpdatedStamp(hotspot.lastUpdatedAtMillis, nowMillis))
        hotspot.message?.let {
          Text(it, style = MaterialTheme.typography.bodySmall, color = WhaleSecondary, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(12.dp))
        when (hotspot.status) {
          HotspotStatus.ON -> {
            if (hotspot.canToggle) {
              WhaleSecondaryButton("Stop hotspot", onClick = { confirmStop = true })
            } else {
              WhaleSecondaryButton("Hotspot controls", onClick = onOpenHotspotSettings)
              Text(
                "Stopping from here is not supported on this backend. Use system settings.",
                style = MaterialTheme.typography.bodySmall,
                color = WhaleSecondary,
                modifier = Modifier.padding(top = 8.dp),
              )
            }
          }
          HotspotStatus.OFF -> {
            if (hotspot.canToggle) {
              WhalePrimaryButton("Start hotspot", onClick = { onToggleHotspot(true) })
            } else {
              WhalePrimaryButton("Open hotspot settings", onClick = onOpenHotspotSettings)
            }
          }
          HotspotStatus.STARTING, HotspotStatus.STOPPING -> {
            StatusChip(ChipTone.Neutral, hotspotTitle(hotspot.status))
          }
          HotspotStatus.ERROR, HotspotStatus.UNKNOWN -> {
            WhaleSecondaryButton("Open hotspot settings", onClick = onOpenHotspotSettings)
          }
        }
      }
      Spacer(Modifier.height(12.dp))
    }
    if (hotspot.enhancedAccess != EnhancedAccessState.READY) {
      item {
        EnhancedAccessNotice(state = hotspot.enhancedAccess, onRequest = onRequestEnhancedAccess)
        Spacer(Modifier.height(12.dp))
      }
    }
    if (hotspot.status == HotspotStatus.ON) {
      if (hotspot.clients == null) {
        item {
          SectionTitle("Clients")
          CapabilityNotice(
            tone = NoticeTone.Unsupported,
            headline = "Client list unknown",
            cause = "The system confirmed the hotspot is on but did not report who is connected. Nothing is guessed here.",
            actionLabel = "Refresh",
            onAction = onOpenHotspotSettings,
          )
        }
      } else {
        if (online.isNotEmpty()) {
          item { SectionTitle("Currently connected") }
          items(online, key = { it.id }) { client ->
            ClientRow(
              client = client,
              nowMillis = nowMillis,
              usageAvailable = hotspot.clientUsageAvailable,
              onClick = { onNavigate(WhaleRoute.ClientDetail(client.id)) },
            )
          }
        }
        if (remembered.isNotEmpty()) {
          item { SectionTitle("Remembered") }
          items(remembered, key = { it.id }) { client ->
            ClientRow(
              client = client,
              nowMillis = nowMillis,
              usageAvailable = hotspot.clientUsageAvailable,
              onClick = { onNavigate(WhaleRoute.ClientDetail(client.id)) },
            )
          }
        }
        if (online.isEmpty() && remembered.isEmpty()) {
          item {
            SectionTitle("Clients")
            WhaleEmptyState(
              title = "No clients connected",
              reason = "The system reported an empty client list. New devices appear here when they join.",
              actionLabel = "Review hotspot profile",
              onAction = { onNavigate(WhaleRoute.HotspotProfile) },
            )
          }
        }
        if (!hotspot.clientUsageAvailable) {
          item {
            Text(
              "Per-client usage is unavailable on this backend. Usage rows stay marked unavailable instead of showing estimates.",
              style = MaterialTheme.typography.bodySmall,
              color = WhaleSecondary,
              modifier = Modifier.padding(top = 12.dp),
            )
          }
        }
      }
      if (lanDevices.isNotEmpty()) {
        item {
          SectionTitle("Nearby on this network")
          Text(
            "These devices answered local discovery. They are not hotspot clients.",
            style = MaterialTheme.typography.bodySmall,
            color = WhaleSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
          )
        }
        items(lanDevices, key = { it.id }) { device ->
          Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text(device.name ?: "Unknown device", style = MaterialTheme.typography.bodyMedium)
            Text(
              listOfNotNull(device.host, device.port?.let { "port $it" }, device.serviceType).joinToString(" · "),
              style = MaterialTheme.typography.labelMedium,
              color = WhaleSecondary,
            )
          }
          HorizontalDivider(color = WhaleBorder, thickness = 1.dp)
        }
      }
      item {
        SectionTitle("Limits")
        Text(
          "Per-client data allowances and speed caps are not supported on this backend. Reminders here never pretend to enforce.",
          style = MaterialTheme.typography.bodyMedium,
          color = WhaleSecondary,
        )
      }
    }
  }
  if (confirmStop) {
    AlertDialog(
      onDismissRequest = { confirmStop = false },
      title = { Text("Stop the hotspot?") },
      text = { Text("Connected devices lose their connection immediately.") },
      confirmButton = {
        TextButton(onClick = { confirmStop = false; onToggleHotspot(false) }) { Text("Stop hotspot") }
      },
      dismissButton = {
        TextButton(onClick = { confirmStop = false }) { Text("Keep running") }
      },
    )
  }
}

@Composable
private fun EnhancedAccessNotice(state: EnhancedAccessState, onRequest: () -> Unit) {
  when (state) {
    EnhancedAccessState.READY -> Unit
    EnhancedAccessState.NOT_INSTALLED -> CapabilityNotice(
      tone = NoticeTone.Setup,
      headline = "Standard access",
      cause = "Basic hotspot controls use system settings. Enhanced access adds direct control and client detail.",
      actionLabel = "Learn about enhanced access",
      onAction = onRequest,
    )
    EnhancedAccessState.NOT_RUNNING -> CapabilityNotice(
      tone = NoticeTone.Setup,
      headline = "Enhanced helper not running",
      cause = "The optional helper is installed but not running, so direct control is unavailable.",
      actionLabel = "Request access",
      onAction = onRequest,
    )
    EnhancedAccessState.PERMISSION_REQUIRED -> CapabilityNotice(
      tone = NoticeTone.Setup,
      headline = "Permission needed",
      cause = "The helper needs one more permission before it can manage the hotspot directly.",
      actionLabel = "Grant permission",
      onAction = onRequest,
    )
    EnhancedAccessState.CONNECTING -> CapabilityNotice(
      tone = NoticeTone.Setup,
      headline = "Connecting to helper",
      cause = "Access was granted. Waiting for the hotspot helper to answer; this clears on its own.",
      actionLabel = "Retry",
      onAction = onRequest,
    )
    EnhancedAccessState.HELPER_UNAVAILABLE -> CapabilityNotice(
      tone = NoticeTone.Lost,
      headline = "Helper unavailable",
      cause = "Access was granted but the hotspot helper did not start. Nothing was changed; retry or use system settings.",
      actionLabel = "Retry",
      onAction = onRequest,
    )
    EnhancedAccessState.REVOKED -> CapabilityNotice(
      tone = NoticeTone.Lost,
      headline = "Access lost",
      cause = "The helper bridge went away. Existing rows are stale until it is restored.",
      actionLabel = "Reconnect",
      onAction = onRequest,
    )
    EnhancedAccessState.UNSUPPORTED -> CapabilityNotice(
      tone = NoticeTone.Unsupported,
      headline = "Not supported here",
      cause = "This device has no supported path for enhanced hotspot control. System settings remain available.",
      actionLabel = "Got it",
      onAction = onRequest,
    )
  }
}

/** One observed client. Never invents a manufacturer or model for unknowns. */
@Composable
fun ClientRow(
  client: HotspotClient,
  nowMillis: Long,
  usageAvailable: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .padding(vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
      Text(
        text = client.name ?: "Unknown device",
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      val identity = listOfNotNull(client.macAddress, client.addresses.firstOrNull()).joinToString(" · ")
      if (identity.isNotEmpty()) {
        Text(identity, style = MaterialTheme.typography.labelMedium, color = WhaleSecondary)
      }
      val seen = formatAge(client.lastSeenAtMillis, nowMillis)
      Text(
        text = if (seen != null) "Last seen $seen" else "Presence unconfirmed",
        style = MaterialTheme.typography.bodySmall,
        color = WhaleSecondary,
      )
      if (!usageAvailable) {
        Text("Usage unavailable on this backend", style = MaterialTheme.typography.bodySmall, color = WhaleSecondary)
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Surface(
        shape = CircleShape,
        color = if (client.connected) WhaleAccent else WhaleSubtle,
        border = if (client.connected) null else BorderStroke(1.dp, WhaleBorder),
        modifier = Modifier.size(10.dp),
      ) {}
      Text(
        if (client.connected) "Online" else "Away",
        style = MaterialTheme.typography.bodySmall,
        color = WhaleSecondary,
        modifier = Modifier.padding(start = 6.dp, end = 4.dp),
      )
    }
  }
  HorizontalDivider(color = WhaleBorder, thickness = 1.dp)
}

@Composable
fun ClientDetailScreen(
  client: HotspotClient?,
  clientUsageAvailable: Boolean,
  canBlockClients: Boolean,
  onBlockClient: (String, Boolean) -> Unit,
  onOpenHotspotSettings: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var confirmBlock by remember { mutableStateOf(false) }
  LazyColumn(
    modifier = modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 116.dp),
  ) {
    item {
      AppTopBar(
        title = "Client",
        onBack = onBack,
        horizontalPadding = 0.dp,
      )
    }
    if (client == null) {
      item {
        WhaleEmptyState(
          title = "Client no longer listed",
          reason = "The system no longer reports this device. It may have left or changed its identifier.",
          actionLabel = "Back to hotspot",
          onAction = onBack,
        )
      }
      return@LazyColumn
    }
    item {
      SummaryCard {
        CardKicker("Observed identity")
        Text(client.name ?: "Unknown device", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 6.dp))
        Text(
          "A local name never renames the remote device. Unknown makers are never invented.",
          style = MaterialTheme.typography.bodySmall,
          color = WhaleSecondary,
          modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        MetricRow("Hardware address", client.macAddress, mono = true)
        MetricRow("Addresses", client.addresses.joinToString(", ").ifEmpty { null }, mono = true)
        MetricRow("State", if (client.connected) "Online" else "Remembered, not connected")
      }
      Spacer(Modifier.height(12.dp))
    }
    item {
      SectionTitle("Usage")
      SummaryCard {
        if (clientUsageAvailable) {
          MetricRow("Received", client.receivedBytes?.let { "$it bytes" }, mono = true)
          MetricRow("Transmitted", client.transmittedBytes?.let { "$it bytes" }, mono = true)
          Text(
            "Counts cover this accounting window only, where the backend proved them.",
            style = MaterialTheme.typography.bodySmall,
            color = WhaleSecondary,
          )
        } else {
          Text(
            "Usage unavailable on this backend. No verified counters exist for this client, so none are shown.",
            style = MaterialTheme.typography.bodyMedium,
            color = WhaleSecondary,
          )
        }
      }
    }
    item {
      SectionTitle("Identity changes")
      Text(
        "Randomized or changing hardware addresses can appear as new identities. Treat a reappearing unknown device as possibly the same visitor.",
        style = MaterialTheme.typography.bodyMedium,
        color = WhaleSecondary,
      )
    }
    item {
      SectionTitle("Limits")
      Text(
        "Data allowances and speed controls are unavailable until a supported backend proves them. Nothing here enforces a limit.",
        style = MaterialTheme.typography.bodyMedium,
        color = WhaleSecondary,
      )
    }
    item {
      Spacer(Modifier.height(16.dp))
      if (canBlockClients) {
        WhaleDestructiveButton(
          label = "Block ${client.name ?: "this device"}",
          onClick = { confirmBlock = true },
        )
      } else {
        CapabilityNotice(
          tone = NoticeTone.Unsupported,
          headline = "Blocking unavailable",
          cause = "This backend reports clients but does not support blocking them. Manage access in system settings.",
          actionLabel = "Open system settings",
          onAction = onOpenHotspotSettings,
        )
      }
    }
  }
  if (confirmBlock && client != null) {
    AlertDialog(
      onDismissRequest = { confirmBlock = false },
      title = { Text("Block ${client.name ?: "this device"}?") },
      text = { Text("It loses hotspot access until you unblock it.") },
      confirmButton = {
        TextButton(onClick = { confirmBlock = false; onBlockClient(client.id, true) }) { Text("Block") }
      },
      dismissButton = {
        TextButton(onClick = { confirmBlock = false }) { Text("Cancel") }
      },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotspotProfileSheet(
  hotspot: HotspotSnapshot,
  onOpenHotspotSettings: () -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
      Text("Hotspot profile", style = MaterialTheme.typography.titleLarge)
      Text(
        "Review only. Changes happen in system settings where the system owns the radio.",
        style = MaterialTheme.typography.bodyMedium,
        color = WhaleSecondary,
        modifier = Modifier.padding(top = 4.dp),
      )
      Spacer(Modifier.height(12.dp))
      SummaryCard {
        MetricRow("Name", hotspot.ssid)
        MetricRow("Security", hotspot.security)
        MetricRow(
          "Editing",
          if (hotspot.canConfigure) "Available in system settings" else null,
          unavailableCause = if (!hotspot.canConfigure) "Direct editing is not supported on this backend." else null,
        )
      }
      Spacer(Modifier.height(12.dp))
      Text(
        "Changing the name or password restarts the hotspot and disconnects everyone. The system confirms that before it happens.",
        style = MaterialTheme.typography.bodySmall,
        color = WhaleSecondary,
      )
      Spacer(Modifier.height(12.dp))
      WhalePrimaryButton("Open system settings", onClick = onOpenHotspotSettings)
      Spacer(Modifier.height(8.dp))
    }
  }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 820)
@Composable
private fun HotspotPreviewOn() {
  // Design fixture: two clearly labeled design fixture clients.
  val now = System.currentTimeMillis()
  WirelessWhaleTheme {
    Surface(color = WhaleBackground) {
      HotspotScreen(
        hotspot = HotspotSnapshot(
          status = HotspotStatus.ON,
          ssid = "Whale Spot",
          security = "WPA2",
          clients = listOf(
            HotspotClient("c1", macAddress = "aa:bb:cc:dd:ee:01", name = "Fixture Laptop", addresses = listOf("192.168.43.10"), connected = true, lastSeenAtMillis = now - 60_000),
            HotspotClient("c2", macAddress = null, name = null, addresses = emptyList(), connected = true, lastSeenAtMillis = now - 9 * 60_000),
          ),
          enhancedAccess = EnhancedAccessState.READY,
          canToggle = true,
          canBlockClients = true,
          clientUsageAvailable = false,
          lastUpdatedAtMillis = now - 60_000,
        ),
        lanDevices = listOf(DiscoveredDevice("d1", "Printer", "192.168.43.20", 631, "_ipp._tcp")),
        nowMillis = now,
        onToggleHotspot = {}, onOpenHotspotSettings = {}, onRequestEnhancedAccess = {},
        onBlockClient = { _, _ -> }, onOpenSettings = {}, onNavigate = {},
      )
    }
  }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 820)
@Composable
private fun HotspotPreviewLost() {
  // Design fixture: access lost plus unknown clients.
  WirelessWhaleTheme {
    Surface(color = WhaleBackground) {
      HotspotScreen(
        hotspot = HotspotSnapshot(
          status = HotspotStatus.ON,
          clients = null,
          enhancedAccess = EnhancedAccessState.REVOKED,
          canToggle = false,
          message = "The helper bridge went away.",
        ),
        lanDevices = emptyList(),
        nowMillis = System.currentTimeMillis(),
        onToggleHotspot = {}, onOpenHotspotSettings = {}, onRequestEnhancedAccess = {},
        onBlockClient = { _, _ -> }, onOpenSettings = {}, onNavigate = {},
      )
    }
  }
}
