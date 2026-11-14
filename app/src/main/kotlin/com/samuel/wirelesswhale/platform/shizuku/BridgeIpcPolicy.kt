package com.samuel.wirelesswhale.platform.shizuku

import com.samuel.wirelesswhale.core.model.EnhancedAccessState

/**
 * Pure IPC failure classification for the shell hotspot bridge.
 *
 * Production link: ShizukuHotspotBridge typed calls (snapshot/start/stop)
 * and the UID 2000 handshake run over Binder with a timeout. A null reply,
 * timeout, or RemoteException means the helper did not answer (transport)
 * and must surface HELPER_UNAVAILABLE. A SecurityException means the grant
 * or shell identity failed (auth) and must surface REVOKED. Cancellation
 * is never a failure: callers rethrow it. Demotion is only applied when
 * the failing call is still current (started, same generation, same binder)
 * so close() and superseded binds are never clobbered by late IPC.
 */
internal object BridgeIpcPolicy {
  enum class IpcFailure { TRANSPORT, AUTH }

  /**
   * Classifies a Binder failure. Null means timeout or a null reply
   * bundle: both are transport failures, never auth.
   */
  fun classify(throwable: Throwable?): IpcFailure =
    if (throwable is SecurityException) IpcFailure.AUTH else IpcFailure.TRANSPORT

  fun accessStateFor(failure: IpcFailure): EnhancedAccessState =
    when (failure) {
      IpcFailure.AUTH -> EnhancedAccessState.REVOKED
      IpcFailure.TRANSPORT -> EnhancedAccessState.HELPER_UNAVAILABLE
    }

  fun messageFor(failure: IpcFailure): String =
    when (failure) {
      IpcFailure.AUTH ->
        "Shizuku access was revoked or the helper identity changed. " +
          "Re-authorize Shizuku access to continue. Nothing was changed."
      IpcFailure.TRANSPORT ->
        "Access was granted but the hotspot helper did not answer. Nothing was changed. " +
          "Retry, or use system settings."
    }

  /**
   * True only when a failure/success payload is still current and may
   * mutate bridge state. Guards late IPC after close() and superseded
   * binds that arrived after a newer binder took over.
   */
  fun shouldApplyResult(
    started: Boolean,
    capturedEpoch: Int,
    currentEpoch: Int,
    binderStillCurrent: Boolean,
  ): Boolean = started && capturedEpoch == currentEpoch && binderStillCurrent
}
