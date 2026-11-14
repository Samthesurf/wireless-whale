package com.samuel.wirelesswhale.navigation

import com.samuel.wirelesswhale.ui.components.WhaleDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused JVM regressions for external navigation requests, token
 * round trips, and rotation restore. No Android APIs. Compose
 * instrumentation for the same scenarios lives in androidTest and was
 * not run here (no device or build in this pass).
 */
class WhaleNavigationLogicTest {

  @Test
  fun hotspotRequestAfterToolsResetsToHotspotTab() {
    val current = listOf(WhaleRoute.Tab(WhaleDestination.Tools))
    assertTrue(shouldConsumeNavigationRequest(1L, 0L))
    assertEquals(
      listOf(WhaleRoute.Tab(WhaleDestination.Hotspot)),
      nextStackForExternalRequest(current, "hotspot"),
    )
  }

  @Test
  fun repeatedSameRouteRequestStillConsumesAndResetsDetail() {
    val onDetail = listOf(
      WhaleRoute.Tab(WhaleDestination.Hotspot),
      WhaleRoute.ClientDetail("c1"),
    )
    assertTrue(shouldConsumeNavigationRequest(2L, 1L))
    assertEquals(
      listOf(WhaleRoute.Tab(WhaleDestination.Hotspot)),
      nextStackForExternalRequest(onDetail, "hotspot"),
    )
  }

  @Test
  fun identicalTabStackIsKeptWithoutRebuilding() {
    val current = listOf(WhaleRoute.Tab(WhaleDestination.Hotspot))
    assertEquals(current, nextStackForExternalRequest(current, "hotspot"))
  }

  @Test
  fun duplicateTokenIsIgnored() {
    assertFalse(shouldConsumeNavigationRequest(1L, 1L))
  }

  @Test
  fun zeroTokenNeverNavigates() {
    assertFalse(shouldConsumeNavigationRequest(0L, 0L))
    assertFalse(shouldConsumeNavigationRequest(0L, 7L))
  }

  @Test
  fun backstackTokensRoundTripEveryRoute() {
    val stack = listOf(
      WhaleRoute.Tab(WhaleDestination.Tools),
      WhaleRoute.ConnectionCheck,
      WhaleRoute.Tab(WhaleDestination.Hotspot),
      WhaleRoute.ClientDetail("c:01"),
      WhaleRoute.NetworkDetail("net.with\\dots:and:colons"),
      WhaleRoute.HotspotProfile,
      WhaleRoute.LanDiscovery,
      WhaleRoute.History,
      WhaleRoute.Settings,
      WhaleRoute.AccessSettings,
      WhaleRoute.QuickSettingsHelp,
      WhaleRoute.Licenses,
    )
    val restored = stackFromTokens(stackToTokens(stack))
    assertEquals(stack, restored)
  }

  @Test
  fun unknownDestinationFallsBackToHomeTab() {
    assertEquals(WhaleRoute.Tab(WhaleDestination.Home), routeFromToken("tab:bogus"))
  }

  @Test
  fun unknownTokensAreDroppedAndEmptyRestoreIsNull() {
    assertNull(routeFromToken("nope"))
    assertNull(routeFromToken(""))
    assertNull(routeFromToken(null))
    assertNull(routeFromToken("network:"))
    assertNull(stackFromTokens(null))
    assertNull(stackFromTokens(emptyList()))
    assertNull(stackFromTokens(listOf("nope")))
  }

  @Test
  fun scannerOverlayLeavesStackUntouched() {
    // The scanner lives outside the app composable, so opening and
    // closing it is the identity on the backstack. The saved token is
    // untouched, which is what lets the return land on the same tab.
    val stack = listOf(
      WhaleRoute.Tab(WhaleDestination.Wifi),
      WhaleRoute.ConnectionCheck,
    )
    val afterReturn = stackFromTokens(stackToTokens(stack))
    assertEquals(stack, afterReturn)
    assertFalse(shouldConsumeNavigationRequest(0L, 0L))
  }

  @Test
  fun destinationNamesMapToTabs() {
    assertEquals(WhaleDestination.Home, destinationFor("home"))
    assertEquals(WhaleDestination.Wifi, destinationFor("wifi"))
    assertEquals(WhaleDestination.Hotspot, destinationFor("hotspot"))
    assertEquals(WhaleDestination.Tools, destinationFor("tools"))
    assertEquals(WhaleDestination.Home, destinationFor("anything-else"))
  }
}
