package com.samuel.wirelesswhale.data

import android.content.Context
import com.samuel.wirelesswhale.diagnostics.DiagnosticsSupport

/**
 * Local display nicknames for observed client identities (MAC / client id).
 *
 * A nickname renames nothing on the remote device; it is a local label shown
 * next to the observed identity. Setting a nickname for an id that was never
 * observed is allowed (the UI decides when the affordance appears), but this
 * store never invents the underlying identity itself.
 *
 * Input is untrusted: [set] cleans via [DiagnosticsSupport.cleanNickname]
 * (control characters stripped, whitespace collapsed, capped at
 * [DiagnosticsSupport.NICKNAME_MAX_LENGTH] code points). A value with nothing
 * displayable left removes the nickname instead of storing an empty label.
 * Blank ids are ignored on both [get] and [set].
 */
class ClientNicknameStore(context: Context) {

  private val prefs =
    context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

  companion object {
    const val PREFS_FILE = "wireless_whale_nicknames"
    const val KEY_PREFIX = "nick:"
  }

  fun get(id: String): String? {
    if (id.isBlank()) return null
    val stored = prefs.getString(KEY_PREFIX + id, null) ?: return null
    return stored.takeIf { DiagnosticsSupport.isValidNickname(it) }
  }

  fun set(id: String, nickname: String) {
    if (id.isBlank()) return
    val cleaned = DiagnosticsSupport.cleanNickname(nickname)
    prefs.edit().apply {
      if (cleaned.isEmpty()) remove(KEY_PREFIX + id) else putString(KEY_PREFIX + id, cleaned)
    }.apply()
  }
}
