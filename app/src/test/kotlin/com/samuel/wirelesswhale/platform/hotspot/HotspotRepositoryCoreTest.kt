package com.samuel.wirelesswhale.platform.hotspot

import com.samuel.wirelesswhale.core.model.EnhancedAccessState
import com.samuel.wirelesswhale.core.model.HotspotProfile
import com.samuel.wirelesswhale.core.model.HotspotStatus
import com.samuel.wirelesswhale.core.model.OperationResult
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.CODE_OK
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.SOURCE_SOFTAP
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.STATUS_OFF
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.STATUS_ON
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeProtocol.BridgeClient
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeProtocol.BridgeResult
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeProtocol.BridgeSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HotspotRepositoryCoreTest {

  private class FakeBridge(
    initialAccess: EnhancedAccessState = EnhancedAccessState.NOT_INSTALLED,
  ) : EnhancedHotspotBridge {
    override val accessState = MutableStateFlow(initialAccess)
    override val accessMessage = MutableStateFlow<String?>(null)
    override val pushedSnapshot = MutableStateFlow<BridgeSnapshot?>(null)
    var fetchResult: BridgeSnapshot? = null
    var startResult: BridgeResult? = BridgeResult(ok = true, code = CODE_OK, op = "start")
    var stopResult: BridgeResult? = BridgeResult(ok = true, code = CODE_OK, op = "stop")
    var closed = false

    override fun start() = Unit
    override fun refreshAccess() = Unit
    override fun requestEnhancedAccess() = Unit
    override fun close() {
      closed = true
    }
    override suspend fun fetchSnapshot(): BridgeSnapshot? = fetchResult
    override suspend fun startTetheredHotspot(): BridgeResult? = startResult
    override suspend fun stopTethering(): BridgeResult? = stopResult
  }

  private fun onSnapshot() = BridgeSnapshot(
    status = STATUS_ON,
    ssid = "WhaleNet",
    clients = listOf(
      BridgeClient(id = "mac:AA:BB:CC:11:22:33", mac = "AA:BB:CC:11:22:33", addresses = listOf("192.168.43.7")),
      BridgeClient(id = "mac:11:22:33:44:55:66", mac = "11:22:33:44:55:66"),
    ),
    source = SOURCE_SOFTAP,
    atMillis = 7L,
    controlAvailable = true,
  )

  // Standard mode.

  @Test
  fun standard_offReadback_clientsNull_toggleLocked() = runTest {
    val core = HotspotRepositoryCore(
      enhanced = null,
      apReader = ApStateReader { ApRead(enabled = false, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    advanceUntilIdle()
    val s = core.state.value
    assertEquals(HotspotStatus.OFF, s.status)
    assertNull(s.clients) // unknown-by-design in standard mode, never fake zero
    assertFalse(s.canToggle)
    assertFalse(s.canConfigure)
    assertFalse(s.canBlockClients)
    assertFalse(s.clientUsageAvailable)
    assertFalse(s.hardQuotaAvailable)
    core.close()
  }

  @Test
  fun standard_setEnabled_handsOffToSystemUi() = runTest {
    val core = HotspotRepositoryCore(
      enhanced = null,
      apReader = ApStateReader { ApRead(enabled = false, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    val result = core.setEnabled(true)
    assertTrue(result is OperationResult.NeedsSystemUi)
    assertEquals(
      HotspotSystemIntents.TETHER_SETTINGS_ACTION,
      (result as OperationResult.NeedsSystemUi).action,
    )
    core.close()
  }

  @Test
  fun standard_permissionShortage_isUnknownWithMessage() = runTest {
    val core = HotspotRepositoryCore(
      enhanced = null,
      apReader = ApStateReader { ApRead(enabled = null, permissionRequired = true) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    advanceUntilIdle()
    val s = core.state.value
    assertEquals(HotspotStatus.UNKNOWN, s.status)
    assertNull(s.clients)
    assertTrue((s.message ?: "").isNotEmpty())
    core.close()
  }

  // Honest refusals.

  @Test
  fun blockClient_withoutVerifiedMechanism_failsHonestly() = runTest {
    val fake = FakeBridge(EnhancedAccessState.READY)
    val core = HotspotRepositoryCore(
      enhanced = fake,
      apReader = ApStateReader { ApRead(enabled = true, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    val bad = core.blockClient("nope", true)
    assertTrue(bad is OperationResult.Failure)
    val honest = core.blockClient("AA:BB:CC:11:22:33", true)
    assertTrue(honest is OperationResult.Failure)
    assertTrue((honest as OperationResult.Failure).message.contains("not proven live"))
    core.close()
  }

  @Test
  fun applyProfile_invalidFails_validHandsOff() = runTest {
    val fake = FakeBridge(EnhancedAccessState.READY)
    val core = HotspotRepositoryCore(
      enhanced = fake,
      apReader = ApStateReader { ApRead(enabled = false, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    val invalid = core.applyProfile(HotspotProfile(ssid = "", password = "x", band = 9))
    assertTrue(invalid is OperationResult.Failure)
    val valid = core.applyProfile(HotspotProfile(ssid = "WhaleNet", password = "password1", band = 2))
    assertTrue(valid is OperationResult.NeedsSystemUi)
    core.close()
  }

  // Enhanced mode mapping and evidence gating.

  @Test
  fun enhanced_readySnapshot_mapsClientsAndLocksCapabilities() = runTest {
    val fake = FakeBridge(EnhancedAccessState.READY).also { it.fetchResult = onSnapshot() }
    val core = HotspotRepositoryCore(
      enhanced = fake,
      apReader = ApStateReader { ApRead(enabled = null, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    advanceUntilIdle()
    val s = core.state.value
    assertEquals(HotspotStatus.ON, s.status)
    assertEquals("WhaleNet", s.ssid)
    assertEquals(2, s.clients?.size)
    assertEquals("AA:BB:CC:11:22:33", s.clients?.get(0)?.macAddress)
    assertEquals(listOf("192.168.43.7"), s.clients?.get(0)?.addresses)
    assertTrue(s.clients?.get(1)?.addresses.isNullOrEmpty())
    assertNull(s.clients?.get(0)?.receivedBytes) // never invented
    // Eligibility: READY + real ON observation enables the request
    // control. Confirmation is the setEnabled result, not this bit.
    assertTrue(s.canToggle)
    assertFalse(s.canConfigure)
    assertFalse(s.canBlockClients)
    assertFalse(s.hardQuotaAvailable)
    assertEquals("SoftAp", s.source)
    core.close()
  }

  @Test
  fun enhanced_stopRoundTrip_confirmsAndKeepsEligible() = runTest {
    val fake = FakeBridge(EnhancedAccessState.READY)
    fake.fetchResult = onSnapshot().copy(status = STATUS_OFF, clients = emptyList())
    val core = HotspotRepositoryCore(
      enhanced = fake,
      apReader = ApStateReader { ApRead(enabled = true, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    advanceUntilIdle()
    // OFF is already request-eligible before any round-trip.
    assertTrue(core.state.value.canToggle)
    val result = core.setEnabled(false)
    advanceUntilIdle()
    assertTrue(result is OperationResult.Success)
    val s = core.state.value
    assertEquals(HotspotStatus.OFF, s.status)
    assertEquals(emptyList<Any>(), s.clients)
    assertTrue(s.canToggle) // eligibility persists; confirmation was the Success
    core.close()
  }

  @Test
  fun enhanced_unconfirmedStart_isFailureButStaysEligible() = runTest {
    val fake = FakeBridge(EnhancedAccessState.READY)
    fake.fetchResult = onSnapshot().copy(status = STATUS_OFF) // accepted but not observed
    val core = HotspotRepositoryCore(
      enhanced = fake,
      apReader = ApStateReader { ApRead(enabled = false, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    advanceUntilIdle()
    val result = core.setEnabled(true)
    advanceUntilIdle()
    assertTrue(result is OperationResult.Failure)
    // OFF remains request-eligible; the Failure is the unconfirmed
    // confirmation, not a lockout.
    assertTrue(core.state.value.canToggle)
    core.close()
  }

  @Test
  fun enhanced_revoked_fallsBackToStandardRead() = runTest {
    val fake = FakeBridge(EnhancedAccessState.REVOKED)
    val core = HotspotRepositoryCore(
      enhanced = fake,
      apReader = ApStateReader { ApRead(enabled = false, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    advanceUntilIdle()
    val s = core.state.value
    assertEquals(EnhancedAccessState.REVOKED, s.enhancedAccess)
    assertEquals(HotspotStatus.OFF, s.status)
    core.close()
  }

  // Regression: eligibility vs confirmation are distinct.

  @Test
  fun enhanced_unknownSnapshot_neverEnablesToggle() = runTest {
    val fake = FakeBridge(EnhancedAccessState.READY).also {
      it.fetchResult = BridgeSnapshot(
        status = com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.STATUS_UNKNOWN,
        clients = null,
        source = com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.SOURCE_UNKNOWN,
        controlAvailable = true,
      )
    }
    val core = HotspotRepositoryCore(
      enhanced = fake,
      apReader = ApStateReader { ApRead(enabled = null, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    advanceUntilIdle()
    val s = core.state.value
    assertEquals(HotspotStatus.UNKNOWN, s.status)
    assertNull(s.clients)
    assertFalse(s.canToggle) // never optimistically On
    core.close()
  }

  @Test
  fun enhanced_onWithoutMembership_isUnknownNotZero() = runTest {
    // State observed ON, but membership callback never arrived:
    // clients must stay null (unknown), never an empty zero.
    val fake = FakeBridge(EnhancedAccessState.READY).also {
      it.fetchResult = BridgeSnapshot(
        status = STATUS_ON,
        ssid = "WhaleNet",
        clients = null,
        source = com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeContract.SOURCE_UNKNOWN,
        controlAvailable = true,
      )
    }
    val core = HotspotRepositoryCore(
      enhanced = fake,
      apReader = ApStateReader { ApRead(enabled = null, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    advanceUntilIdle()
    val s = core.state.value
    assertEquals(HotspotStatus.ON, s.status)
    assertNull(s.clients) // membership unknown, not zero
    assertTrue(s.canToggle) // adapter probe valid, request eligible
    core.close()
  }

  @Test
  fun enhanced_tetherUnsupported_locksToggle() = runTest {
    val fake = FakeBridge(EnhancedAccessState.READY).also {
      it.fetchResult = onSnapshot().copy(tetherSupported = false)
    }
    val core = HotspotRepositoryCore(
      enhanced = fake,
      apReader = ApStateReader { ApRead(enabled = null, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    advanceUntilIdle()
    assertFalse(core.state.value.canToggle)
    core.close()
  }

  // Regression: the shell control-shape probe gates the request control.
  // Production: an unusable start/request/Builder/stop shape (or a
  // snapshot from before the probe) reports controlAvailable false/null;
  // READY + observed ON must still not offer the control.

  @Test
  fun enhanced_unverifiedControlShape_locksToggle() = runTest {
    val fake = FakeBridge(EnhancedAccessState.READY).also {
      it.fetchResult = onSnapshot().copy(controlAvailable = false)
    }
    val core = HotspotRepositoryCore(
      enhanced = fake,
      apReader = ApStateReader { ApRead(enabled = null, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    advanceUntilIdle()
    val s = core.state.value
    assertEquals(HotspotStatus.ON, s.status) // observation still reported honestly
    assertFalse(s.canToggle) // but no request control without the verified shape
    core.close()
  }

  @Test
  fun enhanced_unprobedControlShape_locksToggle() = runTest {
    val fake = FakeBridge(EnhancedAccessState.READY).also {
      it.fetchResult = onSnapshot().copy(controlAvailable = null)
    }
    val core = HotspotRepositoryCore(
      enhanced = fake,
      apReader = ApStateReader { ApRead(enabled = null, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    advanceUntilIdle()
    assertFalse(core.state.value.canToggle)
    core.close()
  }

  @Test
  fun enhanced_startConfirmedByPoll_succeeds() = runTest {
    val fake = FakeBridge(EnhancedAccessState.READY)
    fake.fetchResult = onSnapshot() // ON observed
    val core = HotspotRepositoryCore(
      enhanced = fake,
      apReader = ApStateReader { ApRead(enabled = false, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    advanceUntilIdle()
    val result = core.setEnabled(true)
    advanceUntilIdle()
    assertTrue(result is OperationResult.Success)
    assertEquals(HotspotStatus.ON, core.state.value.status)
    core.close()
  }

  @Test
  fun enhanced_nullTransport_isUncertainFailure() = runTest {
    val fake = FakeBridge(EnhancedAccessState.READY).also {
      it.fetchResult = onSnapshot().copy(status = STATUS_OFF, clients = emptyList())
      it.startResult = null // helper did not answer
    }
    val core = HotspotRepositoryCore(
      enhanced = fake,
      apReader = ApStateReader { ApRead(enabled = false, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    advanceUntilIdle()
    val result = core.setEnabled(true)
    advanceUntilIdle()
    assertTrue(result is OperationResult.Failure)
    val message = (result as OperationResult.Failure).message
    assertTrue(message.contains("could not be confirmed"))
    assertFalse(message.contains("nothing was changed"))
    core.close()
  }

  // Regression: granted -> CONNECTING -> HELPER_UNAVAILABLE never loops to
  // permission. Production: Shizuku grant shown, helper loader dies before
  // any binder callback (Transsion/Infinix LoadedApk NPE), bridge timeout
  // reports unavailable; the UI must offer Retry/system settings, not Grant.
  @Test
  fun granted_connecting_helperFailure_isNotPermission() = runTest {
    val fake = FakeBridge(EnhancedAccessState.PERMISSION_REQUIRED)
    val core = HotspotRepositoryCore(
      enhanced = fake,
      apReader = ApStateReader { ApRead(enabled = null, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    advanceUntilIdle()
    assertEquals(EnhancedAccessState.PERMISSION_REQUIRED, core.state.value.enhancedAccess)

    fake.accessState.value = EnhancedAccessState.CONNECTING
    fake.accessMessage.value = "Access was granted. Connecting to the hotspot helper; this clears when it answers."
    advanceUntilIdle()
    assertEquals(EnhancedAccessState.CONNECTING, core.state.value.enhancedAccess)
    assertFalse(core.state.value.canToggle)

    fake.accessState.value = EnhancedAccessState.HELPER_UNAVAILABLE
    fake.accessMessage.value =
      "Access was granted but the hotspot helper did not start. Nothing was changed. Retry, or use system settings."
    advanceUntilIdle()
    val s = core.state.value
    assertEquals(EnhancedAccessState.HELPER_UNAVAILABLE, s.enhancedAccess)
    assertFalse(s.canToggle)
    assertTrue((s.message ?: "").contains("did not start"))
    assertTrue((s.message ?: "").contains("Nothing was changed"))
    core.close()
  }

  // Regression: delayed grant message arrives after the state change and
  // readiness follows with a real snapshot. Production: permission listener
  // moves granted -> CONNECTING, message flow updates, then UID 2000
  // handshake moves CONNECTING -> READY and the snapshot enables toggle.
  @Test
  fun delayedGrantMessage_thenReadiness_enablesToggle() = runTest {
    val fake = FakeBridge(EnhancedAccessState.PERMISSION_REQUIRED)
    val core = HotspotRepositoryCore(
      enhanced = fake,
      apReader = ApStateReader { ApRead(enabled = null, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    advanceUntilIdle()

    // Grant arrives: state first, message delayed one emission.
    fake.accessState.value = EnhancedAccessState.CONNECTING
    advanceUntilIdle()
    assertEquals(EnhancedAccessState.CONNECTING, core.state.value.enhancedAccess)
    fake.accessMessage.value = "Access was granted. Connecting to the hotspot helper; this clears when it answers."
    advanceUntilIdle()
    assertTrue((core.state.value.message ?: "").contains("Connecting"))

    // Helper answers: READY + real ON snapshot enables the request control.
    fake.fetchResult = onSnapshot()
    fake.accessState.value = EnhancedAccessState.READY
    advanceUntilIdle()
    val s = core.state.value
    assertEquals(EnhancedAccessState.READY, s.enhancedAccess)
    assertEquals(HotspotStatus.ON, s.status)
    assertEquals(2, s.clients?.size)
    assertTrue(s.canToggle)
    core.close()
  }

  // Regression (independent review 1): READY + null fetch must not leave a
  // prior ON/clients/canToggle visible as fresh. Production: helper answers
  // the handshake (READY) but the snapshot IPC times out or a buggy bridge
  // stays READY; the core must disable controls, preserve the last
  // trustworthy values with their age, and surface unavailable/stale.
  // Never invents OFF or zero clients.
  @Test
  fun ready_nullFetch_disablesControls_preservesStale() = runTest {
    val fake = FakeBridge(EnhancedAccessState.READY).also { it.fetchResult = onSnapshot() }
    val core = HotspotRepositoryCore(
      enhanced = fake,
      apReader = ApStateReader { ApRead(enabled = null, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    advanceUntilIdle()
    val fresh = core.state.value
    assertEquals(HotspotStatus.ON, fresh.status)
    assertEquals(2, fresh.clients?.size)
    assertTrue(fresh.canToggle)
    val age = fresh.lastUpdatedAtMillis

    // Transport failure on the next read while the bridge still claims READY.
    fake.fetchResult = null
    fake.accessMessage.value = null
    core.refresh()
    advanceUntilIdle()

    val s = core.state.value
    assertFalse(s.canToggle)
    assertFalse(s.canConfigure)
    assertFalse(s.canBlockClients)
    // Last trustworthy values preserved with age, never invented OFF/zero.
    assertEquals(HotspotStatus.ON, s.status)
    assertEquals("WhaleNet", s.ssid)
    assertEquals(2, s.clients?.size)
    assertEquals(age, s.lastUpdatedAtMillis)
    val message = (s.message ?: "").lowercase()
    assertTrue(message.contains("unavailable") || message.contains("stale") || message.contains("did not answer"))
    core.close()
  }

  // Regression (independent review 1): READY + null fetch with no prior
  // good snapshot stays UNKNOWN/null, never OFF/zero, with controls locked.
  @Test
  fun ready_nullFetchWithNoPrior_neverInventsOffOrZero() = runTest {
    val fake = FakeBridge(EnhancedAccessState.READY).also { it.fetchResult = null }
    val core = HotspotRepositoryCore(
      enhanced = fake,
      apReader = ApStateReader { ApRead(enabled = null, permissionRequired = false) },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    core.start()
    advanceUntilIdle()
    val s = core.state.value
    assertEquals(HotspotStatus.UNKNOWN, s.status)
    assertNull(s.clients)
    assertFalse(s.canToggle)
    assertFalse(s.canConfigure)
    assertFalse(s.canBlockClients)
    val message = (s.message ?: "").lowercase()
    assertTrue(message.contains("unavailable") || message.contains("stale") || message.contains("did not answer"))
    core.close()
  }
}
