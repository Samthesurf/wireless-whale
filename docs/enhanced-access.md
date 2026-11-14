# Enhanced access (Shizuku, non-root)

On Android 11, ordinary app permissions cannot provide the desired tethered
hotspot controls. The optional backend is designed to run a small typed
service as shell UID 2000 through Shizuku. Authorization alone does not prove
that helper startup or individual controls work.

## What it is

- Shizuku runs the app's service code with the shell identity (UID 2000 on
  this non-root phone), which already holds the tethering privileges.
- No su, no root daemon, no system modification. The app process itself
  uses zero hidden APIs.
- The service exposes a small typed surface only (start or stop tethering,
  subscribe to client callbacks, read a snapshot). It is not a generic
  command runner.

## Manual setup, after user consent only

The app never installs or starts Shizuku and never changes hotspot, Wi-Fi,
or mobile data state on its own. Setup is manual, and every step needs an
explicit user action. Nothing here grants Shizuku authorization for any
other app, and nothing changes authorizations already on the phone.

1. Install Shizuku from its official source
   (`https://shizuku.rikka.app/download/`). The build verified with this
   project is the official v13.6.0 release (version code 1086, source revision
   2650830c); if you use a newer release, follow that release's own steps.
2. Start Shizuku using its own instructions. On Android 11 and above,
   on-device pairing is possible only if the OEM exposes wireless debugging.
   The server needs restarting after reboot. For USB startup, use the exact command
   the installed Shizuku app shows on its own start screen (current Shizuku
   builds resolve their starter from the installed library directory, not
   from a fixed on-device path). There is no `/sdcard/.../start.sh` in
   current releases; do not run saved scripts from old guides.
   Verified on the project phone: server running as shell UID 2000
   (`uid 2000 shell`), with Wi-Fi state unchanged by the startup.
3. Open Wireless Whale, go to Hotspot, and approve the Shizuku
   authorization prompt. Denying leaves the app on standard mode with
   system controls.

## States

The hotspot screen reports not installed, not running, permission required,
connecting, helper unavailable, ready, revoked, or unsupported. Ready requires
a UID-2000/package/protocol handshake. Connecting has a bounded timeout; a
failed helper offers Retry without pretending authorization was denied.

## What it does not give

- No proved per-client byte counters, strict per-client quotas, or speed caps.
  Association allow/block APIs are not quotas; this build keeps profile
  changes and blocking disabled until the vendor route is proved.
- Soft AP association callbacks define membership. Best-effort tethering
  IP/hostname data may only enrich those members, never invent membership.
  Until the helper supplies authoritative observations, the list is Unknown.
- Carrier entitlement on this build is unknown until a first live start
  with explicit user consent and a client device present.

## Current status

The server and authorization were verified on the Infinix X6816. However,
Shizuku 13.6.0 crashes in the OEM `LoadedApk.makeApplication` bootstrap before
loading the helper class. The installed app correctly reports Helper
unavailable rather than requesting authorization again. Start/stop and
client callbacks are not working or verified on that configuration.

## Approved older-release compatibility test

Official v13.5.4 uses a context-only startup path and does not call the
failing `makeApplication` method. After explicit user approval, a
certificate-matched, data-preserving downgrade and server restart started
the app helper as UID 2000; the app reported Enhanced: ready and read the
hotspot's actual OFF state. No root or unofficial APK was used.

The first live Start test then exposed a separate app-side API mismatch:
this phone uses `TetheringManager$TetheringRequest` and its nested
`$Builder(int)`, not the newer top-level request class. A read-only shell
reflection probe confirmed the real signatures. Correcting that adapter
and verifying start/stop is separate from successful helper startup.

Do not downgrade automatically or promise app authorizations survive.
Retain rollback options and verify the restarted server, because replacing
the manager alone leaves the old process running. Startup instructions are
release-specific; older releases can generate an external `start.sh`.
Inspect it before execution and never run recovery code that deletes the
entire `/data/local/tmp` directory.

The latest official release checked is still v13.6.0. The matching upstream
report is https://github.com/RikkaApps/Shizuku/issues/1198. A custom app
Application cannot run before this bootstrap failure. The pinned client's
old process-launch API is private and deprecated, and is not used as an
unsupported fallback. Unofficial manager forks require a separate user
decision; none was installed. Standard system-controls handoff remains
available.
