package com.samuel.wirelesswhale.platform.shizuku

import com.samuel.wirelesswhale.core.model.EnhancedAccessState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeIpcPolicyTest {

  // Production link: typed snapshot/start/stop IPC runs over Binder with a
  // timeout. A null reply, timeout, or RemoteException means the helper did
  // not answer (transport). A SecurityException means the grant or shell
  // identity failed (auth). These pins keep the demotion honest.

  @Test
  fun nullReply_isTransport() {
    assertEquals(
      BridgeIpcPolicy.IpcFailure.TRANSPORT,
      BridgeIpcPolicy.classify(null),
    )
  }

  @Test
  fun genericException_isTransport() {
    assertEquals(
      BridgeIpcPolicy.IpcFailure.TRANSPORT,
      BridgeIpcPolicy.classify(RuntimeException("binder died")),
    )
  }

  @Test
  fun securityException_isAuth() {
    assertEquals(
      BridgeIpcPolicy.IpcFailure.AUTH,
      BridgeIpcPolicy.classify(SecurityException("revoked")),
    )
  }

  @Test
  fun transport_mapsToHelperUnavailable() {
    val failure = BridgeIpcPolicy.IpcFailure.TRANSPORT
    assertEquals(EnhancedAccessState.HELPER_UNAVAILABLE, BridgeIpcPolicy.accessStateFor(failure))
    val message = BridgeIpcPolicy.messageFor(failure)
    assertTrue(message.contains("did not answer"))
    assertTrue(message.contains("Nothing was changed"))
  }

  @Test
  fun auth_mapsToRevoked() {
    val failure = BridgeIpcPolicy.IpcFailure.AUTH
    assertEquals(EnhancedAccessState.REVOKED, BridgeIpcPolicy.accessStateFor(failure))
    val message = BridgeIpcPolicy.messageFor(failure)
    assertTrue(message.contains("Re-authorize"))
    assertTrue(message.contains("Nothing was changed"))
  }

  // Production link: late IPC after close() and superseded binds must never
  // demote or revive. The bridge captures an epoch at IPC start and compares
  // service identity at apply time.

  @Test
  fun shouldApply_trueWhenCurrent() {
    assertTrue(BridgeIpcPolicy.shouldApplyResult(true, 3, 3, true))
  }

  @Test
  fun shouldApply_falseAfterClose() {
    assertFalse(BridgeIpcPolicy.shouldApplyResult(false, 3, 3, true))
  }

  @Test
  fun shouldApply_falseOnStaleEpoch() {
    // close() bumps the epoch: a payload captured before close is stale.
    assertFalse(BridgeIpcPolicy.shouldApplyResult(true, 3, 4, true))
  }

  @Test
  fun shouldApply_falseWhenSuperseded() {
    // A newer binder replaced this call's service: stale failure must not
    // clobber the newer READY.
    assertFalse(BridgeIpcPolicy.shouldApplyResult(true, 3, 3, false))
  }
}
