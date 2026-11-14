# Shared implementation contract

This is the integration contract for parallel Muse/Meta workers. Root is prohibited. Use exact packages/signatures below; parent handles changes and assembly. Proposed source root is `app/src/main/kotlin/com/samuel/wirelesswhale/`.

## Ownership

- Foundation worker: `core/`, `platform/publicapi/AndroidWifiRepository.kt`, Gradle dependency/catalog configuration, foundation tests. Define shared models/interfaces before dependent feature work.
- UI worker: `ui/`, `feature/home/`, `feature/wifi/`, `feature/hotspot/`, `feature/tools/`, `feature/settings/`, `navigation/`. Do not own MainActivity or repositories.
- QR worker: `qr/`, `feature/scanner/`, QR tests. No manifest/build file edits; request parent integration.
- Hotspot worker: `platform/shizuku/`, `platform/hotspot/`, `tiles/`, their pure tests. No shared model/build/manifest edits.
- Diagnostics worker: `diagnostics/`, `data/`, `feature/history/`, tests. No other feature screen or shared model edits.
- Parent: MainActivity, app assembly/ViewModel, manifest, resources outside delegated UI/font assets, final integration and verification.

All workers must report files and required integration rather than modifying a sibling's paths. A missing dependency is not permission to add a mock production fallback.

## Shared models in core/model/Models.kt

Implement the following exact public types. Additional optional properties with safe defaults are allowed only if they do not break callers.

- `enum class InternetState { UNKNOWN, DISCONNECTED, LOCAL_ONLY, CAPTIVE_PORTAL, VALIDATED }`
- `enum class WifiSecurity { OPEN, WPA2_PERSONAL, WPA3_PERSONAL, WPA2_WPA3_PERSONAL, WEP, ENTERPRISE, OWE, UNKNOWN }`
- `data class WifiCredentials(val ssid: String, val password: String = "", val security: WifiSecurity = WifiSecurity.WPA2_PERSONAL, val hidden: Boolean = false)` with a redacted override of toString, no secret logging.
- `data class WifiNetwork(val id: String, val ssid: String, val bssid: String?, val rssiDbm: Int, val frequencyMhz: Int, val security: WifiSecurity, val capabilities: String = "", val lastSeenAtMillis: Long? = null)`.
- `data class WifiSnapshot(val enabled: Boolean = false, val connected: Boolean = false, val ssid: String? = null, val bssid: String? = null, val rssiDbm: Int? = null, val frequencyMhz: Int? = null, val linkSpeedMbps: Int? = null, val txLinkSpeedMbps: Int? = null, val rxLinkSpeedMbps: Int? = null, val ipv4: List<String> = emptyList(), val ipv6: List<String> = emptyList(), val gateway: String? = null, val dnsServers: List<String> = emptyList(), val internet: InternetState = InternetState.UNKNOWN, val metered: Boolean? = null, val vpnActive: Boolean = false, val permissionRequired: Boolean = false, val locationEnabled: Boolean = true, val lastUpdatedAtMillis: Long? = null, val message: String? = null)`.
- `data class ScanState(val scanning: Boolean = false, val lastScanAtMillis: Long? = null, val message: String? = null, val permissionRequired: Boolean = false, val locationRequired: Boolean = false, val throttled: Boolean = false)`.
- `enum class HotspotStatus { UNKNOWN, OFF, STARTING, ON, STOPPING, ERROR }`
- `enum class EnhancedAccessState { NOT_INSTALLED, NOT_RUNNING, PERMISSION_REQUIRED, CONNECTING, HELPER_UNAVAILABLE, READY, REVOKED, UNSUPPORTED }`.
- `data class HotspotClient(val id: String, val macAddress: String? = null, val name: String? = null, val addresses: List<String> = emptyList(), val connected: Boolean = true, val lastSeenAtMillis: Long? = null, val receivedBytes: Long? = null, val transmittedBytes: Long? = null)`.
- `data class HotspotSnapshot(val status: HotspotStatus = HotspotStatus.UNKNOWN, val ssid: String? = null, val security: String? = null, val clients: List<HotspotClient>? = null, val enhancedAccess: EnhancedAccessState = EnhancedAccessState.NOT_INSTALLED, val canToggle: Boolean = false, val canConfigure: Boolean = false, val canBlockClients: Boolean = false, val clientUsageAvailable: Boolean = false, val hardQuotaAvailable: Boolean = false, val message: String? = null, val source: String = "System", val lastUpdatedAtMillis: Long? = null)`.
- `data class HotspotProfile(val ssid: String, val password: String, val band: Int? = null)` with redacted toString.
- `sealed interface OperationResult { data class Success(val message: String) : OperationResult; data class Failure(val message: String) : OperationResult; data class NeedsSystemUi(val action: String) : OperationResult }`.
- `enum class CheckStatus { WAITING, RUNNING, PASSED, FAILED, WARNING }`.
- `data class DiagnosticStep(val id: String, val title: String, val status: CheckStatus, val detail: String = "", val durationMs: Long? = null)`.
- `data class DiagnosticReport(val steps: List<DiagnosticStep> = emptyList(), val startedAtMillis: Long = 0, val finishedAtMillis: Long? = null, val cancelled: Boolean = false)`.
- `data class DiscoveredDevice(val id: String, val name: String?, val host: String?, val port: Int?, val serviceType: String)`.
- `data class HistoryEntry(val id: String, val title: String, val detail: String, val atMillis: Long, val kind: String)`.

