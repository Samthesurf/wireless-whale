# Shizuku hotspot backend — parent integration requirements

Owner: hotspot worker. Parent implements everything in this file; the
worker owns no manifest, build-file, or MainActivity edits.

## 1. Build changes (parent)

- `app/build.gradle.kts`: `buildFeatures.aidl = true` is enabled. The two
  AIDL files below require it. No hidden platform stubs are added: Shizuku `api` + `provider` 13.1.5 are already pinned in
  `gradle/libs.versions.toml` and already on `implementation` in the app
  module.
- Do NOT add hidden-API stub jars, `HiddenApiRefinePlugin`, or any
  non-SDK bypass: the design compiles against public SDK + Shizuku + AIDL
  only. Reflection lives solely in `HotspotUserService` (shell process).

## 2. AIDL (already created by worker, parent only enables §1)

- `app/src/main/aidl/com/samuel/wirelesswhale/bridge/IHotspotBridge.aidl`
- `app/src/main/aidl/com/samuel/wirelesswhale/bridge/IHotspotEventCallback.aidl`

Generated Java lands in `com.samuel.wirelesswhale.bridge.*` and is consumed
by `platform/shizuku/HotspotUserService.kt` and `ShizukuHotspotBridge.kt`.

## 3. Manifest additions (parent)

Inside `<application>`:

```xml
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:enabled="true"
    android:exported="true"
    android:multiprocess="false"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

Tiles (labels only; no behavior in manifest):

```xml
<service
    android:name="com.samuel.wirelesswhale.tiles.HotspotTileService"
    android:label="Hotspot"
    android:icon="@mipmap/ic_launcher"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
</service>
<service
    android:name="com.samuel.wirelesswhale.tiles.ScanWifiTileService"
    android:label="Scan Wi-Fi"
    android:icon="@mipmap/ic_launcher"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
