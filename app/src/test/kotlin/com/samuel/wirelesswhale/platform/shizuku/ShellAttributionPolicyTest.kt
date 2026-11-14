package com.samuel.wirelesswhale.platform.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellAttributionPolicyTest {
  @Test fun matchingShellProcessAndPackage_areEligible() {
    assertTrue(ShellAttributionPolicy.isEligible(2000, 2000, "com.android.shell"))
  }

  @Test fun rootAndOrdinaryApp_cannotClaimShellAttribution() {
    assertFalse(ShellAttributionPolicy.isEligible(0, 2000, "com.android.shell"))
    assertFalse(ShellAttributionPolicy.isEligible(10250, 2000, "com.android.shell"))
  }

  @Test fun anotherPackageOrPackageUid_isRejected() {
    assertFalse(ShellAttributionPolicy.isEligible(2000, 1000, "com.android.shell"))
    assertFalse(ShellAttributionPolicy.isEligible(2000, 2000, "android"))
    assertFalse(ShellAttributionPolicy.isEligible(2000, 2000, "com.samuel.wirelesswhale"))
    assertFalse(ShellAttributionPolicy.isEligible(2000, 2000, null))
  }
}
