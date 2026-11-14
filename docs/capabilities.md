# Capabilities  -  non-root hotspot backend (API 30 target)

Scope: what Wireless Whale can and cannot do on the audited Infinix X6816
(Android 11 / API 30) without root. Kotlin source is now present, but source
implementation, a passing build, and physical device proof are separate
milestones. See `device-audit.md` for the read-only baseline evidence. Project builds with `compileSdk = 36`, `minSdk = 29`,
`targetSdk = 36`  -  a newer compile SDK does not backport platform services, so
everything below targets API 30 behavior on the phone.

## Observed target-device blocker

The official Shizuku 13.6.0 server runs as UID 2000 and app authorization
succeeds. Its UserService helper exits before this app's constructor:
`LoadedApk.makeApplication` throws a null `String.equals` exception. This
matches the upstream MediaTek/OEM bootstrap issue:
https://github.com/RikkaApps/Shizuku/issues/1198.
The pinned runner calls `makeApplication` before loading the service class:
https://github.com/RikkaApps/Shizuku-API/blob/510fc988c02c3475d8c25db170f96792f105bdf8/server-shared/src/main/java/rikka/shizuku/server/UserService.java.

The app's granted, connecting, unavailable, and ready states are separate.
A missing callback times out to Helper unavailable, not another permission
request. On this phone, enhanced start/stop and client callbacks are NOT
verified and currently unavailable. API strategies below describe the
intended backend, not working-device claims. No unofficial manager, private
process-launch fallback, root, or system security changes were applied.

### Compatibility observation

With explicit consent, official v13.5.4 was installed without clearing data;
its certificate matched the installed official v13.6.0. Restarting the
server under shell UID 2000 allowed the real app helper to bind and return
an OFF snapshot. No unofficial manager or root path was used. Helper
readiness alone is not proof that individual controls work.

The first Start attempt stayed OFF because the adapter used an absent
newer request class. The actual phone's read-only reflection output is:

```
startTethering(TetheringManager$TetheringRequest, Executor, StartTetheringCallback)
TetheringManager$TetheringRequest$Builder(int)
TetheringManager$TetheringRequest Builder.build()
stopTethering(int)
```

The int start overload also exists, and must not be invoked with a request
object. The production resolver must derive matching request, builder,
and callback types from the chosen method rather than mixing SDK shapes.

## 1. The API-30 rule

- Public SDK `TetheringManager.startTethering` is documented as added in API 36
  and must NOT be used for this device.
- On the `android11-release` AOSP branch, `TetheringManager` is
  `@hide @SystemApi`: `startTethering(TetheringRequest, Executor,
  StartTetheringCallback)`, `startTethering(int, …)`, `stopTethering(int)`,
  `stopAllTethering()`, `registerTetheringEventCallback(...)`, and
  `TetheredClient` (MAC + `AddressInfo` IP/hostname + tethering type) all exist
  but are hidden. They require `TETHER_PRIVILEGED` (or `WRITE_SETTINGS`) and are
  callable only from a shell-identity (uid 2000) process  -  i.e. a Shizuku
  UserService  -  never from the normal app process.
- AOSP Soft-AP guidance (source.android.com wifi-softap) defines the supported
  pattern: `WifiManager#setSoftApConfiguration` + `TetheringManager#startTethering`
  for a tethered hotspot; membership via `SoftApCallback#onConnectedClientsChanged`
  (MACs) plus `TetheringEventCallback#onClientsChanged` (IP/hostname).

## 2. Proposed backend: Shizuku shell-mode UserService

- No root, no unlock, no SELinux changes (user constraint). Shizuku runs the
  app's `UserService` code as shell uid 2000, which already holds
  `TETHER_PRIVILEGED`, `NETWORK_SETTINGS`, `READ_WIFI_CREDENTIAL`,
  `ACCESS_WIFI_STATE`, `DUMP` on this phone (verified).
