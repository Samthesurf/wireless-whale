package com.samuel.wirelesswhale.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.samuel.wirelesswhale.core.model.EnhancedAccessState
import com.samuel.wirelesswhale.core.model.HotspotSnapshot
import com.samuel.wirelesswhale.navigation.WhaleRoute
import com.samuel.wirelesswhale.ui.enhancedAccessLabel
import com.samuel.wirelesswhale.ui.components.AppTopBar
import com.samuel.wirelesswhale.ui.components.CapabilityNotice
import com.samuel.wirelesswhale.ui.components.MetricRow
import com.samuel.wirelesswhale.ui.components.NoticeTone
import com.samuel.wirelesswhale.ui.components.SectionTitle
import com.samuel.wirelesswhale.ui.components.SettingRow
import com.samuel.wirelesswhale.ui.components.SummaryCard
import com.samuel.wirelesswhale.ui.components.WhalePrimaryButton
import com.samuel.wirelesswhale.ui.components.WhaleSecondaryButton
import com.samuel.wirelesswhale.ui.theme.WhaleBackground
import com.samuel.wirelesswhale.ui.theme.WhaleSecondary
import com.samuel.wirelesswhale.ui.theme.WirelessWhaleTheme

@Composable
fun SettingsScreen(
  hotspot: HotspotSnapshot,
  onRequestEnhancedAccess: () -> Unit,
  onNavigate: (WhaleRoute) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 116.dp),
  ) {
    item {
      AppTopBar(title = "Settings", onBack = onBack, horizontalPadding = 0.dp)
    }
    item {
      SectionTitle("Access")
      SummaryCard {
        SettingRow(
          title = "Hotspot control",
          detail = enhancedAccessLabel(hotspot.enhancedAccess),
          onClick = { onNavigate(WhaleRoute.AccessSettings) },
        )
        SettingRow(
          title = "Quick Settings tiles",
          detail = "Scan QR and hotspot, added from system settings",
          onClick = { onNavigate(WhaleRoute.QuickSettingsHelp) },
        )
      }
    }
    item {
      SectionTitle("Privacy")
      SummaryCard {
        MetricRow(
          "Location",
          "Used only to read network names and scans",
          unavailableCause = null,
        )
        MetricRow("Passwords", "Never logged or stored by these screens")
        MetricRow("QR payloads", "Redacted before storage or export")
      }
      Text(
        "This app never logs passwords, complete QR credential payloads, or saved network dumps.",
        style = MaterialTheme.typography.bodySmall,
        color = WhaleSecondary,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
    item {
      SectionTitle("Alerts and units")
      SummaryCard {
        SettingRow(title = "Alerts", detail = "Off. This slice defines no alert rules, so there is nothing to toggle.")
        SettingRow(title = "Units", detail = "Signal in dBm, speed in Mbps. Fixed in this slice.")
      }
    }
    item {
      SectionTitle("Storage")
      SummaryCard {
        SettingRow(title = "Local history", detail = "Bounded list on this device. Oldest entries drop off past the cap.")
        SettingRow(title = "Encryption", detail = "Not claimed. Treat this store as plain local storage.")
      }
    }
    item {
      SectionTitle("About")
      SummaryCard {
        SettingRow(title = "Licenses", detail = "Geist font, SIL Open Font License 1.1", onClick = { onNavigate(WhaleRoute.Licenses) })
        SettingRow(title = "Version", detail = "Slice build. Capabilities report their real state per screen.")
      }
    }
  }
}

@Composable
fun AccessSettingsScreen(
  hotspot: HotspotSnapshot,
  onRequestEnhancedAccess: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 116.dp),
  ) {
    item {
      AppTopBar(title = "Access", onBack = onBack, horizontalPadding = 0.dp)
      Text(
        "Standard control always uses system settings. Enhanced access is an optional helper, never required.",
        style = MaterialTheme.typography.bodyMedium,
        color = WhaleSecondary,
      )
      Spacer(Modifier.height(12.dp))
    }
    item {
      SummaryCard {
        MetricRow("Current", enhancedAccessLabel(hotspot.enhancedAccess))
        MetricRow("Direct toggle", if (hotspot.canToggle) "Supported" else null,
          unavailableCause = if (!hotspot.canToggle) "This backend hands toggling to system settings." else null)
        MetricRow("Direct client detail", if (hotspot.enhancedAccess == EnhancedAccessState.READY) "Supported" else null,
          unavailableCause = if (hotspot.enhancedAccess != EnhancedAccessState.READY) "Needs the helper described below." else null)
      }
      Spacer(Modifier.height(12.dp))
    }
    item {
      when (hotspot.enhancedAccess) {
        EnhancedAccessState.READY -> CapabilityNotice(
          tone = NoticeTone.Setup,
          headline = "Enhanced access is ready",
          cause = "Direct hotspot control and client detail are available alongside system settings.",
          actionLabel = "Open hotspot",
          onAction = onBack,
        )
        EnhancedAccessState.NOT_INSTALLED -> {
          Text(
            "The helper runs as shell user 2000 and is set up explicitly. Nothing installs itself.",
            style = MaterialTheme.typography.bodyMedium,
            color = WhaleSecondary,
          )
          Spacer(Modifier.height(12.dp))
          WhalePrimaryButton("Request enhanced access", onClick = onRequestEnhancedAccess)
        }
        EnhancedAccessState.NOT_RUNNING -> {
          Text(
            "The helper is known but not running. Start it explicitly before direct control works.",
            style = MaterialTheme.typography.bodyMedium,
            color = WhaleSecondary,
          )
          Spacer(Modifier.height(12.dp))
          WhalePrimaryButton("Start request", onClick = onRequestEnhancedAccess)
        }
        EnhancedAccessState.PERMISSION_REQUIRED -> CapabilityNotice(
          tone = NoticeTone.Setup,
          headline = "One permission missing",
          cause = "The helper is present but the system still withholds the grant it needs.",
          actionLabel = "Grant permission",
          onAction = onRequestEnhancedAccess,
        )
        EnhancedAccessState.CONNECTING -> CapabilityNotice(
          tone = NoticeTone.Setup,
          headline = "Connecting to helper",
          cause = hotspot.message
            ?: "Access was granted. Waiting for the hotspot helper to answer; this clears on its own.",
          actionLabel = "Retry",
          onAction = onRequestEnhancedAccess,
        )
        EnhancedAccessState.HELPER_UNAVAILABLE -> CapabilityNotice(
          tone = NoticeTone.Lost,
          headline = "Helper unavailable",
          cause = hotspot.message
            ?: "Access was granted but the hotspot helper did not start. Nothing was changed; retry or use system settings.",
          actionLabel = "Retry",
          onAction = onRequestEnhancedAccess,
        )
        EnhancedAccessState.REVOKED -> CapabilityNotice(
          tone = NoticeTone.Lost,
          headline = "Access was lost",
          cause = "The bridge stopped or its grant was removed. Screens that depended on it show stale values until it returns.",
          actionLabel = "Reconnect",
          onAction = onRequestEnhancedAccess,
        )
        EnhancedAccessState.UNSUPPORTED -> CapabilityNotice(
          tone = NoticeTone.Unsupported,
          headline = "Not supported on this device",
          cause = "No safe non-root path exists here. Standard system settings cover hotspot control.",
          actionLabel = "Back",
          onAction = onBack,
        )
      }
    }
  }
}

