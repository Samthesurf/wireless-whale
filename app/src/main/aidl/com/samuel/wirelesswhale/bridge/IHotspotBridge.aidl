package com.samuel.wirelesswhale.bridge;

import com.samuel.wirelesswhale.bridge.IHotspotEventCallback;

/**
 * Typed IPC surface for the Wireless Whale hotspot UserService.
 *
 * The service runs as shell UID 2000 via Shizuku (non-root). Every method
 * exchanges small versioned Bundles defined by HotspotBridgeContract; there
 * is intentionally no generic command runner, shell endpoint, or raw-dump
 * ferry. Passwords are never returned in any response.
 *
 * The last method, destroy(), asks the service to unregister its callbacks
 * and terminate its own process. The Shizuku server additionally destroys
 * replaced services with binder transaction 16777115, which the service
 * handles in onTransact and also ends in System.exit().
 */
interface IHotspotBridge {
  /** Bridge protocol version (HotspotBridgeContract.BRIDGE_VERSION). */
  int getBridgeVersion();

  /**
   * Read-only handshake: returns uid, shell-identity flag, package, and
   * feature probes. Never changes radio state.
   */
  Bundle handshake(in Bundle request);

  /** Current hotspot snapshot as a Bundle (see HotspotBridgeContract). */
  Bundle getSnapshot();

  /**
   * Start internet tethering with the system's current Soft AP
   * configuration. Config Bundle may carry an optional validated profile;
   * applying an unverified profile is rejected until proven live.
   */
  Bundle startTetheredHotspot(in Bundle config);

  /** Stop Wi-Fi tethering. */
  Bundle stopTethering();

  /**
   * Validate (and, only once a vendor mechanism is proven live, apply) a
   * hotspot profile. Until then returns an explicit unsupported result.
   */
  Bundle applyProfile(in Bundle profile);

  /**
   * Allow or block one associated client. Returns explicit unsupported
   * until a vendor block mechanism is proven live on this device.
   */
  Bundle setClientBlocked(in Bundle request);

  void registerEventCallback(IHotspotEventCallback callback);

  void unregisterEventCallback(IHotspotEventCallback callback);

  /** Unregister callbacks and terminate the service process. */
  void destroy();
}