- Per the official Shizuku-API guide: a UserService has no non-SDK restrictions
  in its own process, but is NOT a valid app process  -  `registerReceiver`,
  `getContentResolver`, and content-provider APIs do not work there. All hidden
  API use stays isolated in that process; the app process uses zero hidden APIs
  and no global bypass.
- The UserService exposes a small typed AIDL surface only (e.g.
  `startTetheredHotspot(config)`, `stopTethering()`, `subscribeClients(cb)`,
  `getSnapshot()`), never an arbitrary command runner. Lifecycle: `bindUserService`
  with explicit `tag` + `versionCode`, `destroy` transaction `16777115`
  (use `16777114` in AIDL) ending in `System.exit()`.
- `cmd wifi start-softap` is NOT the backend: device help text confirms it does
  not activate internet tethering. `cmd tethering` has no implementation at all.

## 3. Exact API strategy (all calls execute inside the UserService)

| Function | API (API 30, hidden) | Permission held by shell |
|---|---|---|
| Configure SSID/security/band | `WifiManager#setSoftApConfiguration(SoftApConfiguration)` (+ `getSoftApConfiguration` to read back) | `TETHER_PRIVILEGED` / `NETWORK_SETTINGS` |
| Start internet tether | `TetheringManager#startTethering(new TetheringRequest.Builder(TETHERING_WIFI).build(), executor, cb)` | `TETHER_PRIVILEGED` |
| Stop | `TetheringManager#stopTethering(TETHERING_WIFI)` only; never stop all tethering | `TETHER_PRIVILEGED` |
| Authoritative membership (MACs) | `WifiManager#registerSoftApCallback` → `SoftApCallback#onConnectedClientsChanged` | `NETWORK_SETTINGS`, held by shell uid 2000 (verified in `device-audit.md`); NOT a location permission. All hidden-API use stays inside the UserService; the app process requests nothing for this. |
| Membership (IP/hostname) | `TetheringManager#registerTetheringEventCallback` → `onClientsChanged(Collection<TetheredClient>)` | `ACCESS_NETWORK_STATE` |
| Upstream/internet state | `TetheringEventCallback#onUpstreamChanged` + `onTetheredInterfacesChanged` | `ACCESS_NETWORK_STATE` |

- Membership source of truth is the Soft AP association set, keyed by MAC.
  `TetheringEventCallback#onClientsChanged` IP/hostname data enriches
  already-associated MACs only: it may attach addresses to a member, but it
  must never add or remove members. In particular the member set is NOT
  intersected with IP entries  -  static-IP and sleeping clients stay listed,
  and neighbor/ARP ghosts never invent members. Neighbor/ARP/DHCP discovery
  alone is NOT current association and must never be presented as such.
- A control request is only "verified" when hotspot state AND real client
  internet access are observed afterward; a success return code or Soft AP state
  alone is insufficient.
- `TetheredClient.onClientsChanged` is documented best-effort: bridged or
  static-IP clients may be invisible or linger after disconnect. Never use it
  for security decisions; surface staleness explicitly.

## 4. What is NOT available  -  render unknown, never zero

- Per-client byte counters: UNKNOWN until a live tethering session is
  observed, not proven absent. The audit evidence (iptables denied for
  shell, no BPF stats map, unreadable DHCP lease dir) was all collected
  with the hotspot OFF, offload disabled, and polling not started  -  at
  that state there is simply nothing to account. Absence of a map while
  idle does not prove counters are unavailable while tethering. What IS
  established: shell cannot read packet-filter counters, `TrafficStats`
  per-UID and aggregate interface totals are not per-client hotspot
  accounting, and user-space poll-and-block is not a strict byte quota.
