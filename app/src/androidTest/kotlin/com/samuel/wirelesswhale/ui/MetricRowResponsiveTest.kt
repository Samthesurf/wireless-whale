package com.samuel.wirelesswhale.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samuel.wirelesswhale.ui.components.MetricRow
import com.samuel.wirelesswhale.ui.theme.WirelessWhaleTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Responsive regression for [MetricRow], the shared label/value row behind
 * the Connection check and Privacy screens.
 *
 * The old row gave the label `weight(1f)` but left the value unconstrained,
 * so a long URL (`https://connectivitycheck.gstatic.com/generate_204`) stole
 * the whole row and squeezed "External probe" (and the Privacy labels) to one
 * character per line. These tests pin the fixed behavior with geometry, not
 * just text existence (the old bug still rendered every string):
 *
 * - long URL/description values stack below their label at 320 dp and 360 dp,
 *   both columns keep a readable width, and the two texts never overlap;
 * - short metrics stay in the compact side-by-side row;
 * - a technical value stacks at font scale 2.0 instead of overflowing;
 * - unknown/stale/unavailable semantics still render.
 *
 * NOT RUN here (no build or phone in this task). The parent runs them with
 * the low-memory Gradle pass. No network actions, no permission grants, no
 * radio toggles.
 */
@RunWith(AndroidJUnit4::class)
class MetricRowResponsiveTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<ComponentActivity>()

  private val tolerance: Dp = 2.dp

  private fun setRow(width: Dp, fontScale: Float = 1f, content: @Composable () -> Unit) {
    composeRule.setContent {
      val current = LocalDensity.current
      CompositionLocalProvider(
        LocalDensity provides Density(density = current.density, fontScale = fontScale)
      ) {
        WirelessWhaleTheme {
          Box(Modifier.requiredWidth(width)) {
            content()
          }
        }
      }
    }
  }

  @Test
  fun externalProbeUrlStacksAt360() {
    val url = "https://connectivitycheck.gstatic.com/generate_204"
    setRow(360.dp) {
      MetricRow("External probe", url, mono = true)
    }

    val label = composeRule.onNodeWithText("External probe")
      .assertIsDisplayed().getUnclippedBoundsInRoot()
    val value = composeRule.onNodeWithText(url)
      .assertIsDisplayed().getUnclippedBoundsInRoot()

    assertTrue(
      "long URL must stack below its label, label.bottom=${label.bottom} value.top=${value.top}",
      label.bottom <= value.top + tolerance,
    )
    assertTrue(
      "label must keep a readable width, was ${label.width}",
      label.width >= 40.dp,
    )
    assertTrue(
      "URL value must keep a readable full width, was ${value.width}",
      value.width >= 200.dp,
    )
    assertTrue(
      "label and URL must not overlap, label=$label value=$value",
      value.top >= label.bottom - tolerance,
    )
  }

  @Test
  fun externalProbeUrlStacksAt320() {
    val url = "https://connectivitycheck.gstatic.com/generate_204"
    setRow(320.dp) {
      MetricRow("External probe", url, mono = true)
    }

    val label = composeRule.onNodeWithText("External probe")
      .assertIsDisplayed().getUnclippedBoundsInRoot()
    val value = composeRule.onNodeWithText(url)
      .assertIsDisplayed().getUnclippedBoundsInRoot()

    assertTrue(
      "long URL must stack below its label at 320dp, label.bottom=${label.bottom} value.top=${value.top}",
      label.bottom <= value.top + tolerance,
    )
    assertTrue(
      "label must keep a readable width at 320dp, was ${label.width}",
      label.width >= 40.dp,
    )
    assertTrue(
      "URL value must keep a readable full width at 320dp, was ${value.width}",
      value.width >= 200.dp,
    )
  }

  @Test
  fun privacyValuesStackAndStayFullyVisible() {
    setRow(320.dp) {
      Column {
        MetricRow("Location", "Used only to read network names and scans")
        MetricRow("Passwords", "Never logged or stored by these screens")
        MetricRow("QR payloads", "Redacted before storage or export")
      }
    }

    val pairs = listOf(
      "Location" to "Used only to read network names and scans",
      "Passwords" to "Never logged or stored by these screens",
      "QR payloads" to "Redacted before storage or export",
    )
    for ((labelText, valueText) in pairs) {
      val label = composeRule.onNodeWithText(labelText)
        .assertIsDisplayed().getUnclippedBoundsInRoot()
      val value = composeRule.onNodeWithText(valueText)
        .assertIsDisplayed().getUnclippedBoundsInRoot()
      assertTrue(
        "\"$labelText\" value must stack below its label, label.bottom=${label.bottom} value.top=${value.top}",
        label.bottom <= value.top + tolerance,
      )
      assertTrue(
        "\"$labelText\" label must keep a readable width, was ${label.width}",
        label.width >= 40.dp,
      )
      assertTrue(
        "\"$labelText\" value must keep a readable full width, was ${value.width}",
        value.width >= 200.dp,
      )
    }
  }

  @Test
  fun shortMetricStaysSideBySide() {
    setRow(360.dp) {
      MetricRow("Signal", "-65 dBm (Good)", mono = true)
    }

    val label = composeRule.onNodeWithText("Signal")
      .assertIsDisplayed().getUnclippedBoundsInRoot()
    val value = composeRule.onNodeWithText("-65 dBm (Good)")
      .assertIsDisplayed().getUnclippedBoundsInRoot()

    assertTrue(
      "short metric must share one row, label=$label value=$value",
      label.bottom > value.top + tolerance && value.bottom > label.top + tolerance,
    )
    assertTrue(
      "value must sit right of the label start, label.left=${label.left} value.left=${value.left}",
      value.left > label.left,
    )
    assertTrue(
      "columns must not overlap horizontally, label.right=${label.right} value.left=${value.left}",
      value.left >= label.right - tolerance,
    )
    assertTrue(
      "label must keep its column share, was ${label.width}",
      label.width >= 100.dp,
    )
  }

  @Test
  fun technicalValueStacksAtFontScale2() {
    setRow(320.dp, fontScale = 2f) {
      MetricRow("BSSID", "AA:BB:CC:11:22:33", mono = true)
    }

    val label = composeRule.onNodeWithText("BSSID")
      .assertIsDisplayed().getUnclippedBoundsInRoot()
    val value = composeRule.onNodeWithText("AA:BB:CC:11:22:33")
      .assertIsDisplayed().getUnclippedBoundsInRoot()

    assertTrue(
      "technical value must stack at font scale 2, label.bottom=${label.bottom} value.top=${value.top}",
      label.bottom <= value.top + tolerance,
    )
    assertTrue(
      "label must keep a readable width at font scale 2, was ${label.width}",
      label.width >= 40.dp,
    )
    assertTrue(
      "value must keep a readable full width at font scale 2, was ${value.width}",
      value.width >= 200.dp,
    )
  }

  @Test
  fun unknownAndUnavailableSemanticsPreserved() {
    setRow(360.dp) {
      Column {
        MetricRow(
          "Direct toggle",
          null,
          unavailableCause = "This backend hands toggling to system settings.",
        )
        MetricRow("Nickname", null)
        MetricRow(
          "Signal",
          "-65 dBm",
          staleStamp = "Last updated 4 min ago. Refresh to confirm.",
        )
      }
    }

    composeRule.onNodeWithText("Unavailable").assertIsDisplayed()
    composeRule.onNodeWithText("This backend hands toggling to system settings.")
      .assertIsDisplayed()
    composeRule.onNodeWithText("Unknown").assertIsDisplayed()
    composeRule.onNodeWithText("Last updated 4 min ago. Refresh to confirm.")
      .assertIsDisplayed()
  }
}
