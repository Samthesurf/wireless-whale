package com.samuel.wirelesswhale.tiles

import android.annotation.SuppressLint

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Hotspot Quick Settings tile.
 *
 * Open-controls handoff: the normal app process has no public-SDK Soft AP
 * readback, so the tile never claims On or Off. It always renders the
 * honest inactive handoff state ("Open controls") and opens the real
 * app hotspot station on tap. Standard mode offers a system-controls
 * handoff there; enhanced mode shows the authorized observations and
 * controls. The tile never toggles a radio or starts its own daemon.
 */
class HotspotTileService : TileService() {

  override fun onStartListening() {
    super.onStartListening()
    val tile = qsTile ?: return
    tile.state = Tile.STATE_INACTIVE
    tile.label = "Hotspot"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      tile.subtitle = "Open controls"
    }
    tile.updateTile()
  }

  override fun onClick() {
    super.onClick()
    unlockAndRun {
      val intent = Intent(TileActions.HOTSPOT)
        .setPackage(packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      collapseAndOpen(intent)
    }
  }

  // PendingIntent collapse exists only on API 34+. The guarded legacy
  // branch is required on API 29-33; never call the Intent overload on 34+.
  @SuppressLint("StartActivityAndCollapseDeprecated")
  private fun collapseAndOpen(intent: Intent) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val pending = android.app.PendingIntent.getActivity(
          this,
          0,
          intent,
          android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        startActivityAndCollapse(pending)
      } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
      }
    } catch (e: Exception) {
      // Collapse rejected (e.g. foreground restrictions): fall back to a
      // plain launch so the user still reaches the app's hotspot station.
      try {
        startActivity(intent)
      } catch (ignored: Exception) {
        // Nothing further a tile can honestly do.
      }
    }
  }
}
