package com.samuel.wirelesswhale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssemblyPolicyTest {

  private val fine = "android.permission.ACCESS_FINE_LOCATION"
  private val coarse = "android.permission.ACCESS_COARSE_LOCATION"

  @Test fun `scan always requests fine plus coarse together`() {
    assertEquals(listOf(fine, coarse), ScanPermissionPolicy.permissionsForScan(fine, coarse))
  }

  @Test fun `scan never adds nearby-devices for location scans`() {
    val requested = ScanPermissionPolicy.permissionsForScan(fine, coarse)
    assertTrue(requested.none { it.contains("NEARBY", ignoreCase = true) })
  }

  @Test fun `approximate-only retry still bundles fine and coarse`() {
    assertEquals(
      listOf(fine, coarse),
      ScanPermissionPolicy.missingForScan(setOf(coarse), fine, coarse),
    )
  }

  @Test fun `missing scan permissions empty when both granted`() {
    assertTrue(ScanPermissionPolicy.missingForScan(setOf(fine, coarse), fine, coarse).isEmpty())
  }

  @Test fun `missing scan permissions requests both when none granted`() {
    assertEquals(
      listOf(fine, coarse),
      ScanPermissionPolicy.missingForScan(emptySet(), fine, coarse),
    )
  }

  @Test fun `save result canceled maps to dismissed`() {
    assertEquals(
      AddNetworkSaveMapper.SaveOutcome.Dismissed,
      AddNetworkSaveMapper.outcome(false, listOf(AddNetworkSaveMapper.RESULT_SUCCESS)),
    )
  }

  @Test fun `save result ok with success code maps to saved`() {
    assertEquals(
      AddNetworkSaveMapper.SaveOutcome.SavedNew,
      AddNetworkSaveMapper.outcome(true, listOf(AddNetworkSaveMapper.RESULT_SUCCESS)),
    )
  }

  @Test fun `save result ok with already-exists maps to already exists`() {
    assertEquals(
      AddNetworkSaveMapper.SaveOutcome.AlreadyExists,
      AddNetworkSaveMapper.outcome(true, listOf(AddNetworkSaveMapper.RESULT_ALREADY_EXISTS)),
    )
  }

  @Test fun `save result ok with failure code maps to failed`() {
    assertEquals(
      AddNetworkSaveMapper.SaveOutcome.Failed,
      AddNetworkSaveMapper.outcome(true, listOf(AddNetworkSaveMapper.RESULT_ADD_OR_UPDATE_FAILED)),
    )
  }

  @Test fun `save result ok with unknown code maps to failed`() {
    assertEquals(
      AddNetworkSaveMapper.SaveOutcome.Failed,
      AddNetworkSaveMapper.outcome(true, listOf(99)),
    )
  }

  @Test fun `save result ok without per-network list never claims saved`() {
    assertEquals(
      AddNetworkSaveMapper.SaveOutcome.Dismissed,
      AddNetworkSaveMapper.outcome(true, null),
    )
    assertEquals(
      AddNetworkSaveMapper.SaveOutcome.Dismissed,
      AddNetworkSaveMapper.outcome(true, emptyList()),
    )
  }
}