@Composable
fun QuickSettingsHelpScreen(
  onBack: () -> Unit,
  onOpenSystemSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 116.dp),
  ) {
    item {
      AppTopBar(title = "Quick Settings tiles", onBack = onBack, horizontalPadding = 0.dp)
      Text(
        "Two tiles ship with the app: Scan QR and Hotspot. They unlock the phone first and never start a background service.",
        style = MaterialTheme.typography.bodyMedium,
        color = WhaleSecondary,
      )
      SectionTitle("Add them on Android 11")
      SummaryCard {
        MetricRow("Step 1", "Swipe down twice to open full Quick Settings")
        MetricRow("Step 2", "Tap the pencil or overflow edit action")
        MetricRow("Step 3", "Drag Scan QR and Hotspot into the active area")
        MetricRow("Step 4", "Tap a tile to run it. Scan QR opens the scanner. Hotspot opens real system controls.")
      }
      Spacer(Modifier.height(12.dp))
      Text(
        "Tiles collapse the notification shade on Android 11 before acting. The hotspot tile reflects the real system state and never shows a fake On bit.",
        style = MaterialTheme.typography.bodySmall,
        color = WhaleSecondary,
      )
      Spacer(Modifier.height(16.dp))
      WhalePrimaryButton("Open system settings", onClick = onOpenSystemSettings)
    }
  }
}

@Composable
fun LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  LazyColumn(
    modifier = modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 116.dp),
  ) {
    item {
      AppTopBar(title = "Licenses", onBack = onBack, horizontalPadding = 0.dp)
      SummaryCard {
        MetricRow("Interface font", "Geist Sans 400, 500 and 600")
        MetricRow("Technical font", "Geist Mono 400 and 500")
        MetricRow("Authors", "The Geist Project Authors, Vercel and collaborators")
        MetricRow("License", "SIL Open Font License 1.1")
      }
      Spacer(Modifier.height(12.dp))
      Text(
        "Geist is bundled under the SIL Open Font License 1.1, which permits embedding in an app as long as the license and copyright notice ship with the font. The full license text ships in this app under assets licenses geist OFL.txt.",
        style = MaterialTheme.typography.bodyMedium,
        color = WhaleSecondary,
      )
      SectionTitle("Sources")
      SummaryCard {
        MetricRow("Release", "https://github.com/vercel/geist-font/releases", mono = true)
        MetricRow("License text", "https://github.com/vercel/geist-font/blob/main/OFL.txt", mono = true)
        MetricRow("OFL FAQ", "https://openfontlicense.org", mono = true)
      }
    }
  }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 820)
@Composable
private fun SettingsPreview() {
  // Design fixture: static preview data only.
  WirelessWhaleTheme {
    Surface(color = WhaleBackground) {
      SettingsScreen(
        hotspot = HotspotSnapshot(enhancedAccess = EnhancedAccessState.NOT_INSTALLED),
        onRequestEnhancedAccess = {},
        onNavigate = {},
        onBack = {},
      )
    }
  }
}
