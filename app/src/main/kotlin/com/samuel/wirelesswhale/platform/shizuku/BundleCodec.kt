package com.samuel.wirelesswhale.platform.shizuku

import android.os.Bundle
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeProtocol.BridgeResult
import com.samuel.wirelesswhale.platform.shizuku.HotspotBridgeProtocol.BridgeSnapshot

/**
 * Thin Bundle adapters around [HotspotBridgeProtocol]'s Map codec.
 * No validation or policy lives here; all rules stay in the pure codec
 * so unit tests pin them without Android.
 */
object BundleCodec {
  private const val BUNDLE_CLIENTS = "clients_parcel"

  fun snapshotToBundle(s: BridgeSnapshot): Bundle {
    val map = HotspotBridgeProtocol.snapshotToMap(s)
    val out = Bundle()
    for ((k, v) in map) {
      when (v) {
        null -> Unit
        is Boolean -> out.putBoolean(k, v)
        is Number -> out.putLong(k, v.toLong())
        is String -> out.putString(k, v)
        else -> Unit
      }
    }
    val clients = (map[HotspotBridgeContract.KEY_CLIENTS] as? List<*>)?.mapNotNull { entry ->
      @Suppress("UNCHECKED_CAST")
      val e = entry as? Map<String, Any?> ?: return@mapNotNull null
      Bundle().also { b ->
        for ((k, v) in e) {
          when (v) {
            null -> Unit
            is Number -> b.putLong(k, v.toLong())
            is String -> b.putString(k, v)
            else -> Unit
          }
        }
      }
    }
    if (clients != null) out.putParcelableArrayList(BUNDLE_CLIENTS, ArrayList(clients))
    return out
  }

  fun snapshotFromBundle(b: Bundle?): BridgeSnapshot {
    if (b == null) return BridgeSnapshot()
    val map = HashMap<String, Any?>(b.size() + 4)
    for (key in b.keySet()) {
      if (key == BUNDLE_CLIENTS) continue
      map[key] = b.get(key)
    }
    val parcelClients = b.getParcelableArrayList<Bundle>(BUNDLE_CLIENTS)?.map { cb ->
      mapOf(
        HotspotBridgeContract.KEY_CLIENT_ID to cb.getString(HotspotBridgeContract.KEY_CLIENT_ID),
        HotspotBridgeContract.KEY_CLIENT_MAC to cb.getString(HotspotBridgeContract.KEY_CLIENT_MAC),
        HotspotBridgeContract.KEY_CLIENT_NAME to cb.getString(HotspotBridgeContract.KEY_CLIENT_NAME),
        HotspotBridgeContract.KEY_CLIENT_ADDRS to cb.getString(HotspotBridgeContract.KEY_CLIENT_ADDRS),
        HotspotBridgeContract.KEY_CLIENT_SEEN to
          if (cb.containsKey(HotspotBridgeContract.KEY_CLIENT_SEEN)) {
            cb.getLong(HotspotBridgeContract.KEY_CLIENT_SEEN)
          } else {
            null
          },
      )
    }
    if (parcelClients != null) map[HotspotBridgeContract.KEY_CLIENTS] = parcelClients
    return HotspotBridgeProtocol.snapshotFromMap(map)
  }

  fun resultToBundle(r: BridgeResult): Bundle {
    val out = Bundle()
    out.putBoolean(HotspotBridgeContract.KEY_OK, r.ok)
    out.putString(HotspotBridgeContract.KEY_CODE, r.code)
    out.putString(HotspotBridgeContract.KEY_OP, r.op)
    out.putString(HotspotBridgeContract.KEY_MESSAGE, r.message)
    return out
  }

  fun resultFromBundle(op: String, b: Bundle?): BridgeResult {
    if (b == null) return BridgeResult(ok = false, op = op)
    return HotspotBridgeProtocol.resultFromMap(
      op,
      mapOf(
        HotspotBridgeContract.KEY_OK to b.getBoolean(HotspotBridgeContract.KEY_OK, false),
        HotspotBridgeContract.KEY_CODE to b.getString(HotspotBridgeContract.KEY_CODE),
        HotspotBridgeContract.KEY_MESSAGE to b.getString(HotspotBridgeContract.KEY_MESSAGE),
      ),
    )
  }
}
