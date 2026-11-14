package com.samuel.wirelesswhale.qr

import com.samuel.wirelesswhale.core.model.WifiCredentials
import com.samuel.wirelesswhale.core.model.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WifiQrCodecTest {

  @Test
  fun decodeBaselinePlanExample() {
    val result = WifiQrCodec.decode("WIFI:T:WPA;S:ExampleNetwork;P:ExamplePassword;;")
    assertEquals(
      WifiCredentials("ExampleNetwork", "ExamplePassword", WifiSecurity.WPA2_PERSONAL, false),
      result.getOrThrow()
    )
  }

  @Test
  fun encodeWpa2RoundTrip() {
    val credentials = WifiCredentials("HomeNet", "correct-horse-1", WifiSecurity.WPA2_PERSONAL, false)
    assertEquals(credentials, WifiQrCodec.decode(WifiQrCodec.encode(credentials)).getOrThrow())
  }

  @Test
  fun decodeOpenNopass() {
    val result = WifiQrCodec.decode("WIFI:T:nopass;S:CafeOpen;;").getOrThrow()
    assertEquals(WifiCredentials("CafeOpen", "", WifiSecurity.OPEN, false), result)
  }

  @Test
  fun decodeOpenHidden() {
    val result = WifiQrCodec.decode("WIFI:T:nopass;S:HiddenOpen;H:true;;").getOrThrow()
    assertEquals(WifiCredentials("HiddenOpen", "", WifiSecurity.OPEN, true), result)
  }

  @Test
  fun encodeOpenOmitsPasswordAndIncludesHidden() {
    val encoded = WifiQrCodec.encode(WifiCredentials("OpenNet", "", WifiSecurity.OPEN, true))
    assertEquals("WIFI:T:nopass;S:OpenNet;H:true;;", encoded)
  }

  @Test
  fun decodeHiddenWpa() {
    val result = WifiQrCodec.decode("WIFI:T:WPA;S:Secret;H:true;P:password123;;").getOrThrow()
    assertTrue(result.hidden)
    assertEquals("Secret", result.ssid)
  }

  @Test
  fun decodeExplicitHiddenFalse() {
    val result = WifiQrCodec.decode("WIFI:T:WPA;S:Net;P:password123;H:false;;").getOrThrow()
    assertFalse(result.hidden)
  }

  @Test
  fun decodeHiddenCaseInsensitive() {
    assertTrue(WifiQrCodec.decode("WIFI:T:WPA;S:N;P:password123;H:TRUE;;").getOrThrow().hidden)
    assertFalse(WifiQrCodec.decode("WIFI:T:WPA;S:N;P:password123;H:False;;").getOrThrow().hidden)
  }

  @Test
  fun decodeWpa2Token() {
    val result = WifiQrCodec.decode("WIFI:T:WPA2;S:Net;P:password123;;").getOrThrow()
    assertEquals(WifiSecurity.WPA2_PERSONAL, result.security)
  }

  @Test
  fun decodeWpa3AndSaeTokens() {
    assertEquals(
      WifiSecurity.WPA3_PERSONAL,
      WifiQrCodec.decode("WIFI:T:WPA3;S:Net;P:pw1;;").getOrThrow().security
    )
    assertEquals(
      WifiSecurity.WPA3_PERSONAL,
      WifiQrCodec.decode("WIFI:T:SAE;S:Net;P:pw1;;").getOrThrow().security
    )
  }

  @Test
  fun decodeTransitionToken() {
    val result = WifiQrCodec.decode("WIFI:T:WPA2/WPA3;S:Net;P:password123;;").getOrThrow()
    assertEquals(WifiSecurity.WPA2_WPA3_PERSONAL, result.security)
  }

  @Test
  fun decodeOwe() {
    val result = WifiQrCodec.decode("WIFI:T:OWE;S:Net;;").getOrThrow()
    assertEquals(WifiSecurity.OWE, result.security)
  }

  @Test
  fun encodeOweRoundTrip() {
    val credentials = WifiCredentials("OweNet", "", WifiSecurity.OWE, false)
    assertEquals(credentials, WifiQrCodec.decode(WifiQrCodec.encode(credentials)).getOrThrow())
  }

  @Test
  fun encodeTransitionUsesCompatibleFloor() {
    val encoded = WifiQrCodec.encode(
      WifiCredentials("TransNet", "password123", WifiSecurity.WPA2_WPA3_PERSONAL, false)
    )
    assertTrue(encoded.startsWith("WIFI:T:WPA;"))
    assertEquals(
      WifiSecurity.WPA2_PERSONAL,
      WifiQrCodec.decode(encoded).getOrThrow().security
    )
  }

  @Test
  fun decodeTypeCaseInsensitive() {
    val result = WifiQrCodec.decode("WIFI:T:wpa;S:Net;P:password123;;").getOrThrow()
    assertEquals(WifiSecurity.WPA2_PERSONAL, result.security)
  }

  @Test
  fun escapingRoundTrip() {
    val credentials = WifiCredentials(
      ssid = "a;b,c:d\\e\"f",
      password = "p;ass,:\\word\"1",
      security = WifiSecurity.WPA2_PERSONAL,
      hidden = true
    )
    assertEquals(credentials, WifiQrCodec.decode(WifiQrCodec.encode(credentials)).getOrThrow())
  }

  @Test
  fun quoteInSsidPreservedLiterally() {
    val credentials = WifiCredentials("o'clock net", "password123", WifiSecurity.WPA2_PERSONAL, false)
    assertEquals(credentials, WifiQrCodec.decode(WifiQrCodec.encode(credentials)).getOrThrow())
  }

  @Test
  fun unicodeSsidRoundTrip() {
    val credentials = WifiCredentials(
      "Café-ホーム-🏠",
      "password123",
      WifiSecurity.WPA2_PERSONAL,
      false
    )
    assertEquals(credentials, WifiQrCodec.decode(WifiQrCodec.encode(credentials)).getOrThrow())
  }

  @Test
  fun unicodePasswordRoundTripWpa3() {
    val credentials = WifiCredentials("Net", "pässwörd-1", WifiSecurity.WPA3_PERSONAL, false)
    assertEquals(credentials, WifiQrCodec.decode(WifiQrCodec.encode(credentials)).getOrThrow())
  }

  @Test
  fun decodeAcceptsUnicodeWpaPasswordOfValidLength() {
    val result = WifiQrCodec.decode("WIFI:T:WPA;S:Net;P:pässwörd1;;").getOrThrow()
    assertEquals("pässwörd1", result.password)
  }

  @Test
  fun whitespacePreservedExactly() {
    val credentials = WifiCredentials("  spaced  ", "  pass 123  ", WifiSecurity.WPA2_PERSONAL, false)
    assertEquals(credentials, WifiQrCodec.decode(WifiQrCodec.encode(credentials)).getOrThrow())
  }

  @Test
  fun decodeIgnoresUnknownWellFormedKey() {
    val result = WifiQrCodec.decode("WIFI:T:WPA;S:Net;P:password123;M:ABC;;").getOrThrow()
    assertEquals("Net", result.ssid)
  }

  @Test
  fun decodeAcceptsEmptyPasswordFieldForOpen() {
    val result = WifiQrCodec.decode("WIFI:T:nopass;S:Net;P:;;").getOrThrow()
    assertEquals(WifiSecurity.OPEN, result.security)
    assertEquals("", result.password)
  }

  @Test
  fun decodeAccepts64HexWpaPassword() {
    val hex = "a1".repeat(32)
    val result = WifiQrCodec.decode("WIFI:T:WPA;S:Net;P:$hex;;").getOrThrow()
    assertEquals(hex, result.password)
  }

  @Test
  fun nonWifiPayloadRejected() {
    assertFails("https://example.com")
    assertFails("WIFI2:T:WPA;S:Net;P:password123;;")
    assertFails("")
  }

  @Test
  fun missingTerminatorRejected() {
    assertFails("WIFI:T:WPA;S:Net;P:password123;")
    assertFails("WIFI:T:WPA;S:Net;P:password123")
  }

  @Test
  fun oversizedPayloadRejected() {
    assertFails("WIFI:T:WPA;S:" + "a".repeat(5000) + ";;")
  }

  @Test
  fun missingSsidRejected() {
    assertFails("WIFI:T:WPA;P:password123;;")
    assertFails("WIFI:T:nopass;;")
  }

  @Test
  fun emptySsidRejected() {
    assertFails("WIFI:T:WPA;S:;P:password123;;")
  }

  @Test
  fun ssidOver32BytesRejected() {
    assertFails("WIFI:T:WPA;S:" + "a".repeat(33) + ";P:password123;;")
    assertFails("WIFI:T:WPA;S:" + "🏠".repeat(9) + ";P:password123;;")
  }

  @Test
  fun missingTypeWithPasswordRejected() {
    assertFails("WIFI:S:Net;P:password123;;")
    assertFails("WIFI:T:;S:Net;P:password123;;")
  }

  @Test
  fun unknownTypeRejected() {
    assertFails("WIFI:T:BLUETOOTH;S:Net;;")
  }

  @Test
  fun wepDecodeRejected() {
    val failure = WifiQrCodec.decode("WIFI:T:WEP;S:Net;P:abcde;;")
    assertTrue(failure.isFailure)
    assertTrue(failure.exceptionOrNull()!!.message!!.contains("WEP"))
  }

  @Test
  fun enterpriseDecodeRejected() {
    assertFails("WIFI:T:WPA-EAP;S:Net;E:TTLS;;")
    assertFails("WIFI:T:EAP;S:Net;;")
    assertFails("WIFI:T:ENTERPRISE;S:Corp;;")
  }

  @Test
  fun enterpriseAuxFieldsNeverSilentlyDropped() {
    assertFails("WIFI:T:WPA;S:Net;P:password123;E:TTLS;;")
    assertFails("WIFI:T:WPA;S:Net;P:password123;PH:MSCHAPV2;;")
  }

  @Test
  fun dppRejected() {
    val failure = WifiQrCodec.decode("WIFI:T:DPP;S:Net;;")
    assertTrue(failure.isFailure)
    assertTrue(failure.exceptionOrNull()!!.message!!.contains("DPP"))
  }

  @Test
  fun openWithPasswordRejected() {
    assertFails("WIFI:T:nopass;S:Net;P:password123;;")
  }

  @Test
  fun oweWithPasswordRejected() {
    assertFails("WIFI:T:OWE;S:Net;P:password123;;")
  }

  @Test
  fun shortWpaPasswordRejected() {
    assertFails("WIFI:T:WPA;S:Net;P:short;;")
  }

  @Test
  fun longNonHexWpaPasswordRejected() {
    assertFails("WIFI:T:WPA;S:Net;P:" + "z".repeat(64) + ";;")
  }

  @Test
  fun unknownEscapeRejected() {
    assertFails("WIFI:T:WPA;S:Sec\\qret;P:password123;;")
  }

  @Test
  fun trailingBackslashRejected() {
    assertFails("WIFI:T:WPA;S:Net\\;P:password123;;")
  }

  @Test
  fun duplicateFieldRejected() {
    assertFails("WIFI:T:WPA;S:One;S:Two;P:password123;;")
    assertFails("WIFI:T:WPA;T:nopass;S:Net;;")
  }

  @Test
  fun invalidHiddenRejected() {
    assertFails("WIFI:T:nopass;S:Net;H:1;;")
    assertFails("WIFI:T:nopass;S:Net;H:maybe;;")
  }

  @Test
  fun segmentWithoutColonRejected() {
    assertFails("WIFI:T:WPA;S:Net;broken;;")
  }

  @Test
  fun lowercaseKeyRejected() {
    assertFails("WIFI:t:WPA;S:Net;P:password123;;")
  }

  @Test
  fun trailingContentRejected() {
    assertFails("WIFI:T:WPA;S:Net;P:password123;;EXTRA")
  }

  @Test
  fun encodeRejectsUnsupportedSecurity() {
    assertEncodeThrows(WifiCredentials("Net", "password123", WifiSecurity.WEP, false))
    assertEncodeThrows(WifiCredentials("Net", "password123", WifiSecurity.ENTERPRISE, false))
    assertEncodeThrows(WifiCredentials("Net", "password123", WifiSecurity.UNKNOWN, false))
  }

  @Test
  fun encodeRejectsOpenWithPassword() {
    assertEncodeThrows(WifiCredentials("Net", "password123", WifiSecurity.OPEN, false))
  }

  @Test
  fun encodeRejectsEmptySsid() {
    assertEncodeThrows(WifiCredentials("", "password123", WifiSecurity.WPA2_PERSONAL, false))
  }

  @Test
  fun encodeRejectsLongSsid() {
    assertEncodeThrows(WifiCredentials("a".repeat(33), "password123", WifiSecurity.WPA2_PERSONAL, false))
  }

  @Test
  fun encodeRejectsInvalidPassword() {
    assertEncodeThrows(WifiCredentials("Net", "short", WifiSecurity.WPA2_PERSONAL, false))
    assertEncodeThrows(WifiCredentials("Net", "", WifiSecurity.WPA3_PERSONAL, false))
  }

  @Test
  fun failureMessagesNeverContainSecrets() {
    val failure = WifiQrCodec.decode("WIFI:T:WPA;S:SecretNet;S:Other;P:SecretPass123;;")
    assertTrue(failure.isFailure)
    val message = failure.exceptionOrNull()!!.message!!
    assertFalse(message.contains("SecretNet"))
    assertFalse(message.contains("SecretPass123"))
    assertFalse(message.contains("WIFI:"))

    val escaped = WifiQrCodec.decode("WIFI:T:WPA;S:Secret\\q;P:SecretPass123;;")
    assertTrue(escaped.isFailure)
    assertFalse(escaped.exceptionOrNull()!!.message!!.contains("Secret"))
  }

  private fun assertFails(payload: String) {
    val result = WifiQrCodec.decode(payload)
    if (result.isSuccess) {
      fail("Expected failure for payload of length ${payload.length}")
    }
  }

  private fun assertEncodeThrows(credentials: WifiCredentials) {
    try {
      WifiQrCodec.encode(credentials)
      fail("Expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      if (credentials.ssid.isNotEmpty()) {
        assertFalse(e.message!!.contains(credentials.ssid))
      }
      if (credentials.password.isNotEmpty()) {
        assertFalse(e.message!!.contains(credentials.password))
      }
    }
  }

  // Regression: standard open codes that omit T must parse as OPEN.
  @Test
  fun missingTypeWithoutPasswordParsesAsOpen() {
    val result = WifiQrCodec.decode("WIFI:S:OpenNet;;").getOrThrow()
    assertEquals(WifiCredentials("OpenNet", "", WifiSecurity.OPEN, false), result)
  }

  @Test
  fun missingTypeWithEmptyPasswordFieldParsesAsOpen() {
    val result = WifiQrCodec.decode("WIFI:S:Net;P:;;").getOrThrow()
    assertEquals(WifiSecurity.OPEN, result.security)
    assertEquals("", result.password)
  }

  @Test
  fun missingTypeWithHiddenParsesAsOpen() {
    val result = WifiQrCodec.decode("WIFI:S:HiddenNet;H:true;;").getOrThrow()
    assertEquals(WifiCredentials("HiddenNet", "", WifiSecurity.OPEN, true), result)
  }

  // Regression: missing T with a password is ambiguous, never guessed.
  @Test
  fun missingTypeWithPasswordRejectedAsAmbiguous() {
    val failure = WifiQrCodec.decode("WIFI:S:Net;P:password123;;")
    assertTrue(failure.isFailure)
    assertTrue(failure.exceptionOrNull()!!.message!!.contains("ambiguous"))
  }

  // Regression: WPA3 encode/decode share one 1..63 policy; 64 is rejected
  // on both paths because the 64-hex PSK form is WPA2 specific with no SAE
  // source (Android API 30 WifiConfiguration preSharedKey).
  @Test
  fun wpa3BoundariesAreConsistent() {
    val min = WifiCredentials("Net", "a", WifiSecurity.WPA3_PERSONAL, false)
    assertEquals(min, WifiQrCodec.decode(WifiQrCodec.encode(min)).getOrThrow())
    val max = WifiCredentials("Net", "x".repeat(63), WifiSecurity.WPA3_PERSONAL, false)
    assertEquals(max, WifiQrCodec.decode(WifiQrCodec.encode(max)).getOrThrow())
    assertFails("WIFI:T:WPA3;S:Net;P:" + "x".repeat(64) + ";;")
    assertEncodeThrows(WifiCredentials("Net", "x".repeat(64), WifiSecurity.WPA3_PERSONAL, false))
    assertEncodeThrows(WifiCredentials("Net", "", WifiSecurity.WPA3_PERSONAL, false))
  }

  // Regression: WPA2 decode accepts Unicode for real-world interop, but
  // encode requires printable ASCII with an explicit charset message.
  @Test
  fun wpa2UnicodeDecodeAcceptedButEncodeRequiresAscii() {
    val decoded = WifiQrCodec.decode("WIFI:T:WPA;S:Net;P:pässwörd1;;").getOrThrow()
    assertEquals("pässwörd1", decoded.password)
    try {
      WifiQrCodec.encode(WifiCredentials("Net", "pässwörd1", WifiSecurity.WPA2_PERSONAL, false))
      fail("Expected IllegalArgumentException for non-ASCII password")
    } catch (e: IllegalArgumentException) {
      assertFalse(e.message!!.contains("pässwörd1"))
      assertTrue(e.message!!.contains("cannot be shared"))
      assertFalse(e.message!!.contains("Unsupported network type"))
    }
  }

  // Regression: any duplicated key is ambiguous, including unknown extensions.
  @Test
  fun duplicateUnknownExtensionKeyRejected() {
    val failure = WifiQrCodec.decode("WIFI:T:WPA;S:Net;P:password123;M:A;M:B;;")
    assertTrue(failure.isFailure)
    assertTrue(failure.exceptionOrNull()!!.message!!.contains("ambiguous"))
    assertFalse(failure.exceptionOrNull()!!.message!!.contains("Net"))
  }

  // Regression: whitespace-only SSID is preserved exactly, never trimmed.
  @Test
  fun whitespaceOnlySsidPreserved() {
    val credentials = WifiCredentials("   ", "password123", WifiSecurity.WPA2_PERSONAL, false)
    assertEquals(credentials, WifiQrCodec.decode(WifiQrCodec.encode(credentials)).getOrThrow())
  }
}
