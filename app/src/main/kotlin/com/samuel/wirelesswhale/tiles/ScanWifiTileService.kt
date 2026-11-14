package com.samuel.wirelesswhale.tiles

import android.annotation.SuppressLint

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Scan Quick Settings tile.
 *
 * Unlocks first (camera and credential handling stay in the foreground
 * activity), collapses Quick Settings, then fires the exact SCAN_QR action
 * the app handles. No credentials or client names ever touch the tile.
 */
class ScanWifiTileService : TileService() {

  override fun onClick() {
    super.onClick()
    unlockAndRun {
      val intent = Intent(TileActions.SCAN_QR)
        .setPackage(packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      collapseAndSend(intent)
    }
  }

  // PendingIntent collapse exists only on API 34+. The guarded legacy
  // branch is required on API 29-33; never call the Intent overload on 34+.
  @SuppressLint("StartActivityAndCollapseDeprecated")
  private fun collapseAndSend(intent: Intent) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val pending = PendingIntent.getActivity(
          this,
          0,
          intent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startActivityAndCollapse(pending)
      } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
      }
    } catch (e: Exception) {
      try {
        startActivity(intent)
      } catch (ignored: Exception) {
        // Best effort from a tile; the app entry point remains.
      }
    }
  }
}
