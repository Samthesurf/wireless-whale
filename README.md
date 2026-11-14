# Wireless Whale

Local-first Wi-Fi companion for Android. Observe the current connection,
scan nearby networks, share and scan Wi-Fi QR codes, run bounded connection
diagnostics, and manage the hotspot through real system controls.

Package `com.samuel.wirelesswhale`. Kotlin plus Jetpack Compose.
Primary target device: Infinix X6816, Android 11 (API 30).
Build baseline: JDK 21, `./gradlew`, compile and target SDK 36, minSdk 29.

## Non-root boundary

Strictly non-root. No su, no root-started helpers, no bootloader unlocking,
no flashing, no SELinux changes. Optional enhanced hotspot control runs as
shell UID 2000 through Shizuku, and only after explicit user consent (see
`docs/enhanced-access.md`). Nothing here installs or starts Shizuku, and no
phone state is changed except through user triggered actions in the app or
the Android system screens it opens.

## What the app does

- Home, Wi-Fi, Hotspot, and Tools tabs over live public API observations.
- Wi-Fi scanning with contextual location permission and system throttling
  respected. Radio numbers (RSSI, link rate) are labeled as radio
  measurements, never as internet speed.
- QR scanning fully offline (CameraX plus ZXing, no Play services model).
  A scan only previews the network and its security, then asks for a user
  action before launching the Android network approval screen. A network is
  reported as saved only when the per-network result list confirms it
  (RESULT_OK alone proves nothing), and never as connected until the
  repository observes the connection.
- Connection diagnostics run only from an explicit Run action and contact
  only `https://connectivitycheck.gstatic.com/generate_204`. DNS, gateway,
  and validated internet are reported as separate steps.
- Local device discovery is scoped mDNS only, with incompleteness labeled.
  It is not a hotspot client inventory.
- Local history is metadata only, redacted, with delete and export.
- Quick Settings tiles for hotspot and QR scan. The hotspot tile opens real
  system controls and never fakes an On state.

## Known limitations

- Per-client byte counters, strict per-client quotas, and speed caps are
  unsupported without a non-root mechanism that has actually been proved.
  Client usage reads unknown until then, never zero presented as measured.
- Hotspot client lists distinguish unknown (`null`) from authoritative zero
  (empty list). Silent or non-advertising devices never appear in discovery.
- Carrier entitlement behavior on this build is unknown until a first live
  start with user consent.
- On the target Infinix, Shizuku 13.6 authorization succeeds but its
  UserService bootstrap crashes in the OEM framework before this app's
  helper runs. Enhanced controls are unavailable on that configuration;
  the app reports the helper failure separately from permission denial.
  See the upstream report: https://github.com/RikkaApps/Shizuku/issues/1198.
  An explicitly approved compatibility test with official 13.5.4, verified
  against the same signing certificate, successfully starts the UID-2000
  helper. This is distinct from proving start/stop or client internet access.
- Cold launch, the reported Privacy/Connection check layouts, and focused
  Compose layout tests have been exercised on the Infinix. This does not
  establish Wi-Fi joining, client internet access, hotspot controls,
  Quick Settings lifecycle, or camera behavior. Those require separate
  physical-device checks and an agreed network test window.

## Permissions

Location (scan, fine plus coarse together on every SDK), camera (scanner
screen only), Wi-Fi state and change, multicast lock while discovery runs.
Nearby Wi-Fi devices is declared but never requested for scans; it stays
reserved for a feature that actually needs peer APIs. No background
location, usage access, accessibility, storage, overlay, or admin
permissions.

## Privacy

No account, no backend, no cloud. See `docs/privacy.md`.

## Build

Do not run Gradle while sibling workers are writing partial sources. The
parent schedules integrated checks.

```
# Full local gates, serialized to bound memory use
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./scripts/verify.sh

# A build only
./gradlew :app:assembleDebug

# Rebuild launcher assets from the preserved generated artwork (optional)
uv run --with pillow python scripts/generate_launcher_assets.py
```

## Docs

- `docs/implementation-contract.md`: shared worker contract.
- `docs/capabilities.md`: non-root hotspot backend evidence.
- `docs/device-audit.md`: read-only device evidence.
- `docs/privacy.md`: data handling.
- `docs/enhanced-access.md`: manual Shizuku setup after user consent.
