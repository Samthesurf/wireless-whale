package com.samuel.wirelesswhale.feature.scanner

/**
 * Pure gating for single-shot scanner delivery.
 *
 * The camera analyzer and the Storage Access Framework import race the
 * screen leaving composition. A result must only fire when the screen is
 * still alive, the host lifecycle is at least STARTED, and no result has
 * already been consumed. A skipped delivery (paused or disposed host)
 * must reset the consumed flag on resume instead of wedging the scanner
 * in a consumed state.
 */
object ScanResultGate {
  fun canDeliver(
    disposed: Boolean,
    lifecycleStarted: Boolean,
    alreadyConsumed: Boolean
  ): Boolean = !disposed && lifecycleStarted && !alreadyConsumed

  fun shouldResetConsumed(
    disposed: Boolean,
    lifecycleStarted: Boolean
  ): Boolean = disposed || !lifecycleStarted
}
