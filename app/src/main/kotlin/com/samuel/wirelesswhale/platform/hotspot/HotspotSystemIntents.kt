package com.samuel.wirelesswhale.platform.hotspot

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * System UI entry points for hotspot control.
 *
 * Standard access never toggles the hotspot directly: it hands off to the
 * real system controls. Both tiles and the repository share these so the
 * NeedsSystemUi action string and the launched screen always agree.
 */
object HotspotSystemIntents {
  /**
   * Action carried by OperationResult.NeedsSystemUi when standard access
   * must hand off. Kept as a string literal because
   * Settings.ACTION_TETHER_SETTINGS is not public SDK.
   */
  const val TETHER_SETTINGS_ACTION = "android.settings.TETHER_SETTINGS"

  /** Verified system screen for the internet hotspot switch and config. */
  fun tetherSettingsIntent(): Intent =
    Intent(TETHER_SETTINGS_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  /** Fallback when the OEM build omits the tether screen. */
  fun settingsFallbackIntent(): Intent =
    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  /**
   * Tether screen when the system resolves it, otherwise the public
   * Settings fallback. Never throws: an unresolvable OEM screen still
   * lands the user on real system controls.
   */
  fun resolvedTetherIntent(context: Context): Intent {
    val tether = tetherSettingsIntent()
    val resolvable = runCatching {
      tether.resolveActivity(context.packageManager) != null
    }.getOrDefault(false)
    return if (resolvable) tether else settingsFallbackIntent()
  }
}
