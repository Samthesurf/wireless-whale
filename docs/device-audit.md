# Device audit — Wireless Whale target phone

Date (UTC): 2026-11-14
Method: read-only `adb shell` inspection only. No network toggles, no scans
started, no connect/disconnect, no settings writes, no hotspot start/stop,
Shizuku was neither installed nor started. Device identifiers (serial, MACs,
SSIDs, credentials) are deliberately omitted; raw dumps were inspected and only
summarized here, never committed.

## 1. Device identity

- Model: Infinix X6816 (INFINIX MOBILITY LIMITED), SoC mt6765 (MediaTek)
- Android 11, API 30, security patch 2023-05-05, build X6816-XQ683AMNP-R-OP-230517V1242
- Display 720x1640, density 320
- Host link: USB adb, `adb` transport state, developer options on, adb enabled
- Shizuku: NOT installed (`pm list packages` shows no `moe.shizuku` / Shizuku package)

## 2. Shell identity and granted permissions

- `adb shell id` → `uid=2000(shell)`, SELinux `u:r:shell:s0` (stock, non-root)
- `dumpsys package com.android.shell` confirms `granted=true` for all of:
  - `TETHER_PRIVILEGED`
  - `NETWORK_SETTINGS`
  - `READ_WIFI_CREDENTIAL`
  - `ACCESS_WIFI_STATE`
  - `DUMP`
- Shell therefore holds the Android-permission side needed for tethering control.
  Linux-side limits still apply (see §6).

## 3. Command surface (verbatim behavior)

- `cmd wifi help` exposes `start-softap <ssid> (open|wpa2) <passphrase> [-b 2|5|6|any]`
  and `stop-softap`. Its own help text states:
  "Note that the shell command doesn't activate internet tethering."
- `cmd tethering help` → `No shell command implementation.` There is no
  tethering shell-command path on this device.
- `cmd wifi status` → `Wifi is disabled`, `Wifi scanning is always available`.
  Nothing was enabled or scanned by us.

## 4. Service surface

- `service list` confirms `tethering: [android.net.ITetheringConnector]`,
  `wifi: [android.net.IWifiManager]`, `wifiscanner`, `connectivity`,
  `netpolicy`, `netstats` present.
- Conclusion: tethering is a live system service reachable via hidden
  `TetheringManager`/`ITetheringConnector` APIs from a shell-identity process,
  not via any `cmd tethering` shell command.

## 5. Hotspot / tethering state (hotspot OFF, no clients)

Redacted summary of `dumpsys tethering` / `dumpsys wifi` (sensitive fields
filtered before reading, none recorded):

- Tetherable regexes include `softap\d` / `ap\d`; DHCP ranges are the standard
  192.168.42–49/x24 pools; default IPv4 DNS 8.8.8.8 / 8.8.4.4.
- Current upstream interface: null. Offload disabled. Polling not started.
  Forwarding stats empty. No BPF stats map. No downstream IPv6 rules.
- `provisioningApp`: empty; `isCarrierSupportTethering: true`;
  `enableLegacyDhcpServer: false`. `settings get global` for
  `tether_supported`, `tether_dun_required`, `soft_ap_timeout_enabled` all
  return `null` (unknown, not false) on this build.
- SoftAp hostapd/wificond setup-failure counters all 0,
  `mSoftApTetheredEvents` / `mSoftApLocalOnlyEvents` empty — no Soft AP session
  recorded.

## 6. Negative results (these constrain the design)

- `/proc/net/arp` header only, `ip neigh show` empty → no neighbors observed
  (hotspot off; also: neighbor tables are not authoritative membership anyway).
- `ls /data/misc/dhcp/` → Permission denied for shell; `/data/misc/dnsmasq/`
  absent. Shell uid 2000 cannot read DHCP lease state directly.
- `ip -o link show` → `request send failed: Permission denied`.
- `iptables -S` → `can't initialize iptables table 'filter': Permission denied
  (you must be root)`. Shell cannot read packet-filter counters; combined with
  "No BPF stats map" above, per-client byte accounting via netfilter/BPF is
  unavailable to shell on this device.
- These negatives were each verified with a read-only command and are facts
  about shell capability, not guesses.

## 7. What was NOT done

No `set-wifi-enabled`, no `start-softap`/`stop-softap`, no `start-scan`, no
`svc`/settings writes, no app installs, no Shizuku install/start, no reboot,
no radio or hotspot state change of any kind.

## 8. Repro commands (all read-only)

`adb devices -l`, `adb shell id`, `adb shell getprop`
(`ro.build.version.{release,sdk,security_patch}`, `ro.product.{model,manufacturer}`,
`ro.hardware`, `ro.build.display.id`), `adb shell wm size|density`,
`adb shell dumpsys package com.android.shell`, `adb shell cmd wifi help`,
`adb shell cmd wifi status`, `adb shell cmd tethering help`,
`adb shell service list`, `adb shell pm list packages`,
`adb shell dumpsys tethering`, `adb shell dumpsys wifi` (both filtered, never
stored raw), `adb shell ip neigh show`, `adb shell cat /proc/net/arp`,
`adb shell ls -l /data/misc/dhcp/`, `adb shell ip -o link show`,
`adb shell iptables -S`, `adb shell settings get global
{tether_supported,tether_dun_required,soft_ap_timeout_enabled,adb_enabled,development_settings_enabled}`.