## Foundation repository contract

Package `core.platform`:

```
interface WifiRepository {
  val connection: StateFlow<WifiSnapshot>
  val networks: StateFlow<List<WifiNetwork>>
  val scanState: StateFlow<ScanState>
  fun start()
  fun refreshPermissions()
  fun requestScan()
  fun close()
}
```

Implementation `platform.publicapi.AndroidWifiRepository(context: Context)`; idempotent start/close, own coroutine lifecycle. No persistent background active scanning. Refresh permission state after Settings return. Validate SSID redaction. Scan sample timestamps are actual scan times, not redraw times. RSSI and link rate are not internet throughput. Avoid logging identifiers/credentials. App target36 must still function on API30.

## Hotspot repository contract

Package `core.platform`:

```
interface HotspotRepository {
  val state: StateFlow<HotspotSnapshot>
  fun start()
  fun refresh()
  fun requestEnhancedAccess()
  suspend fun setEnabled(enabled: Boolean): OperationResult
  suspend fun blockClient(id: String, blocked: Boolean): OperationResult
  suspend fun applyProfile(profile: HotspotProfile): OperationResult
  fun close()
}
```

Implementation `platform.hotspot.AndroidHotspotRepository(context: Context)`.
- Standard mode can return NeedsSystemUi rather than pretend to toggle.
- No root-started Shizuku. Require UID2000 and feature-specific validation.
- `clients=null` is unknown, `clients=emptyList()` is authoritative zero.
- No per-client usage/quota claim without evidence.
- Never serialize passwords in status responses. Revealing/sharing a hotspot password requires an intentional foreground action and separate explicit retrieval contract if later supported.

## UI contract

UI is a stateless function over observations plus callbacks. Navigation and UI-local sheet state may be internal. MainActivity/app assembly stays outside UI ownership.

Entry point:

```
@Composable
fun WirelessWhaleApp(
  wifi: WifiSnapshot,
  networks: List<WifiNetwork>,
  scanState: ScanState,
  hotspot: HotspotSnapshot,
  diagnosticReport: DiagnosticReport,
  diagnosticRunning: Boolean,
  discoveredDevices: List<DiscoveredDevice>,
  discoveryRunning: Boolean,
  history: List<HistoryEntry>,
  initialDestination: String = "home",
  onRequestWifiPermission: () -> Unit,
  onOpenWifiSettings: () -> Unit,
  onRefreshNetworks: () -> Unit,
  onConnectNetwork: (WifiCredentials) -> Unit,
  onOpenScanner: () -> Unit,
  onShareCredentials: (WifiCredentials) -> Unit,
  onOpenHotspotSettings: () -> Unit,
  onToggleHotspot: (Boolean) -> Unit,
  onRequestEnhancedAccess: () -> Unit,
  onBlockClient: (String, Boolean) -> Unit,
  onRunDiagnostics: () -> Unit,
  onCancelDiagnostics: () -> Unit,
  onStartDiscovery: () -> Unit,
  onStopDiscovery: () -> Unit,
  onClearHistory: () -> Unit,
  onExportHistory: () -> Unit,
  onOpenQuickSettingsHelp: () -> Unit,
  navigationRequestId: Long = 0L
)
```

