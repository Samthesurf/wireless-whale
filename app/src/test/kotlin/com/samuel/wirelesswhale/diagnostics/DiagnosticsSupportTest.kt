package com.samuel.wirelesswhale.diagnostics

import com.samuel.wirelesswhale.core.model.CheckStatus
import com.samuel.wirelesswhale.core.model.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSupportTest {

  // Redaction

  @Test fun `redacts full WIFI QR payload`() {
    val out = DiagnosticsSupport.redactSecrets("Connect WIFI:T:WPA;S:CafeNet;P:s3cr3t;; now")
    assertEquals("Connect WIFI:[REDACTED] now", out)
  }

  @Test fun `redacts password-style assignments`() {
    assertEquals("wifi password=[REDACTED]", DiagnosticsSupport.redactSecrets("wifi password=hunter2"))
    assertEquals("PSK=[REDACTED] here", DiagnosticsSupport.redactSecrets("PSK: hunter2 here"))
    assertEquals("keep pass=[REDACTED]", DiagnosticsSupport.redactSecrets("keep pass:\"a b c\""))
  }

  @Test fun `leaves plain observations untouched`() {
    val text = "SSID CafeNet, RSSI -62 dBm, gateway 192.168.43.1"
    assertEquals(text, DiagnosticsSupport.redactSecrets(text))
  }

  @Test fun `does not redact pass without separator`() {
    assertEquals("pass the salt", DiagnosticsSupport.redactSecrets("pass the salt"))
  }

  // Bounds

  @Test fun `trimHistory keeps newest first within bound`() {
    val entries = (1..150).map { HistoryEntry("id$it", "t$it", "d", it.toLong(), "note") }
    val trimmed = DiagnosticsSupport.trimHistory(entries)
    assertEquals(DiagnosticsSupport.HISTORY_MAX_ENTRIES, trimmed.size)
    assertEquals("id1", trimmed.first().id)
    assertEquals("id100", trimmed.last().id)
  }

  @Test fun `trimHistory passes small lists through`() {
    val entries = listOf(HistoryEntry("a", "t", "d", 1L, "note"))
    assertEquals(entries, DiagnosticsSupport.trimHistory(entries))
  }

  @Test fun `truncate caps length`() {
    assertEquals("abc", DiagnosticsSupport.truncate("abcdef", 3))
    assertEquals("ab", DiagnosticsSupport.truncate("ab", 5))
  }

  // Result logic

  @Test fun `https 204 passes`() {
    val step = DiagnosticsSupport.httpsStep(code = 204, error = null, durationMs = 120L)
    assertEquals(CheckStatus.PASSED, step.status)
    assertEquals(DiagnosticsSupport.STEP_HTTPS, step.id)
    assertEquals(120L, step.durationMs)
  }

  @Test fun `https 200 warns of captive portal`() {
    val step = DiagnosticsSupport.httpsStep(code = 200, error = null, durationMs = 90L)
    assertEquals(CheckStatus.WARNING, step.status)
    assertTrue(step.detail.contains("captive portal", ignoreCase = true))
  }

  @Test fun `https error fails with cause`() {
    val step = DiagnosticsSupport.httpsStep(code = null, error = "timed out", durationMs = 8000L)
    assertEquals(CheckStatus.FAILED, step.status)
    assertTrue(step.detail.contains("timed out"))
  }

  @Test fun `https 500 fails without claiming no internet`() {
    val step = DiagnosticsSupport.httpsStep(code = 500, error = null, durationMs = 300L)
    assertEquals(CheckStatus.FAILED, step.status)
    assertTrue(step.detail.contains("500"))
  }

  @Test fun `dns success reports measured count and duration`() {
    val step = DiagnosticsSupport.dnsStep(addressCount = 2, durationMs = 45L, error = null)
    assertEquals(CheckStatus.PASSED, step.status)
    assertTrue(step.detail.contains("2 address"))
    assertTrue(step.detail.contains("45 ms"))
  }

  @Test fun `dns error fails`() {
    val step = DiagnosticsSupport.dnsStep(addressCount = 0, durationMs = 10_000L, error = "timed out after 10000 ms")
    assertEquals(CheckStatus.FAILED, step.status)
    assertTrue(step.detail.contains("timed out"))
  }

  // Nicknames

  @Test fun `valid nickname passes`() {
    assertTrue(DiagnosticsSupport.isValidNickname("Samuel's Infinix"))
  }

  @Test fun `blank nickname invalid`() {
    assertFalse(DiagnosticsSupport.isValidNickname("   "))
    assertFalse(DiagnosticsSupport.isValidNickname(""))
  }

  @Test fun `nickname strips controls and collapses spaces`() {
    assertEquals("ab cd", DiagnosticsSupport.cleanNickname("  ab\u0007  cd  "))
  }

  @Test fun `nickname truncates to max length`() {
    val long = "a".repeat(DiagnosticsSupport.NICKNAME_MAX_LENGTH + 10)
    assertEquals(DiagnosticsSupport.NICKNAME_MAX_LENGTH, DiagnosticsSupport.cleanNickname(long).length)
  }

  // Export

  @Test fun `export escapes and re-redacts`() {
    val entries = listOf(
      HistoryEntry("1", "Check \"home\"", "password=hunter2\nline2", 1700000000000L, "diagnostic"),
    )
    val out = DiagnosticsSupport.buildHistoryExport(entries)
    assertTrue(out.startsWith("["))
    assertTrue(out.endsWith("]"))
    assertTrue(out.contains("Check \\\"home\\\""))
    assertTrue(out.contains("password=[REDACTED]"))
    assertTrue(out.contains("\\n"))
    assertFalse(out.contains("hunter2"))
    assertTrue(out.contains("\"atMillis\":1700000000000"))
  }

  @Test fun `export of empty list is empty array`() {
    assertEquals("[]", DiagnosticsSupport.buildHistoryExport(emptyList()))
  }
}
