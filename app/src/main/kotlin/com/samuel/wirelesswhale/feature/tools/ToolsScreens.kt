package com.samuel.wirelesswhale.feature.tools

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.samuel.wirelesswhale.core.model.CheckStatus
import com.samuel.wirelesswhale.core.model.DiagnosticReport
import com.samuel.wirelesswhale.core.model.DiagnosticStep
import com.samuel.wirelesswhale.core.model.DiscoveredDevice
import com.samuel.wirelesswhale.core.model.HistoryEntry
import com.samuel.wirelesswhale.navigation.WhaleRoute
import com.samuel.wirelesswhale.ui.checkStatusLabel
import com.samuel.wirelesswhale.ui.formatAge
import com.samuel.wirelesswhale.ui.components.AppTopBar
import com.samuel.wirelesswhale.ui.components.CapabilityNotice
import com.samuel.wirelesswhale.ui.components.ChipTone
import com.samuel.wirelesswhale.ui.components.MetricRow
import com.samuel.wirelesswhale.ui.components.NoticeTone
import com.samuel.wirelesswhale.ui.components.SectionTitle
import com.samuel.wirelesswhale.ui.components.SettingRow
import com.samuel.wirelesswhale.ui.components.StatusChip
import com.samuel.wirelesswhale.ui.components.SummaryCard
import com.samuel.wirelesswhale.ui.components.WhaleEmptyState
import com.samuel.wirelesswhale.ui.components.WhalePrimaryButton
import com.samuel.wirelesswhale.ui.components.WhaleSecondaryButton
import com.samuel.wirelesswhale.ui.theme.WhaleAccent
import com.samuel.wirelesswhale.ui.theme.WhaleBackground
import com.samuel.wirelesswhale.ui.theme.WhaleBorder
import com.samuel.wirelesswhale.ui.theme.WhaleSecondary
import com.samuel.wirelesswhale.ui.theme.WirelessWhaleTheme

