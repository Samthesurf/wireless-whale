package com.samuel.wirelesswhale.core.domain

/**
 * Budget guarding WifiManager.startScan() calls. Android throttles foreground
 * scans (typically 4 per 2 minutes), so the repository enforces its own
 * budget first and reports [com.samuel.wirelesswhale.core.model.ScanState.throttled]
 * instead of burning framework quota.
 */
data class ScanBudget(
  val maxScans: Int = 4,
  val windowMillis: Long = 120_000L,
  val minIntervalMillis: Long = 30_000L,
)

/** Drops scan timestamps older than the budget window. */
fun pruneScanHistory(
  scanTimestampsMillis: List<Long>,
  nowMillis: Long,
  budget: ScanBudget = ScanBudget(),
): List<Long> = scanTimestampsMillis.filter { it > nowMillis - budget.windowMillis }

/** True when another scan may be requested right now under [budget]. */
fun canRequestScan(
  nowMillis: Long,
  scanTimestampsMillis: List<Long>,
  budget: ScanBudget = ScanBudget(),
): Boolean {
  val recent = pruneScanHistory(scanTimestampsMillis, nowMillis, budget)
  if (recent.size >= budget.maxScans) return false
  val last = recent.maxOrNull() ?: return true
  if (nowMillis < last) return false
  return nowMillis - last >= budget.minIntervalMillis
}

/** Milliseconds until the next scan is allowed, or 0 when a scan is allowed now. */
fun nextScanAllowedInMillis(
  nowMillis: Long,
  scanTimestampsMillis: List<Long>,
  budget: ScanBudget = ScanBudget(),
): Long {
  if (canRequestScan(nowMillis, scanTimestampsMillis, budget)) return 0L
  val recent = pruneScanHistory(scanTimestampsMillis, nowMillis, budget)
  val last = recent.maxOrNull() ?: return 0L
  val byInterval = (last + budget.minIntervalMillis) - nowMillis
  // The window only binds once the scan budget is full; otherwise the
  // per-scan cooldown is the only wait. An overfull history (more scans
  // than budget inside the window) must wait for enough entries to expire.
  if (recent.size < budget.maxScans) return maxOf(byInterval, 0L)
  val threshold = recent.sorted().let { it[it.size - budget.maxScans.coerceAtLeast(1)] }
  val byWindow = (threshold + budget.windowMillis) - nowMillis
  return maxOf(byInterval, byWindow, 0L)
}

/** True when [lastScanAtMillis] is recent enough to present without rescanning. */
fun isScanFresh(
  lastScanAtMillis: Long?,
  nowMillis: Long,
  freshnessMillis: Long = 60_000L,
): Boolean {
  if (lastScanAtMillis == null) return false
  if (nowMillis < lastScanAtMillis) return false
  return nowMillis - lastScanAtMillis <= freshnessMillis
}
