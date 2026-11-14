package com.samuel.wirelesswhale

/**
 * Assembly owned pure policy for the app entry point.
 *
 * No Android framework references here on purpose: MainActivity passes plain
 * strings in, so this file stays unit testable on the JVM. It covers the two
 * parent review findings that must not regress:
 *
 * 1. Wi-Fi scan permission requests always bundle fine plus coarse location
 *    together on every SDK (Android 12 and above requires both in one
 *    request, including Android 13 and above), and never add nearby-devices
 *    for scans. Nearby permission is requested only where a feature actually
 *    needs peer APIs, and scanning works on location alone.
 * 2. The Android network approval screen (Settings.ACTION_WIFI_ADD_NETWORKS)
 *    returning RESULT_OK proves nothing about an individual network. The
 *    per-network list (Settings.EXTRA_WIFI_NETWORK_RESULT_LIST, integers
 *    mirroring Settings.ADD_WIFI_RESULT_* : 0 success, 1 add-or-update
 *    failed, 2 already exists) decides what may be claimed.
 */
object ScanPermissionPolicy {

  /**
   * Permissions a Wi-Fi scan needs, independent of SDK level.
   * Always fine plus coarse together; never nearby-devices for scans.
   */
  fun permissionsForScan(fine: String, coarse: String): List<String> = listOf(fine, coarse)

  /**
   * Returns the complete location request pair whenever either permission
   * is missing. Android 12+ requires coarse alongside fine even on a retry
   * after an approximate-only grant, so filtering out granted coarse would
   * make the fine-only retry invalid.
   */
  fun missingForScan(granted: Set<String>, fine: String, coarse: String): List<String> {
    val pair = permissionsForScan(fine, coarse)
    return if (pair.all { it in granted }) emptyList() else pair
  }
}

/** Truthful mapping of the network approval result to a user message. */
object AddNetworkSaveMapper {

  /** Mirrors Settings.ADD_WIFI_RESULT_SUCCESS. */
  const val RESULT_SUCCESS = 0

  /** Mirrors Settings.ADD_WIFI_RESULT_ADD_OR_UPDATE_FAILED. */
  const val RESULT_ADD_OR_UPDATE_FAILED = 1

  /** Mirrors Settings.ADD_WIFI_RESULT_ALREADY_EXISTS. */
  const val RESULT_ALREADY_EXISTS = 2

  sealed interface SaveOutcome {
    /** At least one network reported success. */
    data object SavedNew : SaveOutcome

    /** Every entry reported already-exists and none reported success. */
    data object AlreadyExists : SaveOutcome

    /** At least one entry reported failure or an unknown code. */
    data object Failed : SaveOutcome

    /** Rejected, closed, or no per-network evidence returned. */
    data object Dismissed : SaveOutcome
  }

  /**
   * Maps the approval activity result. RESULT_OK alone is never proof: a
   * missing or empty per-network list maps to [SaveOutcome.Dismissed] so the
   * caller cannot claim anything was saved.
   */
  fun outcome(resultOk: Boolean, codes: List<Int>?): SaveOutcome {
    if (!resultOk) return SaveOutcome.Dismissed
    if (codes.isNullOrEmpty()) return SaveOutcome.Dismissed
    if (codes.all { it == RESULT_SUCCESS }) return SaveOutcome.SavedNew
    if (codes.all { it == RESULT_ALREADY_EXISTS }) return SaveOutcome.AlreadyExists
    if (codes.any { it == RESULT_SUCCESS } && codes.all { it == RESULT_SUCCESS || it == RESULT_ALREADY_EXISTS }) {
      return SaveOutcome.SavedNew
    }
    return SaveOutcome.Failed
  }
}
