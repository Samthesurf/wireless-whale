package com.samuel.wirelesswhale.platform.hotspot

import android.content.Context
import com.samuel.wirelesswhale.core.model.HotspotProfile
import com.samuel.wirelesswhale.core.model.HotspotSnapshot
import com.samuel.wirelesswhale.core.model.OperationResult
import com.samuel.wirelesswhale.core.platform.HotspotRepository
import com.samuel.wirelesswhale.platform.shizuku.ShizukuHotspotBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * Exact contract implementation: `AndroidHotspotRepository(context)`.
 *
 * Thin wrapper: all policy lives in [HotspotRepositoryCore] so unit tests
 * can drive the core with fakes and no Android runtime. Production wiring
 * (Shizuku bridge) is built here only. Standard mode has no public-SDK
 * Soft AP readback, so it honestly reports unknown until the enhanced
 * bridge observes a real state or the user takes the system handoff.
 */
class AndroidHotspotRepository(
  context: Context,
) : HotspotRepository {

  private val core: HotspotRepositoryCore by lazy {
    val appContext = context.applicationContext
    HotspotRepositoryCore(
      enhanced = runCatching { ShizukuHotspotBridge(appContext) }.getOrNull(),
      apReader = {
        // No public-SDK readback exists for the Soft AP switch in the
        // normal app process (WifiManager.isWifiApEnabled is hidden, not
        // public SDK), so standard mode honestly reports unknown until a
        // real allowed observation or the system-settings handoff. Never
        // a false OFF to make a call site compile.
        ApRead(null, false)
      },
      dispatcher = Dispatchers.Default,
    )
  }

  override val state: StateFlow<HotspotSnapshot>
    get() = core.state

  override fun start() = core.start()

  override fun refresh() = core.refresh()

  override fun requestEnhancedAccess() = core.requestEnhancedAccess()

  override suspend fun setEnabled(enabled: Boolean): OperationResult = core.setEnabled(enabled)

  override suspend fun blockClient(id: String, blocked: Boolean): OperationResult =
    core.blockClient(id, blocked)

  override suspend fun applyProfile(profile: HotspotProfile): OperationResult =
    core.applyProfile(profile)

  override fun close() = core.close()
}

/** Test seam for the Soft AP switch readback. */
internal fun interface ApStateReader {
  fun read(): ApRead
}

internal data class ApRead(val enabled: Boolean?, val permissionRequired: Boolean)
