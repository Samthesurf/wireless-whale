package com.samuel.wirelesswhale.platform.shizuku

/** Attribution is valid only for the actual shell process and its own package. */
internal object ShellAttributionPolicy {
  const val SHELL_PACKAGE = "com.android.shell"

  fun isEligible(processUid: Int, packageUid: Int, packageName: String?): Boolean =
    processUid == HotspotBridgeContract.EXPECTED_SHELL_UID &&
      packageUid == processUid && packageName == SHELL_PACKAGE
}
