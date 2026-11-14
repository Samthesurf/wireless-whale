# Wireless Whale engineering rules

Project: /home/samuelsurf/Documents/Android_stuff/wireless whale
Package: com.samuel.wirelesswhale
Primary device: Infinix X6816, Android 11/API 30, 360x820 logical dp before insets.

## Safety and truthfulness
- STRICTLY NON-ROOT. No su, Sui, root-started Shizuku, bootloader unlocking, flashing, SELinux changes, or root helpers.
- Optional Shizuku must run as shell UID 2000. Authorization/setup is explicit.
- Do not change hotspot, Wi-Fi, mobile data, developer options, or existing connections without a separately agreed test window.
- Do not install/start Shizuku or grant access without the parent handling the user-facing decision.
- Never log passwords, complete QR credential payloads, saved network dumps, or unnecessary device identifiers.
- Production screens use real observations or explicit unknown/unavailable state. No demo clients or invented usage/counters.
- Per-client hard quotas and speed caps are unsupported until a non-root mechanism is actually proved. Do not simulate enforcement.

## Product and UI
- Kotlin + Jetpack Compose only. White theme, bundled Geist, dark graphite text, restrained teal accent, generous rounded corners.
- Four primary tabs: Home, Wi-Fi, Hotspot, Tools. Floating capsule navigation with semicircular ends.
- User rejected basic settings-form UI. Use information hierarchy, useful rows, designed failure/empty states, and a consistent component system.
- Verify 320dp/360dp widths, long identifiers, font scale 2.0, keyboard and system insets. No clipped primary controls.
- No em/en dashes, marketing fluff, forced cuteness, or emoji UI icons.

## Coordination
- Parent owns integration, shared build files, manifest, MainActivity, and final verification unless explicitly assigned otherwise.
- Each subagent edits only its assigned paths. Do not overwrite sibling work, run git checkout/reset, commit, or push.
- Shared APIs are defined in docs/implementation-contract.md. Do not silently change signatures.
- Avoid builds while siblings are writing partial sources; parent schedules integrated checks. Report required dependencies explicitly.
- Do not run Gradle with the system's default toolchain assumptions. JDK 21 and ./gradlew are the baseline. compile/target SDK 36, minSdk 29.
- AGP 9 uses built-in Kotlin; do not layer the legacy Kotlin Android plugin onto it.
- Unit tests are for pure logic; actual Wi-Fi/hotspot/tile/camera behavior requires physical-device evidence.
- Finish reports distinguish implemented, verified, blocked, and untested. Return exact file paths and any commands actually run.

## Scope reference
Read the relevant sections of .hermes/plans/2026-11-14_094250-wireless-whale.md. It is a large plan, not a claim that all its capabilities are available.
