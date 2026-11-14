package com.samuel.wirelesswhale.platform.shizuku

import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.BRIDGE_TAG
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.BRIDGE_VERSION
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.DESTROY_TRANSACTION
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeProtocol.BridgeClient
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeProtocol.BridgeSnapshot
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeProtocol.TileHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotBridgeProtocolTest {

  // Contract pins.

  @Test
  fun contract_tagAndVersion_areStable() {
    assertEquals("ww-hotspot-bridge", BRIDGE_TAG)
    assertEquals(3, BRIDGE_VERSION)
    assertEquals(16777115, DESTROY_TRANSACTION)
  }

  // Identity.

  @Test
  fun normalizeMac_acceptsAndCanonicalizes() {
    assertEquals("AA:BB:CC:11:22:33", HotspotBridgeProtocol.normalizeMac("aa:bb:cc:11:22:33"))
    assertEquals("AA:BB:CC:11:22:33", HotspotBridgeProtocol.normalizeMac("  AA:BB:CC:11:22:33  "))
  }

  @Test
  fun normalizeMac_rejectsGarbage() {
    assertNull(HotspotBridgeProtocol.normalizeMac(null))
    assertNull(HotspotBridgeProtocol.normalizeMac(""))
    assertNull(HotspotBridgeProtocol.normalizeMac("not-a-mac"))
    assertNull(HotspotBridgeProtocol.normalizeMac("AA:BB:CC:11:22")) // too short
    assertNull(HotspotBridgeProtocol.normalizeMac("AA:BB:CC:11:22:33:44")) // too long
    assertNull(HotspotBridgeProtocol.normalizeMac("GG:BB:CC:11:22:33")) // bad hex
  }

  @Test
  fun clientId_isStableMacKey() {
    assertEquals("mac:AA:BB:CC:11:22:33", HotspotBridgeProtocol.clientIdFor("aa:bb:cc:11:22:33"))
  }

  @Test
  fun plausibleIp_acceptsV4AndV6() {
    assertTrue(HotspotBridgeProtocol.isPlausibleIp("192.168.43.7"))
    assertTrue(HotspotBridgeProtocol.isPlausibleIp("fe80::1"))
    assertFalse(HotspotBridgeProtocol.isPlausibleIp(null))
    assertFalse(HotspotBridgeProtocol.isPlausibleIp(""))
    assertFalse(HotspotBridgeProtocol.isPlausibleIp("999.1.1.1"))
    assertFalse(HotspotBridgeProtocol.isPlausibleIp("hello"))
  }

  // Profile validation.

  @Test
  fun validateProfile_acceptsGoodWpa2() {
    assertTrue(HotspotBridgeProtocol.validateProfile("WhaleNet", "correct-horse-9", 2).isEmpty())
  }

  @Test
  fun validateProfile_acceptsOpen() {
    assertTrue(HotspotBridgeProtocol.validateProfile("WhaleNet", "", null).isEmpty())
  }

  @Test
  fun validateProfile_rejectsBadInputs() {
    assertFalse(HotspotBridgeProtocol.validateProfile("", "password1", null).isEmpty())
    assertFalse(HotspotBridgeProtocol.validateProfile("WhaleNet", "short", null).isEmpty())
    assertFalse(HotspotBridgeProtocol.validateProfile("WhaleNet", "password1", 7).isEmpty())
    assertFalse(HotspotBridgeProtocol.validateProfile(" leadspace", "password1", null).isEmpty())
  }

  // Membership merge: Soft AP authoritative, enrichment never filters.

  @Test
  fun mergeClients_keepsMacWithoutIp() {
    val merged = HotspotBridgeProtocol.mergeClients(
      softApMacs = listOf("AA:BB:CC:11:22:33"),
      addressesByMac = emptyMap(),
    )
    assertEquals(1, merged.size)
    assertEquals("AA:BB:CC:11:22:33", merged[0].mac)
    assertTrue(merged[0].addresses.isEmpty())
  }

  @Test
  fun mergeClients_enrichesMatchedMacOnly() {
    val merged = HotspotBridgeProtocol.mergeClients(
      softApMacs = listOf("AA:BB:CC:11:22:33", "11:22:33:44:55:66"),
      addressesByMac = mapOf(
        "aa:bb:cc:11:22:33" to listOf("192.168.43.7"),
        "DE:AD:BE:EF:00:01" to listOf("192.168.43.9"), // ghost: not associated
      ),
      hostnameByMac = mapOf("AA:BB:CC:11:22:33" to "pixel"),
    )
    assertEquals(2, merged.size)
    val withIp = merged.first { it.mac == "AA:BB:CC:11:22:33" }
    assertEquals(listOf("192.168.43.7"), withIp.addresses)
    assertEquals("pixel", withIp.hostname)
    val staticIp = merged.first { it.mac == "11:22:33:44:55:66" }
    assertTrue(staticIp.addresses.isEmpty()) // static-IP/sleeping client stays listed
    assertTrue(merged.none { it.mac == "DE:AD:BE:EF:00:01" }) // ghost never invents a member
  }

  @Test
  fun mergeClients_dropsInvalidMacs() {
    val merged = HotspotBridgeProtocol.mergeClients(
      softApMacs = listOf("bogus", "AA:BB:CC:11:22:33"),
    )
    assertEquals(1, merged.size)
  }

  // Strict table parsers.

  @Test
  fun parseArpTable_strictAndCapped() {
    val text = """
      IP address       HW type     Flags       HW address            Mask     Device
      192.168.43.7     0x1         0x2         aa:bb:cc:11:22:33     *        wlan0
      garbage line without columns
      999.1.1.1        0x1         0x2         notamac               *        wlan0
      192.168.43.8     0x1         0x0         00:00:00:00:00:00     *        wlan0
    """.trimIndent()
    val parsed = HotspotBridgeProtocol.parseArpTable(text)
    assertEquals(mapOf("AA:BB:CC:11:22:33" to "192.168.43.7"), parsed)
  }

  @Test
  fun parseNeighShow_strictRowsOnly() {
    val text = """
      192.168.43.7 dev wlan0 lladdr aa:bb:cc:11:22:33 REACHABLE
      192.168.43.8 dev wlan0  FAILED
      not a neighbor line
      192.168.43.9 dev wlan0 lladdr 11:22:33:44:55:66 STALE
    """.trimIndent()
    val parsed = HotspotBridgeProtocol.parseNeighShow(text)
    assertEquals(
      mapOf("AA:BB:CC:11:22:33" to "192.168.43.7", "11:22:33:44:55:66" to "192.168.43.9"),
      parsed,
    )
  }

  // Snapshot codec round-trip.

  @Test
  fun snapshotCodec_roundTripPreservesMembers() {
    val snap = BridgeSnapshot(
      status = HotspotBridgeContract.STATUS_ON,
      ssid = "WhaleNet",
      security = "WPA2",
      clients = listOf(
        BridgeClient(id = "mac:AA:BB:CC:11:22:33", mac = "AA:BB:CC:11:22:33", addresses = listOf("192.168.43.7")),
        BridgeClient(id = "mac:11:22:33:44:55:66", mac = "11:22:33:44:55:66"),
      ),
      source = HotspotBridgeContract.SOURCE_SOFTAP,
      atMillis = 123L,
    )
    val decoded = HotspotBridgeProtocol.snapshotFromMap(HotspotBridgeProtocol.snapshotToMap(snap))
    assertEquals(HotspotBridgeContract.STATUS_ON, decoded.status)
    assertEquals("WhaleNet", decoded.ssid)
    assertEquals(2, decoded.clients?.size)
    assertEquals(listOf("192.168.43.7"), decoded.clients?.get(0)?.addresses)
    assertTrue(decoded.clients?.get(1)?.addresses.isNullOrEmpty())
  }

  @Test
  fun snapshotCodec_unknownWhenEmpty() {
    val decoded = HotspotBridgeProtocol.snapshotFromMap(emptyMap())
    assertEquals(HotspotBridgeContract.STATUS_UNKNOWN, decoded.status)
    assertNull(decoded.clients)
  }

  @Test
  fun snapshotCodec_rejectsBogusStatus() {
    val decoded = HotspotBridgeProtocol.snapshotFromMap(mapOf(HotspotBridgeContract.KEY_STATUS to "FROBNICATING"))
    assertEquals(HotspotBridgeContract.STATUS_UNKNOWN, decoded.status)
    val transient = HotspotBridgeProtocol.snapshotFromMap(mapOf(HotspotBridgeContract.KEY_STATUS to "STARTING"))
    assertEquals("STARTING", transient.status)
  }

  // Regression: the control-shape probe result survives the codec so the
  // repository gates canToggle on the live probe, not on assumptions.

  @Test
  fun snapshotCodec_roundTripsControlAvailable() {
    val verified = BridgeSnapshot(
      status = HotspotBridgeContract.STATUS_OFF,
      source = HotspotBridgeContract.SOURCE_UNKNOWN,
      controlAvailable = true,
    )
    assertEquals(true, HotspotBridgeProtocol.snapshotFromMap(HotspotBridgeProtocol.snapshotToMap(verified)).controlAvailable)
    val missing = BridgeSnapshot(
      status = HotspotBridgeContract.STATUS_OFF,
      source = HotspotBridgeContract.SOURCE_UNKNOWN,
      controlAvailable = false,
    )
    assertEquals(false, HotspotBridgeProtocol.snapshotFromMap(HotspotBridgeProtocol.snapshotToMap(missing)).controlAvailable)
    // Absent key (older helper) decodes to null, which locks the control.
    val legacy = HotspotBridgeProtocol.snapshotFromMap(emptyMap())
    assertNull(legacy.controlAvailable)
    assertFalse(
      HotspotBridgeProtocol.isToggleRequestEligible(
        HotspotBridgeContract.STATUS_ON, null, true, legacy.controlAvailable,
      ),
    )
  }

  // Tile hint: stateless, no cached bit.

  @Test
  fun tileHint_mapsObservedStateOnly() {
    assertEquals(TileHint.ACTIVE, HotspotBridgeProtocol.tileHintFor(HotspotBridgeContract.STATUS_ON))
    assertEquals(TileHint.INACTIVE, HotspotBridgeProtocol.tileHintFor(HotspotBridgeContract.STATUS_OFF))
    assertEquals(TileHint.UNAVAILABLE, HotspotBridgeProtocol.tileHintFor(HotspotBridgeContract.STATUS_UNKNOWN))
    assertEquals(TileHint.UNAVAILABLE, HotspotBridgeProtocol.tileHintFor(HotspotBridgeContract.STATUS_ERROR))
    assertEquals(TileHint.UNAVAILABLE, HotspotBridgeProtocol.tileHintFor(null))
  }

  // Shell / caller authorization (pure decisions, Binder-free).

  @Test
  fun supportedSdk_onlyApi30() {
    assertTrue(HotspotBridgeProtocol.isSupportedSdk(30))
    assertFalse(HotspotBridgeProtocol.isSupportedSdk(29))
    assertFalse(HotspotBridgeProtocol.isSupportedSdk(31))
    assertFalse(HotspotBridgeProtocol.isSupportedSdk(36))
  }

  @Test
  fun shellIdentity_onlyUid2000() {
    assertTrue(HotspotBridgeProtocol.isShellIdentity(2000))
    assertFalse(HotspotBridgeProtocol.isShellIdentity(0))
    assertFalse(HotspotBridgeProtocol.isShellIdentity(1000))
    assertFalse(HotspotBridgeProtocol.isShellIdentity(10245))
  }

  @Test
  fun callerAuthorized_appOrSelfOnly() {
    assertTrue(HotspotBridgeProtocol.isCallerAuthorized(2000, 10245, 2000))
    assertTrue(HotspotBridgeProtocol.isCallerAuthorized(10245, 10245, 2000))
    assertFalse(HotspotBridgeProtocol.isCallerAuthorized(1000, 10245, 2000))
    assertFalse(HotspotBridgeProtocol.isCallerAuthorized(9999, 10245, 2000))
  }

  // Request eligibility vs confirmation.

  @Test
  fun requestEligibility_needsReadyAndObservedOnOff() {
    assertTrue(
      HotspotBridgeProtocol.isToggleRequestEligible(
        HotspotBridgeContract.STATUS_ON, null, true, true,
      ),
    )
    assertTrue(
      HotspotBridgeProtocol.isToggleRequestEligible(
        HotspotBridgeContract.STATUS_OFF, null, true, true,
      ),
    )
    // Never optimistically On.
    assertFalse(
      HotspotBridgeProtocol.isToggleRequestEligible(
        HotspotBridgeContract.STATUS_UNKNOWN, null, true, true,
      ),
    )
    assertFalse(
      HotspotBridgeProtocol.isToggleRequestEligible(
        HotspotBridgeContract.STATUS_STARTING, null, true, true,
      ),
    )
    assertFalse(
      HotspotBridgeProtocol.isToggleRequestEligible(
        HotspotBridgeContract.STATUS_STOPPING, null, true, true,
      ),
    )
    assertFalse(
      HotspotBridgeProtocol.isToggleRequestEligible(
        HotspotBridgeContract.STATUS_ERROR, null, true, true,
      ),
    )
    // Authorization and proven service required.
    assertFalse(
      HotspotBridgeProtocol.isToggleRequestEligible(
        HotspotBridgeContract.STATUS_ON, null, false, true,
      ),
    )
    assertFalse(
      HotspotBridgeProtocol.isToggleRequestEligible(
        HotspotBridgeContract.STATUS_ON, false, true, true,
      ),
    )
  }

  // Regression: the exact API 30 control shape gates the request control.
  // Production: shell service probes the nested
  // TetheringManager$TetheringRequest start overload, the (int) Builder
  // ctor with exact build() return, and Wi-Fi-only stopTethering(int);
  // a missing/unusable shape reports controlAvailable false (or null
  // before the probe) and the UI must not offer the control.

  @Test
  fun requestEligibility_requiresVerifiedControlShape() {
    // Exact shape missing: locked even with READY + observed ON.
    assertFalse(
      HotspotBridgeProtocol.isToggleRequestEligible(
        HotspotBridgeContract.STATUS_ON, null, true, false,
      ),
    )
    // Probe not yet run: locked, never optimistically enabled.
    assertFalse(
      HotspotBridgeProtocol.isToggleRequestEligible(
        HotspotBridgeContract.STATUS_ON, null, true, null,
      ),
    )
    assertFalse(
      HotspotBridgeProtocol.isToggleRequestEligible(
        HotspotBridgeContract.STATUS_OFF, null, true, false,
      ),
    )
    assertFalse(
      HotspotBridgeProtocol.isToggleRequestEligible(
        HotspotBridgeContract.STATUS_OFF, null, true, null,
      ),
    )
    // Omitted control flag fails closed (defaults to null).
    assertFalse(
      HotspotBridgeProtocol.isToggleRequestEligible(
        HotspotBridgeContract.STATUS_ON, null, true,
      ),
    )
  }

  // Membership truthfulness.

  @Test
  fun authoritativeClients_offIsStableZero() {
    val resolved = HotspotBridgeProtocol.resolveAuthoritativeClients(
      status = HotspotBridgeContract.STATUS_OFF,
      membershipObserved = false,
      softApMacs = emptyList(),
    )
    assertEquals(emptyList<BridgeClient>(), resolved)
  }

  @Test
  fun authoritativeClients_onWithoutMembershipIsUnknown() {
    // State ON alone never justifies a zero roster.
    assertNull(
      HotspotBridgeProtocol.resolveAuthoritativeClients(
        status = HotspotBridgeContract.STATUS_ON,
        membershipObserved = false,
        softApMacs = emptyList(),
      ),
    )
  }

  @Test
  fun authoritativeClients_membershipObservedMergesAuthoritatively() {
    val resolved = HotspotBridgeProtocol.resolveAuthoritativeClients(
      status = HotspotBridgeContract.STATUS_ON,
      membershipObserved = true,
      softApMacs = listOf("AA:BB:CC:11:22:33"),
      addressesByMac = mapOf("AA:BB:CC:11:22:33" to listOf("192.168.43.7")),
    )
    assertEquals(1, resolved?.size)
    assertEquals(listOf("192.168.43.7"), resolved?.get(0)?.addresses)
    val emptyButObserved = HotspotBridgeProtocol.resolveAuthoritativeClients(
      status = HotspotBridgeContract.STATUS_ON,
      membershipObserved = true,
      softApMacs = emptyList(),
    )
    assertEquals(emptyList<BridgeClient>(), emptyButObserved)
  }
}