</service>
```

Permissions to add (all install-time; no new runtime prompts):

```xml
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="moe.shizuku.manager.permission.API_V23" />
```

Notes: `isWifiApEnabled` is hidden and is not called by the normal app or
tile. Standard mode keeps hotspot state unknown without an actual permitted
observation. The tile opens the app hotspot station without an On/Off claim;
that station offers the real system-settings handoff. The Shizuku manager
permission lets `Shizuku.checkSelfPermission` / `requestPermission` work;
the actual grant is the user's explicit Shizuku authorization, requested
from a foreground gesture via `HotspotRepository.requestEnhancedAccess()`.

## 4. MainActivity intent routing (parent)

- `ScanWifiTileService` fires explicit action
  `com.samuel.wirelesswhale.action.SCAN_QR` (see `TileActions`) at this
  package. Route it to the scanner entry: it must NOT auto-connect, must
  NOT fire from the lock screen without the tile's `unlockAndRun` (already
  enforced in the tile), and camera permission stays in the foreground
  activity.
- `HotspotTileService` sends `com.samuel.wirelesswhale.action.HOTSPOT`
  to the app's hotspot screen after unlock. It does not toggle radios from
  the tile, create a permanent daemon, or label unknown state as Off.

## 5. R8 / ProGuard (parent, required)

The Shizuku server identifies the UserService by tag + version, not class
name, but the class must still survive shrinking with its exact
constructors (Shizuku v13 reflectively tries `(Context)` first, then `()`):

```
-keep class com.samuel.wirelesswhale.bridge.** { *; }
-keep class com.samuel.wirelesswhale.platform.shizuku.HotspotUserService {
    public <init>(...);
}
-keepnames class com.samuel.wirelesswhale.platform.shizuku.HotspotUserService
```

`HotspotBridgeContract.BRIDGE_TAG` (`"ww-hotspot-bridge"`) and
`BRIDGE_VERSION` (1) are string/int literals safe under R8; bump the
version only when the service code changes, since a mismatch makes the
server destroy and replace the running service. Service identity key is
`com.samuel.wirelesswhale:ww-hotspot-bridge`, daemon mode is OFF
(non-persistent; no permanent daemon from tiles or the app).

The server destroy envelope was checked against the API submodule pinned by
Shizuku v13.6.0, commit `510fc988c02c3475d8c25db170f96792f105bdf8`.
`UserServiceRecord.destroy()` writes the service's interface descriptor into
the Parcel before sending the destroy transaction. Keeping the helper's
interface-token check and caller gate is therefore correct for this version;
an empty destroy Parcel must not be assumed. This source check does not prove
physical process replacement or cleanup ordering on the phone.
Source: https://raw.githubusercontent.com/RikkaApps/Shizuku-API/510fc988c02c3475d8c25db170f96792f105bdf8/server-shared/src/main/java/rikka/shizuku/server/UserServiceRecord.java

## 6. Wiring the repository (parent)

- Instantiate `AndroidHotspotRepository(context)` once in app assembly,
  call `start()` on launch scope and `close()` on teardown. Constructor
  signature is frozen by the shared contract.
- `requestEnhancedAccess()` must be called from a user gesture (it fires
  Shizuku's grant UI best-effort); never call it silently at startup and
  never attempt to install or start the Shizuku service itself.
- Grant results arrive via Shizuku's permission listener already handled
  inside `ShizukuHotspotBridge`; no Activity result plumbing is needed.

## 7. Repaired service design (worker, for parent review)

- Compiles against public SDK only: no `android.os.ServiceManager`
  import. `ServiceManager.getService` is reached reflectively via
  `Class.forName("android.os.ServiceManager")`.
- Soft AP membership uses `WifiManager#registerSoftApCallback(Executor,
  SoftApCallback)` reflectively with a `java.lang.reflect.Proxy`
  `WifiManager$SoftApCallback` (verified android-11.0.0_r1 interface).
  No manual `ISoftApCallback` Parcel dispatch is used. The same proxy
  instance is unregistered on shutdown.
- `startTethering` is selected by `TetheringRequest` parameter type, not
  by overload position. Stop uses Wi-Fi-only
  `stopTethering(TETHERING_WIFI)` only; there is no `stopAllTethering`
  fallback, so unrelated USB/Bluetooth tethers are never touched.
- Authorization: `Process.myUid() == 2000` is checked before any
  hidden-API init (API 30 only; other levels report unsupported). Every
  entry validates the caller (app UID or self) and privileged outbound
  calls run with the incoming identity cleared, so they execute as shell.
  `getBridgeVersion` and the server-destroy transaction are caller-gated.
- Shell package context (`com.android.shell`, best-effort) is used for
  hidden service lookups to avoid app-ops uid/package mismatch.
- Truthfulness: Soft AP MACs are the only authoritative roster.
  `TetheredClient` data enriches Wi-Fi-type members only via
  `getAddresses()` / `AddressInfo.getAddress()` / `LinkAddress` plus
  hostname. `clients` is null until `onConnectedClientsChanged` arrives
  (state ON alone never yields zero); stable empty appears only after
  the membership callback or confirmed OFF.
- App side: all blocking Binder IPC runs on `Dispatchers.IO` with a
  bounded wait, `CancellationException` is never swallowed, and
  `setEnabled` serializes with a `Mutex` plus bounded confirmation
  polling (20 s), not a single short delay. Uncertain RPC answers never
  claim nothing changed. `canToggle` is request eligibility
  (READY + proven service + real ON/OFF), confirmation is the
  `OperationResult`.

## 8. Verification status (what is and is not demonstrated)

- Tests WRITTEN, not executed (JVM): Bundle/Map codec round-trips,
  MAC-authoritative merge (static-IP members kept, ghosts dropped),
  strict ARP/neighbor parsers with caps, profile validation, tile-hint
  mapping, repository policy (NeedsSystemUi handoffs, honest block/apply
  refusals, eligibility vs confirmation, membership-unknown vs zero,
  uncertain-transport message, revocation fallback), plus pure
  shell/caller/SDK-guard decisions. No Gradle run was performed by the
  worker and no phone verification is claimed.
- NOT yet demonstrated (needs parent's scheduled physical-device window,
  hotspot currently OFF, Shizuku not installed): UserService bind as UID
  2000 on the Infinix, Soft AP Proxy probe, TetheredClient Wi-Fi-type
  enrichment shape on this OEM stack, start/stop round-trip with observed
  ON/OFF, entitlement behavior, 5 GHz support. `canToggle` stays false
  until a real ON/OFF is observed; block/configure stay refused until
  proven live.
