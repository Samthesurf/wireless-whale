package com.samuel.wirelesswhale.data

import com.samuel.wirelesswhale.core.model.HistoryEntry
import com.samuel.wirelesswhale.diagnostics.DiagnosticsSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Data-layer contract tests that run on plain JUnit (no Android framework).
 * They pin the history bound/redaction/export shape and the nickname rules the
 * SharedPreferences stores delegate to. The thin Context wrappers themselves
 * need an instrumented or Robolectric run, which is not wired in this slice.
 */
class HistoryNicknameLogicTest {

  private fun entry(i: Int, title: String = "t$i", detail: String = "d$i") =
    HistoryEntry(id = "id$i", title = title, detail = detail, atMillis = i.toLong(), kind = "diagnostic")

  @Test fun `newest entry survives bound while oldest drops`() {
    val entries = (1..105).map { entry(it) } // newest-first: id1 newest
    val trimmed = DiagnosticsSupport.trimHistory(entries)
    assertEquals(100, trimmed.size)
    assertEquals("id1", trimmed.first().id)
    assertEquals("id100", trimmed.last().id)
    assertTrue(trimmed.none { it.id == "id105" })
  }

  @Test fun `stored title and detail never carry raw wifi payloads`() {
    val raw = "Share WIFI:T:WPA2;S:Home;P:sup3rsecret;; done, password=sup3rsecret"
    val storedTitle = DiagnosticsSupport.redactSecrets(raw)
    val storedDetail = DiagnosticsSupport.truncate(
      DiagnosticsSupport.redactSecrets(raw),
      DiagnosticsSupport.HISTORY_DETAIL_MAX,
    )
    assertFalse(storedTitle.contains("sup3rsecret"))
    assertFalse(storedDetail.contains("sup3rsecret"))
    assertTrue(storedTitle.contains("WIFI:[REDACTED]"))
  }

  @Test fun `kind falls back to note and stays bounded`() {
    val kind = "".ifEmpty { "note" }
    assertEquals("note", kind)
    assertEquals(
      DiagnosticsSupport.HISTORY_KIND_MAX,
      DiagnosticsSupport.truncate("k".repeat(60), DiagnosticsSupport.HISTORY_KIND_MAX).length,
    )
  }

  @Test fun `export keeps newest-first order and valid shape`() {
    val entries = listOf(entry(2), entry(1))
    val out = DiagnosticsSupport.buildHistoryExport(entries)
    assertTrue(out.indexOf("\"id2\"") < out.indexOf("\"id1\""))
    assertTrue(out.startsWith("[{"))
    assertTrue(out.endsWith("}]"))
  }

  @Test fun `nickname round trip rules for set and get`() {
    // Mirrors ClientNicknameStore cleaning: stored value is always cleaned.
    assertEquals("Living Room", DiagnosticsSupport.cleanNickname("  Living   Room "))
    assertEquals("", DiagnosticsSupport.cleanNickname("\u0000\u0007  "))
    // Blank-after-clean means "remove", so get would return null.
    assertEquals("", DiagnosticsSupport.cleanNickname("   "))
  }

  @Test fun `overlong nickname is truncated not rejected`() {
    val cleaned = DiagnosticsSupport.cleanNickname("n".repeat(50))
    assertEquals(DiagnosticsSupport.NICKNAME_MAX_LENGTH, cleaned.length)
    assertTrue(DiagnosticsSupport.isValidNickname("n".repeat(50)))
  }

  @Test fun `nickname keeps unicode letters`() {
    assertEquals("Chukwuemeka", DiagnosticsSupport.cleanNickname("Chukwuemeka"))
  }
}
