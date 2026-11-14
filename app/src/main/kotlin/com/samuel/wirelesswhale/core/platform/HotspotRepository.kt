package com.samuel.wirelesswhale.core.platform

import com.samuel.wirelesswhale.core.model.HotspotProfile
import com.samuel.wirelesswhale.core.model.HotspotSnapshot
import com.samuel.wirelesswhale.core.model.OperationResult
import kotlinx.coroutines.flow.StateFlow

interface HotspotRepository {
  val state: StateFlow<HotspotSnapshot>

  fun start()
  fun refresh()
  fun requestEnhancedAccess()
  suspend fun setEnabled(enabled: Boolean): OperationResult
  suspend fun blockClient(id: String, blocked: Boolean): OperationResult
  suspend fun applyProfile(profile: HotspotProfile): OperationResult
  fun close()
}
