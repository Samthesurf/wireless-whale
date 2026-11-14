package com.samuel.wirelesswhale.bridge;

/** Push path for hotspot snapshot changes from the UserService process. */
interface IHotspotEventCallback {
  /** A HotspotBridgeContract snapshot Bundle; small, no credentials. */
  void onSnapshotChanged(in Bundle snapshot);
}