@Composable
fun ToolsScreen(
  report: DiagnosticReport,
  diagnosticRunning: Boolean,
  discoveredDevices: List<DiscoveredDevice>,
  discoveryRunning: Boolean,
  history: List<HistoryEntry>,
  onNavigate: (WhaleRoute) -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 116.dp),
  ) {
    item {
      AppTopBar(
        title = "Tools",
        subtitle = "Checks run only when you start them",
        showSettings = true,
        onSettings = onOpenSettings,
        horizontalPadding = 0.dp,
      )
    }
    item {
      SectionTitle("Diagnose")
      SummaryCard {
        SettingRow(
          title = "Connection check",
          detail = lastCheckLine(report),
          onClick = { onNavigate(WhaleRoute.ConnectionCheck) },
        )
        SettingRow(
          title = "LAN discovery",
          detail = if (discoveryRunning) "Listening for nearby services" else "${discoveredDevices.size} responsive devices remembered",
          onClick = { onNavigate(WhaleRoute.LanDiscovery) },
        )
      }
    }
    item {
      SectionTitle("Observe")
      SummaryCard {
        SettingRow(
          title = "Signal history",
          detail = "Unavailable in this slice. Samples need a recording source that does not exist yet.",
          onClick = null,
        )
        SettingRow(
          title = "Channel observations",
          detail = "Unavailable in this slice. Channel data needs a proven scan source.",
          onClick = null,
        )
        SettingRow(
          title = "History",
          detail = if (history.isEmpty()) "Nothing recorded yet" else "${history.size} entries kept on this device",
          onClick = { onNavigate(WhaleRoute.History) },
        )
      }
      Text(
        "Each result separates what was observed from what it means. Nothing is estimated.",
        style = MaterialTheme.typography.bodySmall,
        color = WhaleSecondary,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}

private fun lastCheckLine(report: DiagnosticReport): String {
  if (report.steps.isEmpty()) return "Never run on this device"
  val failed = report.steps.count { it.status == CheckStatus.FAILED }
  val warnings = report.steps.count { it.status == CheckStatus.WARNING }
  return when {
    report.cancelled -> "Last run canceled partway"
    failed > 0 -> "$failed of ${report.steps.size} checks failed"
    warnings > 0 -> "$warnings of ${report.steps.size} checks need a look"
    else -> "Last run passed ${report.steps.size} checks"
  }
}

@Composable
fun ConnectionCheckScreen(
  report: DiagnosticReport,
  running: Boolean,
  onRunDiagnostics: () -> Unit,
  onCancelDiagnostics: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 116.dp),
  ) {
    item {
      AppTopBar(title = "Connection check", onBack = onBack, horizontalPadding = 0.dp)
      Text(
        "Runs bounded local checks plus one external HTTPS probe. Nothing starts until you tap Run check.",
        style = MaterialTheme.typography.bodyMedium,
        color = WhaleSecondary,
      )
      MetricRow("External probe", "https://connectivitycheck.gstatic.com/generate_204", mono = true)
      Spacer(Modifier.height(8.dp))
      if (running) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        WhaleSecondaryButton("Cancel", onClick = onCancelDiagnostics)
      } else {
        WhalePrimaryButton(
          if (report.cancelled) "Run again" else "Run check",
          onClick = onRunDiagnostics,
        )
      }
      Spacer(Modifier.height(8.dp))
    }
    if (report.steps.isEmpty() && !running) {
      item {
        SummaryCard {
          Text("No results yet", style = MaterialTheme.typography.titleMedium)
          Text(
            "Results will appear here after you run the check.",
            style = MaterialTheme.typography.bodyMedium,
            color = WhaleSecondary,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
      }
    } else {
      if (report.cancelled && !running) {
        item {
          SummaryCard {
            Text("Run canceled", style = MaterialTheme.typography.titleMedium)
            Text(
              "Steps below the cancel point never ran. Results are partial, not a verdict.",
              style = MaterialTheme.typography.bodyMedium,
              color = WhaleSecondary,
              modifier = Modifier.padding(top = 8.dp),
            )
          }
          Spacer(Modifier.height(12.dp))
        }
      }
      items(report.steps, key = { it.id }) { step ->
        DiagnosticStepRow(step = step)
      }
    }
  }
}

@Composable
private fun DiagnosticStepRow(step: DiagnosticStep) {
  val (icon, tint, tone) = when (step.status) {
    CheckStatus.PASSED -> Triple(Icons.Filled.Check, WhaleAccent, ChipTone.Live)
    CheckStatus.FAILED -> Triple(Icons.Filled.Warning, com.samuel.wirelesswhale.ui.theme.WhaleDestructive, ChipTone.Error)
    CheckStatus.WARNING -> Triple(Icons.Filled.Warning, com.samuel.wirelesswhale.ui.theme.WhaleWarning, ChipTone.Warning)
    CheckStatus.RUNNING -> Triple(Icons.Filled.Info, WhaleAccent, ChipTone.Neutral)
    CheckStatus.WAITING -> Triple(Icons.Filled.Info, WhaleSecondary, ChipTone.Neutral)
  }
  Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
      Text(
        step.title,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(1f).padding(start = 10.dp),
      )
      StatusChip(tone = tone, text = checkStatusLabel(step.status))
    }
    if (step.detail.isNotEmpty()) {
      Text(
        step.detail,
        style = MaterialTheme.typography.bodySmall,
        color = WhaleSecondary,
        modifier = Modifier.padding(start = 32.dp, top = 4.dp),
      )
    }
    if (step.durationMs != null) {
      Text(
        "${step.durationMs} ms",
        style = MaterialTheme.typography.labelMedium,
        color = WhaleSecondary,
        modifier = Modifier.padding(start = 32.dp, top = 2.dp),
      )
    }
  }
  HorizontalDivider(color = WhaleBorder, thickness = 1.dp)
}

