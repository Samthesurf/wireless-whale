package com.samuel.wirelesswhale.ui

import com.samuel.wirelesswhale.core.model.CheckStatus
import com.samuel.wirelesswhale.core.model.EnhancedAccessState
import com.samuel.wirelesswhale.core.model.HotspotStatus
import com.samuel.wirelesswhale.core.model.InternetState
import com.samuel.wirelesswhale.core.model.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Focused JVM tests for stateless UI presentation helpers. No Android APIs. */
class WhaleFormatTest {

  private val now = 1_700_000_000_000L

  @Test
  fun ageIsNullWithoutTimestamp() {
    assertNull(formatAge(null, now))
  }

  @Test
  fun ageHandlesJustNowAndFuture() {
    assertEquals("Just now", formatAge(now - 3_000, now))
    assertNull(formatAge(now + 60_000, now))
  }

  @Test
  fun ageStepsThroughMinutesAndHours() {
    assertEquals("1 min ago", formatAge(now - 70_000, now))
    assertEquals("4 min ago", formatAge(now - 240_000, now))
    assertEquals("1 hour ago", formatAge(now - 3_600_000, now))
    assertEquals("3 hours ago", formatAge(now - 3 * 3_600_000, now))
  }

  @Test
  fun signalBarsNeverInventSignal() {
    assertEquals(0, signalBars(null))
    assertEquals(0, signalBars(-95))
    assertEquals(4, signalBars(-50))
    assertEquals("No signal", signalLabel(null))
    assertEquals("Excellent", signalLabel(-55))
  }

  @Test
  fun bandLabelsCoverUnknown() {
    assertEquals("Band unknown", bandLabel(null))
    assertEquals("2.4 GHz", bandLabel(2412))
    assertEquals("5 GHz", bandLabel(5180))
    assertEquals("6 GHz", bandLabel(6135))
  }

  @Test
  fun securityLabelsAreComplete() {
    assertEquals("Open", securityLabel(WifiSecurity.OPEN))
    assertEquals("Enterprise", securityLabel(WifiSecurity.ENTERPRISE))
    assertEquals("Unknown security", securityLabel(WifiSecurity.UNKNOWN))
  }

  @Test
  fun internetLabelsNameEveryState() {
    assertEquals("Internet verified", internetLabel(InternetState.VALIDATED))
    assertEquals("Sign in required", internetLabel(InternetState.CAPTIVE_PORTAL))
    assertEquals("Internet status unknown", internetLabel(InternetState.UNKNOWN))
  }

  @Test
  fun clientCountNeverRendersUnknownAsZero() {
    assertEquals("Client count unknown", clientCountLine(null))
    assertEquals("No clients connected", clientCountLine(0))
    assertEquals("1 client connected", clientCountLine(1))
    assertEquals("3 clients connected", clientCountLine(3))
  }

  @Test
  fun hotspotTitlesNameEveryState() {
    assertEquals("Hotspot is on", hotspotTitle(HotspotStatus.ON))
    assertEquals("Hotspot status unknown", hotspotTitle(HotspotStatus.UNKNOWN))
  }

  @Test
  fun enhancedAccessLabelsNameEveryState() {
    assertEquals("Standard access", enhancedAccessLabel(EnhancedAccessState.NOT_INSTALLED))
    assertEquals("Enhanced: connecting", enhancedAccessLabel(EnhancedAccessState.CONNECTING))
    assertEquals("Enhanced: access lost", enhancedAccessLabel(EnhancedAccessState.REVOKED))
    assertEquals("Enhanced: helper unavailable", enhancedAccessLabel(EnhancedAccessState.HELPER_UNAVAILABLE))
  }

  @Test
  fun checkStatusLabelsNameEveryState() {
    assertEquals("Waiting", checkStatusLabel(CheckStatus.WAITING))
    assertEquals("Warning", checkStatusLabel(CheckStatus.WARNING))
  }

  @Test
  fun ssidValidationRejectsBlankAndLong() {
    assertNotNull(validateSsid("").error)
    assertNotNull(validateSsid("   ").error)
    assertNotNull(validateSsid("x".repeat(33)).error)
    assertNull(validateSsid("Home Fibre").error)
  }

  @Test
  fun passwordValidationEnforcesWpaBounds() {
    assertNull(validatePassword("", WifiSecurity.OPEN).error)
    assertNotNull(validatePassword("short", WifiSecurity.WPA2_PERSONAL).error)
    assertNull(validatePassword("correct horse 1", WifiSecurity.WPA2_PERSONAL).error)
    assertNotNull(validatePassword("x".repeat(64), WifiSecurity.WPA3_PERSONAL).error)
  }

  @Test
  fun passwordValidationExplainsUnsupportedInsteadOfDowngrading() {
    val wep = validatePassword("any", WifiSecurity.WEP).error
    assertNotNull(wep)
    assert(wep!!.contains("system settings"))
    assertNotNull(validatePassword("any", WifiSecurity.ENTERPRISE).error)
    assertNotNull(validatePassword("", WifiSecurity.UNKNOWN).error)
  }

  @Test
  fun groupingKeepsStableSsidOrder() {
    val ids = listOf("b", "a", "c")
    val groups = groupNetworksBySsid(ids) { mapOf("a" to "Home", "b" to "Home", "c" to "Cafe")[it]!! }
    assertEquals(listOf("Home", "Cafe"), groups.keys.toList())
    assertEquals(listOf("b", "a"), groups["Home"])
  }
}
