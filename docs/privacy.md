# Privacy

Wireless Whale has no account, no backend, no cloud sync, and no analytics.
Everything below stays on the device unless the user explicitly shares it.

## What is collected

- Wi-Fi observations (connection state, scan results) are held in memory
  for display. SSIDs and BSSIDs are observations shown on screen, never
  uploaded anywhere. There is nowhere to upload them to.
- Passwords and full QR credential payloads are transient. They live only
  in memory while a preview or share dialog is open, are never written to
  saved state, saved bundles, logs, or history, and are cleared on dismiss.
  Only non-secret UI flags (selected tab, scanner open) survive rotation;
  credentials never do.
- Local history (session notes, diagnostic and hotspot outcomes) stores
  metadata only: titles, redacted details, timestamps, kinds. Secrets are
  redacted before storage and again on export. History supports delete and
  redacted export, and lives in app-private storage, which is not presented
  as encrypted credential storage.

## Camera and QR

- QR scanning runs fully offline with CameraX and ZXing. Frames and
  payloads are decoded on the device and never logged or uploaded.
- Camera permission is requested contextually on the scanner screen only.
- Imported images use the Storage Access Framework, so no storage
  permission is needed.

## Network

- Diagnostics run only from an explicit user Run action and contact only
  `https://connectivitycheck.gstatic.com/generate_204`. The response body
  is capped and discarded; only the status code is interpreted.
- Local discovery uses scoped mDNS on the current network. No subnet sweep,
  no route changes, no VPN bypass.

## Backup

Auto backup is off (`allowBackup=false`) until safe exclusions are defined,
so history and nicknames do not leave the device through cloud restore.

## Sharing

Two share paths exist, both explicit user actions: sharing Wi-Fi details as
text from the QR share dialog, and sharing the redacted history export.
Image file export of QR codes is not offered.
