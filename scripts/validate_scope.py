#!/usr/bin/env python3
"""Check Wireless Whale's non-root manifest and production-source boundary.

This is a focused static gate, not a substitute for compilation, tests, or
physical-device verification. It never changes the phone or project files.
"""
from __future__ import annotations

import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
FORBIDDEN_PERMISSIONS = {
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.PACKAGE_USAGE_STATS",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.permission.WRITE_SECURE_SETTINGS",
    "android.permission.TETHER_PRIVILEGED",
    "android.permission.NETWORK_SETTINGS",
}
ROOT_EXECUTION = (
    re.compile(r'Runtime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\s*\(\s*"su(?:\s|"|\\b)'),
    re.compile(r'ProcessBuilder\s*\(\s*"su(?:\s|")'),
)


def inspect_manifest(text: str) -> list[str]:
    issues: list[str] = []
    try:
        manifest = ET.fromstring(text)
    except ET.ParseError as exc:
        return [f"Manifest XML is invalid: {exc}"]
    permissions = {
        node.get(ANDROID + "name")
        for node in manifest.findall("uses-permission")
    }
    for permission in sorted(permissions & FORBIDDEN_PERMISSIONS):
        issues.append(f"Out-of-scope permission: {permission}")
    fine = next(
        (node for node in manifest.findall("uses-permission")
         if node.get(ANDROID + "name") == "android.permission.ACCESS_FINE_LOCATION"),
        None,
    )
    if fine is None:
        issues.append("Fine location must be declared for the scanning workflow")
    elif fine.get(ANDROID + "maxSdkVersion") is not None:
        issues.append("Fine location is incorrectly capped despite modern scan API requirements")
    application = manifest.find("application")
    if application is None:
        return issues + ["Application declaration is missing"]
    if application.get(ANDROID + "allowBackup") != "false":
        issues.append("Backup must remain disabled until safe data exclusions are implemented")
    if application.get(ANDROID + "usesCleartextTraffic") != "false":
        issues.append("Unexpected global cleartext networking permission")
    services = {
        node.get(ANDROID + "name"): node
        for node in application.findall("service")
    }
    for name in (".tiles.HotspotTileService", ".tiles.ScanWifiTileService"):
        node = services.get(name)
        if node is None:
            issues.append(f"Missing Quick Settings service: {name}")
            continue
        if node.get(ANDROID + "permission") != "android.permission.BIND_QUICK_SETTINGS_TILE":
            issues.append(f"Wrong Quick Settings binding permission: {name}")
        if node.get(ANDROID + "exported") != "true":
            issues.append(f"Quick Settings service must be exported to SystemUI: {name}")
    provider = next(
        (node for node in application.findall("provider")
         if node.get(ANDROID + "name") == "rikka.shizuku.ShizukuProvider"),
        None,
    )
    if provider is None:
        issues.append("Shizuku provider declaration is missing")
    elif provider.get(ANDROID + "permission") != "android.permission.INTERACT_ACROSS_USERS_FULL":
        issues.append("Shizuku provider must be protected from ordinary app callers")
    return issues


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    source_root = root / "app/src/main"
    issues = inspect_manifest((source_root / "AndroidManifest.xml").read_text())
    checked = 0
    for path in source_root.rglob("*"):
        if path.suffix not in {".kt", ".java", ".aidl"}:
            continue
        checked += 1
        text = path.read_text()
        relative = path.relative_to(root)
        if any(pattern.search(text) for pattern in ROOT_EXECUTION):
            issues.append(f"Root process execution is prohibited: {relative}")
        if re.search(r"hardQuotaAvailable\s*=\s*true", text):
            issues.append(f"Hard quota advertised without an accepted non-root backend: {relative}")
    print(json.dumps({
        "gate": "focused non-root scope validation",
        "production_source_files_checked": checked,
        "issues": issues,
        "passed": not issues,
        "does_not_verify": ["compilation", "behavior", "device integration", "traffic enforcement"],
    }, indent=2))
    return int(bool(issues))


if __name__ == "__main__":
    raise SystemExit(main())