`navigationRequestId` is a parent-approved integration extension: increment it for each external navigation command, including repeated requests for the same destination. The UI consumes tokens once and preserves ordinary user navigation across recomposition/rotation. Keep passwords and QR payloads out of saved navigation state.

`WirelessWhaleApp` package `ui`. `WirelessWhaleTheme` package `ui.theme`. Use real state only. Unsupported quota UI is explanatory, not an editable pretend setting. Networking/security form validation can delegate shared domain validators. QR sharing is generated by parent and presented safely; UI provides explicit credential form.

## QR contract

Package `qr`:
- `object WifiQrCodec { fun decode(payload: String): Result<WifiCredentials>; fun encode(credentials: WifiCredentials): String }`.
- `fun createWifiQrBitmap(credentials: WifiCredentials, size: Int = 768): Bitmap`.

Package `feature.scanner`:
```
@Composable
fun ScannerScreen(
  onResult: (WifiCredentials) -> Unit,
  onClose: () -> Unit
)
```

The scanner owns contextual camera permission, CameraX, torch, image import, decode-once handling, and lifecycle disposal. Result does not auto-connect: parent previews and launches system approval. Offline ZXing, no Play services model dependency. Build dependencies expected: CameraX camera-core/camera-camera2/camera-lifecycle/camera-view 1.4.2 and ZXing core3.5.3. Explicitly unsupported enterprise/WEP/ambiguous formats must not silently downgrade security.

## Diagnostics and local data contract

Package `diagnostics`:
- `class ConnectionCheckRunner(context: Context) { suspend fun run(onStep: (DiagnosticStep) -> Unit): DiagnosticReport }`.
- `class LanDiscoveryRunner(context: Context) { val devices: StateFlow<List<DiscoveredDevice>>; val running: StateFlow<Boolean>; fun start(); fun stop() }`.

Use bounded public-API probes, cancellation and selected active-network context; no global process route changes, VPN bypass, or disruptive subnet sweeps. Explicit user Run action is the only start for external HTTPS/DNS checks. Expose destination in UI/help. DNS/gateway/validated-state conclusions are separate. LAN discovery should initially use scoped local mDNS service discovery and label incompleteness, not pretend to enumerate all clients.

Package `data`:
- `class HistoryStore(context: Context) { val entries: StateFlow<List<HistoryEntry>>; fun add(title: String, detail: String, kind: String); fun clear(); fun exportRedacted(): String }`.
- `class ClientNicknameStore(context: Context) { fun get(id: String): String?; fun set(id: String, nickname: String) }`.

Prefer a small bounded local store for first working slice, no secret persistence by default. If Room adds integration complexity before first slice, explain deferred migrations honestly rather than stubbing persistence. History must redact secrets and allow delete/export. Do not falsely label preferences persistence as encrypted credential storage.

## Tiles

HotspotTileService and ScanWifiTileService under `tiles`. Parent manifest integration. Specific scan action string `com.samuel.wirelesswhale.action.SCAN_QR`; hotspot action `com.samuel.wirelesswhale.action.HOTSPOT`. All sensitive/control actions unlock first. Intent collapse on API30, API34+ PendingIntent branch. Hotspot tile opens the app's hotspot station; standard mode offers real system controls there. No false On bit or automatic toggle. Tile should not start a permanent daemon.

## Expected dependencies

Foundation worker may add exact version-pinned CameraX1.4.2, ZXing3.5.3, Shizuku api/provider13.1.5, coroutine Android1.10.2, and the Compose icon dependency only if actually used. Other workers report additional needs; do not silently modify build files.

## Verification

Use test-first pure validators/QR/scan budget/identity logic. Integrated builds are scheduled by the parent, not every worker simultaneously. Tests never silently pass when required physical APIs are unavailable. Exact device actions/installs/permissions are handled by the parent. No commits or pushes by workers.
