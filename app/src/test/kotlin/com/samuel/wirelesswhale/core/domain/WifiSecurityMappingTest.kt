package com.samuel.wirelesswhale.core.domain

import com.samuel.wirelesswhale.core.model.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiSecurityMappingTest {

  @Test
  fun open_emptyCapabilities_isOpen() {
    assertEquals(WifiSecurity.OPEN, securityFromCapabilities(""))
    assertEquals(WifiSecurity.OPEN, securityFromCapabilities("[ESS]"))
  }

  @Test
  fun wpa2_personalVariants() {
    assertEquals(
      WifiSecurity.WPA2_PERSONAL,
      securityFromCapabilities("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"),
    )
    assertEquals(
      WifiSecurity.WPA2_PERSONAL,
      securityFromCapabilities("[WPA-PSK-CCMP][ESS]"),
    )
  }

  @Test
  fun wpa3_transitionAndPure() {
    assertEquals(
      WifiSecurity.WPA2_WPA3_PERSONAL,
      securityFromCapabilities("[WPA2-PSK-CCMP][RSN-PSK-CCMP][SAE-HNP][ESS]"),
    )
    assertEquals(
      WifiSecurity.WPA3_PERSONAL,
      securityFromCapabilities("[RSN-SAE-CCMP][ESS]"),
    )
  }

  @Test
  fun wep_isWep() {
    assertEquals(WifiSecurity.WEP, securityFromCapabilities("[WEP][ESS]"))
  }

  @Test
  fun enterprise_isEnterprise() {
    assertEquals(
      WifiSecurity.ENTERPRISE,
      securityFromCapabilities("[WPA2-EAP-CCMP][RSN-EAP-CCMP][ESS]"),
    )
    assertEquals(
      WifiSecurity.ENTERPRISE,
      securityFromCapabilities("[IEEE8021X][ESS]"),
    )
  }

  @Test
  fun owe_isOwe() {
    assertEquals(WifiSecurity.OWE, securityFromCapabilities("[RSN-OWE-CCMP][ESS]"))
  }

  @Test
  fun ambiguous_isNeverDowngradedToOpen() {
    assertEquals(WifiSecurity.UNKNOWN, securityFromCapabilities("[WAPI-PSK][ESS]"))
    assertEquals(WifiSecurity.UNKNOWN, securityFromCapabilities("[FOO][ESS]"))
  }

  @Test
  fun unknownPskProtocol_isUnknown() {
    assertEquals(WifiSecurity.UNKNOWN, securityFromCapabilities("[WAPI-PSK-CCMP][ESS]"))
    assertEquals(WifiSecurity.UNKNOWN, securityFromCapabilities("[FOO-PSK-CCMP][ESS]"))
  }

  @Test
  fun bareRsnOrMalformedPrefix_isUnknown() {
    // RSN names the cipher suite, not the key management: without PSK/SAE/EAP
    // it asserts nothing about authentication.
    assertEquals(WifiSecurity.UNKNOWN, securityFromCapabilities("[RSN-CCMP][ESS]"))
    assertEquals(WifiSecurity.UNKNOWN, securityFromCapabilities("[WPA2PSK][ESS]"))
    assertEquals(WifiSecurity.UNKNOWN, securityFromCapabilities("[XWPA2-PSK-CCMP][ESS]"))
  }

  @Test
  fun wpsAlone_isOpen() {
    // WPS is setup signalling, not authentication: WPS + ESS stays OPEN.
    assertEquals(WifiSecurity.OPEN, securityFromCapabilities("[WPS][ESS]"))
  }
}