- Therefore: any per-client usage/quota field the app shows must distinguish
  unknown/stale from zero, and a strict per-client byte-quota enforcement
  feature is NOT implementable on this path. Association control (allow/block
  via `SoftApConfiguration` blocked/allowed lists, disconnect) is the available
  enforcement primitive  -  but the vendor mechanism is itself unverified on
  this device (see §7), so block/configure stay locked behind live proof.
- Carrier entitlement on this Infinix build is unknown until the first real
  `startTethering`: `provisioningApp` is empty and `isCarrierSupportTethering`
  is true, but entitlement-check behavior is only observable live.

## 5. Required deps and stubs (parent implements)

- Maven Central `dev.rikka.shizuku:api` + `dev.rikka.shizuku:provider` are pinned
  to 13.1.5 in `gradle/libs.versions.toml`. The official manager installed on
  the phone is Shizuku v13.6.0.r1086.2650830c. Shell UID 2000 server startup
  is verified separately from app authorization and feature behavior.
- `ShizukuProvider` manifest entry with `${applicationId}.shizuku` authority and
  `INTERACT_ACROSS_USERS_FULL` protection, per the Shizuku guide.
- Typed AIDL surface only (`IHotspotBridge` / `IHotspotEventCallback`,
  Bundle payloads pinned by `HotspotBridgeContract`). No hidden-API stubs
  are compiled anywhere: the UserService reaches hidden platform classes
  purely by reflection, and `java.lang.reflect.Proxy` implements the two
  `TetheringManager` callback interfaces (`StartTetheringCallback`,
  `TetheringEventCallback`), which are plain interfaces with default methods
  on android11-release. The verified API-30 `WifiManager.SoftApCallback` is
  also an interface. A Proxy is registered through WifiManager's reflected
  registerSoftApCallback adapter. No manual private callback Parcel decoding
  remains. Runtime shape failures produce explicit unavailability, never a
  fabricated roster. Tethering enrichment alone cannot establish membership.
  No app-wide bypass, no reflection into hidden APIs from the app process.
- Hidden isWifiApEnabled and WIFI_AP_STATE_* are not public-SDK APIs. Standard
  mode must retain unknown state without an actual permitted observation.
  A Quick Settings handoff must not label unavailable state as On or Off.
- Profile editing and client blocking remain intentionally refused until the
  vendor route is proven. Per-client usage, quota, and speed limits have no
  proven implementation. Do not present disabled controls as working features.
- Runtime contract: Shizuku installed + permission granted + binder alive
  (`Shizuku.checkSelfPermission`, binder listeners); uid check `2000` expected
  on this non-root phone; Shizuku needs restart after every reboot (pairing/
  authorization persists, running state does not)  -  wireless debugging or USB
  adb with explicit user consent, never assumed available.

## 6. Safe verification plan (read current state first)

1. Re-run the §8 read-only commands in `device-audit.md`; record the current
   state without changing it. The audit baseline was Wi-Fi disabled and
   hotspot off; do not assume that historical state still holds.
2. Build and install the actual APK, obtain the user's app authorization, and
   verify the UID-2000/package/protocol handshake. With radios unchanged,
   verify callback registration and initial observations. Unknown membership
   stays null until an authoritative callback or confirmed OFF state. Do not
   assume a support flag or empty client set before the bridge proves it.
3. Negative tests: UserService reports clean errors when Shizuku binder is dead
   or permission denied (no crash, no state change).
4. Live start/stop test only later, with explicit user consent and a client
   device present: `startTethering` → Soft AP up → client associates (appears in
   both callbacks) → client loads a page (real internet proof) → `stopTethering`
   → callbacks confirm teardown. Then re-audit.

## 7. Open unknowns (do not claim; verify live in step 4 above)

- First-`startTethering` entitlement result and error codes on this carrier build.
- 5 GHz Soft AP support on this hardware (2.4 GHz fallback assumed until observed).
- Actual client-visible IP/hostname population via `TetheredClient` on this OEM stack.
- Any OEM (Transsion) background/auto-start restrictions affecting the app UI process.
