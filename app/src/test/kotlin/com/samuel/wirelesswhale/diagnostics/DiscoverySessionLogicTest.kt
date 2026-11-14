package com.samuel.wirelesswhale.diagnostics

import com.samuel.wirelesswhale.core.model.CheckStatus
import com.samuel.wirelesswhale.core.model.DiagnosticStep
import com.samuel.wirelesswhale.core.model.DiscoveredDevice
import com.samuel.wirelesswhale.core.model.HistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure regression tests for the hardened LAN-discovery session rules and the
 * diagnostics cancellation contract. No Android framework needed.
 */
class DiscoverySessionLogicTest {

  private fun device(id: String) =
    DiscoveredDevice(id = id, name = id, host = "192.168.1.2", port = 80, serviceType = "_http._tcp.")

  @Test fun `discovery key is stable and null-safe`() {
    assertEquals("_http._tcp.|Printer", DiagnosticsSupport.discoveryKey("_http._tcp.", "Printer"))
    assertEquals("_http._tcp.|unknown", DiagnosticsSupport.discoveryKey("_http._tcp.", null))
  }

  @Test fun `stale generation is detected`() {
    assertTrue(DiagnosticsSupport.isStaleDiscoveryCallback(1, 2))
    assertFalse(DiagnosticsSupport.isStaleDiscoveryCallback(2, 2))
  }

  @Test fun `bounded insert evicts oldest first`() {
    val map = LinkedHashMap<String, DiscoveredDevice>()
    val evicted = mutableListOf<String?>()
    for (i in 0 until DiagnosticsSupport.DISCOVERY_MAX_DEVICES + 5) {
      evicted.add(DiagnosticsSupport.insertDiscoveryBounded(map, device("id$i")))
    }
    assertEquals(DiagnosticsSupport.DISCOVERY_MAX_DEVICES, map.size)
    // First five inserts caused no eviction until the map filled; later ones evicted.
    assertNull(evicted.first())
    assertEquals("id0", evicted[DiagnosticsSupport.DISCOVERY_MAX_DEVICES])
    assertFalse(map.containsKey("id0"))
    assertTrue(map.containsKey("id${DiagnosticsSupport.DISCOVERY_MAX_DEVICES + 4}"))
  }

  @Test fun `bounded insert updates in place without eviction`() {
    val map = LinkedHashMap<String, DiscoveredDevice>()
    for (i in 0 until DiagnosticsSupport.DISCOVERY_MAX_DEVICES) {
      DiagnosticsSupport.insertDiscoveryBounded(map, device("id$i"))
    }
    val evicted = DiagnosticsSupport.insertDiscoveryBounded(
      map,
      device("id0").copy(host = "10.0.0.9"),
    )
    assertNull(evicted)
    assertEquals(DiagnosticsSupport.DISCOVERY_MAX_DEVICES, map.size)
    assertEquals("10.0.0.9", map["id0"]?.host)
  }

  @Test fun `underlying map never grows unbounded`() {
    val map = LinkedHashMap<String, DiscoveredDevice>()
    for (i in 0 until 500) {
      DiagnosticsSupport.insertDiscoveryBounded(map, device("id$i"))
    }
    assertEquals(DiagnosticsSupport.DISCOVERY_MAX_DEVICES, map.size)
  }

  @Test fun `resolve queue dedups and serializes`() {
    val q = DiagnosticsSupport.ResolveQueue()
    assertTrue(q.offer("a"))
    assertFalse(q.offer("a"))
    assertTrue(q.offer("b"))
    assertEquals("a", q.takeNext())
    // Busy while one resolve is active.
    assertNull(q.takeNext())
    assertEquals("a", q.activeId())
    // Finishing pumps the next waiter.
    assertEquals("b", q.finish("a"))
    assertEquals("b", q.activeId())
    assertNull(q.finish("b"))
    assertNull(q.activeId())
  }

  @Test fun `resolve queue remove drops lost services`() {
    val q = DiagnosticsSupport.ResolveQueue()
    q.offer("a")
    q.offer("gone")
    assertTrue(q.remove("gone"))
    assertEquals("a", q.takeNext())
    assertNull(q.finish("a"))
  }

  @Test fun `resolve queue finish is idempotent`() {
    val q = DiagnosticsSupport.ResolveQueue()
    q.offer("a")
    assertEquals("a", q.takeNext())
    assertNull(q.finish("stale-id"))
    assertEquals("a", q.activeId())
    assertNull(q.finish("a"))
  }

  @Test fun `only timeouts count as timeouts`() = runTest {
    // Obtain the real exception through the public API and virtual time;
    // its constructor is internal in the pinned coroutine version.
    val timeout = try {
      withTimeout(1L) { delay(2L) }
      throw AssertionError("Expected a coroutine timeout")
    } catch (expected: TimeoutCancellationException) {
      expected
    }
    assertTrue(DiagnosticsSupport.isTimeoutCancellation(timeout))
    assertFalse(DiagnosticsSupport.isTimeoutCancellation(CancellationException("user cancel")))
  }

  @Test fun `cancelled step marking preserves terminal states`() {
    val running = DiagnosticStep("dns", "DNS resolution", CheckStatus.RUNNING, "Checking…")
    val marked = DiagnosticsSupport.markStepCancelled(running)
    assertEquals(CheckStatus.FAILED, marked.status)
    assertTrue(marked.detail.contains("Cancelled", ignoreCase = true))

    val passed = DiagnosticStep("dns", "DNS resolution", CheckStatus.PASSED, "ok")
    assertEquals(passed, DiagnosticsSupport.markStepCancelled(passed))
  }

  @Test fun `discovery session timeout is sixty seconds`() {
    assertEquals(60_000L, DiagnosticsSupport.DISCOVERY_TIMEOUT_MS)
  }

  @Test fun `history export stays bounded and redacts kind`() {
    val entries = (1..150).map {
      HistoryEntry("id$it", "title password=hunter2", "detail", it.toLong(), "kind password=hunter2")
    }
    val out = DiagnosticsSupport.buildHistoryExport(entries)
    assertFalse(out.contains("hunter2"))
    // Newest-first first page survives; the dropped tail does not.
    assertTrue(out.contains("\"id1\""))
    assertFalse(out.contains("\"id150\""))
  }
}
