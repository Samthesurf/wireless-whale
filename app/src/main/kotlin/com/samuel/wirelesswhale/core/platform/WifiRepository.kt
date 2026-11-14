package com.samuel.wirelesswhale.core.platform

import com.samuel.wirelesswhale.core.model.ScanState
import com.samuel.wirelesswhale.core.model.WifiNetwork
import com.samuel.wirelesswhale.core.model.WifiSnapshot
import kotlinx.coroutines.flow.StateFlow

interface WifiRepository {
  val connection: StateFlow<WifiSnapshot>
  val networks: StateFlow<List<WifiNetwork>>
  val scanState: StateFlow<ScanState>

  fun start()
  fun refreshPermissions()
  fun requestScan()
  fun close()
}