@Composable
fun LanDiscoveryScreen(
  devices: List<DiscoveredDevice>,
  running: Boolean,
  onStartDiscovery: () -> Unit,
  onStopDiscovery: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 116.dp),
  ) {
    item {
      AppTopBar(title = "LAN discovery", onBack = onBack, horizontalPadding = 0.dp)
      Text(
        "Listens for scoped local service announcements on the network you are already on. It never enumerates every client and never proves who uses your hotspot.",
        style = MaterialTheme.typography.bodyMedium,
        color = WhaleSecondary,
      )
      Spacer(Modifier.height(12.dp))
      if (running) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          androidx.compose.foundation.Canvas(modifier = Modifier.size(14.dp)) {
            drawRoundRect(WhaleAccent, cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f))
          }
          Text("Listening", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp).weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        WhaleSecondaryButton("Stop", onClick = onStopDiscovery)
      } else {
        WhalePrimaryButton("Start discovery", onClick = onStartDiscovery)
      }
      Spacer(Modifier.height(8.dp))
    }
    if (devices.isEmpty() && !running) {
      item {
        WhaleEmptyState(
          title = "No devices heard yet",
          reason = "Start discovery while on a local network. Only devices that answer appear here.",
          actionLabel = "Start discovery",
          onAction = onStartDiscovery,
        )
      }
    } else {
      item {
        SectionTitle("Responsive devices (${devices.size})")
      }
      items(devices, key = { it.id }) { device ->
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
  }
}

@Composable
fun HistoryScreen(
  history: List<HistoryEntry>,
  nowMillis: Long,
  onClearHistory: () -> Unit,
  onExportHistory: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var confirmClear by remember { mutableStateOf(false) }
  LazyColumn(
    modifier = modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 116.dp),
  ) {
    item {
      AppTopBar(title = "History", onBack = onBack, horizontalPadding = 0.dp)
      Text(
        "Sessions and results kept on this device. Secrets are redacted before anything is stored or exported.",
        style = MaterialTheme.typography.bodyMedium,
        color = WhaleSecondary,
      )
      Spacer(Modifier.height(12.dp))
      if (history.isNotEmpty()) {
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
          Column(Modifier.weight(1f)) { WhaleSecondaryButton("Export redacted", onClick = onExportHistory) }
          Column(Modifier.weight(1f)) { WhaleSecondaryButton("Review then clear", onClick = { confirmClear = true }) }
        }
        Spacer(Modifier.height(8.dp))
      }
    }
    if (history.isEmpty()) {
      item {
        WhaleEmptyState(
          title = "History is empty",
          reason = "Connection checks and hotspot sessions record here once they happen.",
          actionLabel = "Back to tools",
          onAction = onBack,
        )
      }
    } else {
      items(history, key = { it.id }) { entry ->
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
          Text(entry.title, style = MaterialTheme.typography.bodyMedium)
          Text(entry.detail, style = MaterialTheme.typography.bodySmall, color = WhaleSecondary)
          Text(
            formatAge(entry.atMillis, nowMillis) ?: "Time unknown",
            style = MaterialTheme.typography.labelMedium,
            color = WhaleSecondary,
            modifier = Modifier.padding(top = 2.dp),
          )
        }
        HorizontalDivider(color = WhaleBorder, thickness = 1.dp)
      }
    }
  }
  if (confirmClear) {
    AlertDialog(
      onDismissRequest = { confirmClear = false },
      title = { Text("Clear ${history.size} entries?") },
      text = { Text("This removes local history from this device. It cannot be undone.") },
      confirmButton = {
        TextButton(onClick = { confirmClear = false; onClearHistory() }) { Text("Clear history") }
      },
      dismissButton = {
        TextButton(onClick = { confirmClear = false }) { Text("Keep") }
      },
    )
  }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 820)
@Composable
private fun ToolsPreview() {
  // Design fixture: static preview data only.
  WirelessWhaleTheme {
    Surface(color = WhaleBackground) {
      ToolsScreen(
        report = DiagnosticReport(
          steps = listOf(
            DiagnosticStep("link", "Local link", CheckStatus.PASSED, "Connected to Home Fibre.", 120),
            DiagnosticStep("dns", "DNS", CheckStatus.WARNING, "Slow answers from 8.8.8.8.", 900),
            DiagnosticStep("https", "Internet", CheckStatus.WAITING),
          ),
        ),
        diagnosticRunning = false,
        discoveredDevices = listOf(DiscoveredDevice("d1", "Printer", "192.168.1.20", 631, "_ipp._tcp")),
        discoveryRunning = false,
        history = listOf(HistoryEntry("h1", "Connection check", "2 of 5 checks failed", System.currentTimeMillis() - 3600_000, "diagnostics")),
        onNavigate = {},
        onOpenSettings = {},
      )
    }
  }
}
