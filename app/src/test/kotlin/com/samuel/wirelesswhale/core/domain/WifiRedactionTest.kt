package com.samuel.wirelesswhale.core.domain

import com.samuel.wirelesswhale.core.model.HotspotProfile
import com.samuel.wirelesswhale.core.model.WifiCredentials
import com.samuel.wirelesswhale.core.model.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WifiRedactionTest {

  @Test
  fun sanitizeSsid_passthrough() {
    assertEquals("WhaleNet", sanitizeSsid("WhaleNet"))
    assertEquals("  WhaleNet  ", sanitizeSsid("  WhaleNet  "))
  }

  @Test
  fun sanitizeSsid_stripsLegacyQuotes() {
    assertEquals("WhaleNet", sanitizeSsid("\"WhaleNet\""))
  }

  @Test
  fun sanitizeSsid_redactedValuesBecomeNull() {
    assertNull(sanitizeSsid(null))
    assertNull(sanitizeSsid(""))
    assertEquals("   ", sanitizeSsid("   "))
    assertNull(sanitizeSsid("<unknown ssid>"))
    assertNull(sanitizeSsid("<UNKNOWN SSID>"))
    assertNull(sanitizeSsid("\"<unknown ssid>\""))
  }

  @Test
  fun scanSsid_preservesLiteralQuotesAndWhitespace() {
    assertEquals("  WhaleNet  ", sanitizeScanSsid("  WhaleNet  "))
    assertEquals("\"WhaleNet\"", sanitizeScanSsid("\"WhaleNet\""))
    assertEquals("   ", sanitizeScanSsid("   "))
    assertNull(sanitizeScanSsid(""))
    assertNull(sanitizeScanSsid(null))
  }

  @Test
  fun wifiInfoSsid_preservesWhitespaceInsidePlatformQuotes() {
    assertEquals("  WhaleNet  ", sanitizeSsid("\"  WhaleNet  \""))
  }

  @Test
  fun sanitizeBssid_normalizesAndRedacts() {
    assertEquals("aa:bb:cc:11:22:33", sanitizeBssid("AA:BB:CC:11:22:33"))
    assertNull(sanitizeBssid(null))
    assertNull(sanitizeBssid(""))
    assertNull(sanitizeBssid("02:00:00:00:00:00"))
  }

  @Test
  fun credentials_toString_neverContainsPassword() {
    val credentials =
      WifiCredentials(ssid = "WhaleNet", password = "s3cret!", security = WifiSecurity.WPA3_PERSONAL)
    val text = credentials.toString()
    assertFalse(text.contains("s3cret!"))
    assertEquals(
      "WifiCredentials(ssid=WhaleNet, security=WPA3_PERSONAL, hidden=false, password=[REDACTED])",
      text,
    )
  }

  @Test
  fun hotspotProfile_toString_neverContainsPassword() {
    val profile = HotspotProfile(ssid = "WhaleHotspot", password = "s3cret!", band = 5)
    val text = profile.toString()
    assertFalse(text.contains("s3cret!"))
  }
}
