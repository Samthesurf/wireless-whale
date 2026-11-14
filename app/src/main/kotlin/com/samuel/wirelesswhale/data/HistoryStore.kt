package com.samuel.wirelesswhale.data

import android.content.Context
import com.samuel.wirelesswhale.core.model.HistoryEntry
import com.samuel.wirelesswhale.diagnostics.DiagnosticsSupport
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small bounded local history of session/diagnostic events, newest first.
 *
 * Storage (first working slice): a single SharedPreferences file holding one
 * JSON array string. This is deliberately NOT Room: it keeps the first slice
 * dependency-free and reviewable. Consequences, stated openly:
 * - No schema migrations exist. If a future slice moves to Room/DataStore, a
 *   one-way import from this key must be written and tested then; nothing
 *   here pretends that path already exists.
 * - This is plain app-private storage, NOT encrypted credential storage. Do
 *   not present it as such in UI.
 * - History holds metadata only: titles, redacted details, timestamps, kinds.
 *   It never stores passwords, raw QR credential payloads, or saved network
 *   secrets. [add] redacts via [DiagnosticsSupport.redactSecrets] and
 *   [exportRedacted] re-redacts on the way out.
 *
 * Bounds: at most [DiagnosticsSupport.HISTORY_MAX_ENTRIES] entries; the oldest
 * are dropped. Titles/details/kinds are length-capped. Mutation methods are
 * safe to call from any thread; [entries] replays the latest list to collectors.
 */
class HistoryStore(context: Context) {

  private val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
  private val lock = Any()

  private val _entries = MutableStateFlow(loadLocked())
  val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

  companion object {
    const val PREFS_FILE = "wireless_whale_history"
    const val KEY_ENTRIES = "entries_v1"
  }

  /**
   * Records one event. Blank titles are ignored. Secrets in [title]/[detail]
   * are redacted before storage; over-long fields are truncated.
   */
  fun add(title: String, detail: String, kind: String) {
    val cleanTitle = DiagnosticsSupport.truncate(title.trim(), DiagnosticsSupport.HISTORY_TITLE_MAX)
    if (cleanTitle.isEmpty()) return
    val entry = HistoryEntry(
      id = UUID.randomUUID().toString(),
      title = DiagnosticsSupport.redactSecrets(cleanTitle),
      detail = DiagnosticsSupport.truncate(
        DiagnosticsSupport.redactSecrets(detail.trim()),
        DiagnosticsSupport.HISTORY_DETAIL_MAX,
      ),
      atMillis = System.currentTimeMillis(),
      kind = DiagnosticsSupport.truncate(
        kind.trim().ifEmpty { "note" },
        DiagnosticsSupport.HISTORY_KIND_MAX,
      ),
    )
    synchronized(lock) {
      val updated = DiagnosticsSupport.trimHistory(listOf(entry) + _entries.value)
      persistLocked(updated)
      _entries.value = updated
    }
  }

  /** Deletes all local history, in memory and on disk. */
  fun clear() {
    synchronized(lock) {
      prefs.edit().remove(KEY_ENTRIES).apply()
      _entries.value = emptyList()
    }
  }

  /** Redacted JSON array export of the current entries (newest first). */
  fun exportRedacted(): String = DiagnosticsSupport.buildHistoryExport(_entries.value)

  private fun loadLocked(): List<HistoryEntry> {
    val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
    return runCatching { parse(raw) }.getOrElse { emptyList() }
  }

  private fun persistLocked(entries: List<HistoryEntry>) {
    val array = JSONArray()
    for (entry in entries) {
      array.put(
        JSONObject()
          .put("id", entry.id)
          .put("title", entry.title)
          .put("detail", entry.detail)
          .put("atMillis", entry.atMillis)
          .put("kind", entry.kind),
      )
    }
    prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
  }

  private fun parse(raw: String): List<HistoryEntry> {
    val array = JSONArray(raw)
    val out = ArrayList<HistoryEntry>(array.length())
    for (i in 0 until array.length()) {
      val o = array.optJSONObject(i) ?: continue
      out.add(
        HistoryEntry(
          id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
          title = o.optString("title"),
          detail = o.optString("detail"),
          atMillis = o.optLong("atMillis", 0L),
          kind = o.optString("kind").ifEmpty { "note" },
        ),
      )
    }
    return DiagnosticsSupport.trimHistory(out)
  }
}
