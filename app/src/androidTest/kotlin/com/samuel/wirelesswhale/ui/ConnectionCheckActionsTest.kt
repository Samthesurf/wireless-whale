package com.samuel.wirelesswhale.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samuel.wirelesswhale.core.model.CheckStatus
import com.samuel.wirelesswhale.core.model.DiagnosticReport
import com.samuel.wirelesswhale.core.model.DiagnosticStep
import com.samuel.wirelesswhale.feature.tools.ConnectionCheckScreen
import com.samuel.wirelesswhale.ui.theme.WirelessWhaleTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-only fixtures and callbacks. These tests never run network diagnostics. */
@RunWith(AndroidJUnit4::class)
class ConnectionCheckActionsTest {
  @get:Rule
  val compose = createAndroidComposeRule<ComponentActivity>()

  private fun show(
    report: DiagnosticReport = DiagnosticReport(),
    running: Boolean = false,
    onRun: () -> Unit = {},
    onCancel: () -> Unit = {},
  ) {
    compose.setContent {
      WirelessWhaleTheme {
        Box(Modifier.requiredWidth(320.dp)) {
          ConnectionCheckScreen(
            report = report,
            running = running,
            onRunDiagnostics = onRun,
            onCancelDiagnostics = onCancel,
            onBack = {},
          )
        }
      }
    }
  }

  @Test
  fun idleHasOneExplicitRunActionAndDoesNotAutoRun() {
    val runs = AtomicInteger()
    show(onRun = { runs.incrementAndGet() })
    compose.waitForIdle()
    assertEquals(0, runs.get())
    assertEquals(1, compose.onAllNodesWithText("Run check").fetchSemanticsNodes().size)
    compose.onNodeWithText("No results yet").assertIsDisplayed()
    compose.onNodeWithText("Run check").performClick()
    assertEquals(1, runs.get())
  }

  @Test
  fun cancelledHasOneRetryAndKeepsPartialResultsWarning() {
    val runs = AtomicInteger()
    show(
      report = DiagnosticReport(
        steps = listOf(DiagnosticStep("fixture", "Local link", CheckStatus.WAITING)),
        cancelled = true,
      ),
      onRun = { runs.incrementAndGet() },
    )
    assertEquals(1, compose.onAllNodesWithText("Run again").fetchSemanticsNodes().size)
    assertEquals(0, compose.onAllNodesWithText("Run check").fetchSemanticsNodes().size)
    compose.onNodeWithText("Run canceled").assertIsDisplayed()
    compose.onNodeWithText(
      "Steps below the cancel point never ran. Results are partial, not a verdict.",
    ).assertIsDisplayed()
    compose.onNodeWithText("Run again").performClick()
    assertEquals(1, runs.get())
  }

  @Test
  fun runningHasOnlyCancelEvenIfPreviousReportWasCancelled() {
    val cancellations = AtomicInteger()
    show(
      report = DiagnosticReport(cancelled = true),
      running = true,
      onCancel = { cancellations.incrementAndGet() },
    )
    assertEquals(0, compose.onAllNodesWithText("Run check").fetchSemanticsNodes().size)
    assertEquals(0, compose.onAllNodesWithText("Run again").fetchSemanticsNodes().size)
    assertEquals(1, compose.onAllNodesWithText("Cancel").fetchSemanticsNodes().size)
    compose.onNodeWithText("Run canceled").assertDoesNotExist()
    compose.onNodeWithText("Cancel").performClick()
    assertEquals(1, cancellations.get())
  }
}
