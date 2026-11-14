# Walkthrough: Milestone 3 (Jetpack Compose UI, Interactive Policy Controls & M2 Polish)

## 1. Files Created/Modified

### Part 1: Milestone 2 Polish & Architecture Hardening
- `android/app/src/main/java/org/privacyguard/android/shizuku/ShizukuShellService.kt`:
  - Added `am set-standby-bucket`, `cmd package`, `dumpsys package`, and `dumpsys activity exit-info` to `ALLOWED_COMMAND_PREFIXES`.
  - Refined regex argument validation: component arguments strictly validate against `^[a-zA-Z0-9_.$]+/[a-zA-Z0-9_.$]+$`, package names against `^[a-zA-Z0-9_.]+$`, and command keywords (`set-chain3-enabled`, `set-package-networking-enabled`, `oem_deny_3`, `add`, `remove`, `set`, `get`, `restricted`, `rare`, `ignore`, `true`, `false`, `exit-info`, `RUN_ANY_IN_BACKGROUND`) are permitted.
- `android/app/src/main/java/org/privacyguard/android/shield/ShieldProbe.kt`:
  - Extended canary DNS resolution check (`app-measurement.com`) to evaluate `addr.isAnyLocalAddress || addr.isLoopbackAddress` and `0:0:0:0:0:0:0:0` alongside `0.0.0.0`, `127.0.0.1`, and `::1` to comprehensively recognize IPv6 unspecified and sinkholed addresses.
- `android/app/src/main/java/org/privacyguard/android/osint/OsintClassifierWorker.kt`:
  - Confirmed strict single-pass local LLM inference per target package over loopback Ollama (`http://127.0.0.1:11434/api/generate`), eliminating redundant inference passes for decoy hashes.
- `android/app/src/main/java/org/privacyguard/android/shizuku/ShizukuManager.kt`:
  - Added `rollbackPackage(pkg: String): Boolean`: executes component state restoration (`0 -> default-state`, `1 -> enable`, `2 -> disable`, `3 -> disable-user` from `PolicyOp.snapshot`) serialized through `ioSingle` with read-back verification before marking `PolicyOp`s as superseded.
- `docs/architecture.md`:
  - Documented production pinned SPKI Ed25519 public key (`MCowBQYDK2VwAyEAgCYpThvpyHUz6FbXD+eFlCHG818ajDs/UOcGH6HV6ew=`).

### Part 2: Room Flow Extensions
- `android/app/src/main/java/org/privacyguard/android/data/dao/AppScanResultDao.kt`: Added `@Query("SELECT * FROM app_scan_result ORDER BY riskScore DESC") fun observeAllSortedByRisk(): Flow<List<AppScanResult>>`.
- `android/app/src/main/java/org/privacyguard/android/data/dao/PolicyOpDao.kt`:
  - Added `@Query("SELECT * FROM policy_op WHERE pkg = :pkg ORDER BY id ASC") fun observeByPackage(pkg: String): Flow<List<PolicyOp>>`.
  - Added `@Query("SELECT * FROM policy_op ORDER BY id ASC") fun observeAll(): Flow<List<PolicyOp>>`.
  - Added `markSuperseded(id, expectedGen)` for safe rollback transitions.
- `android/app/src/main/java/org/privacyguard/android/data/dao/CapabilityDao.kt`: Added `@Query("SELECT * FROM capability") fun observeAllCapabilities(): Flow<List<Capability>>`.

### Part 3: Compose Dependencies & R8 Hardening
- `android/gradle/libs.versions.toml`:
  - Added `kotlin-compose` plugin (Kotlin 2.0.20 Compose compiler).
  - Added Compose BOM `2024.09.02`, `activity-compose` `1.9.2`, and `lifecycle` `2.8.6`.
  - Zero icon bloat: avoided `androidx-compose-material-icons-extended` entirely.
- `android/build.gradle.kts`: Added `alias(libs.plugins.kotlin.compose) apply false`.
- `android/app/build.gradle.kts`: Applied `alias(libs.plugins.kotlin.compose)`, enabled `buildFeatures { compose = true }`, and added Compose dependencies using platform BOM.
- `android/app/proguard-rules.pro`: Added keep rules for ViewModel constructors and Compose UI classes under R8 full mode.
- `.gitignore`: Added `.kotlin/` and `**/.kotlin/` to exclude local Kotlin daemon caches.

### Part 4: ViewModel & Jetpack Compose UI
- `android/app/src/main/java/org/privacyguard/android/ui/Theme.kt`: Dark/Light Material 3 theme (`PrivacyGuardTheme`) with dynamic color support on Android 12+.
- `android/app/src/main/java/org/privacyguard/android/ui/PrivacyGuardViewModel.kt`:
  - Single immutable `UiState` StateFlow combining Shizuku status, active capabilities, Shield telemetry, scan state, filtered apps, and selected app policy ops.
  - Collector-leak-free reactive flow switching via `@OptIn(ExperimentalCoroutinesApi::class) _selectedPackage.flatMapLatest { ... }`.
  - Live derivation of `selectedApp` from `apps: List<AppScanResult>` ensuring live DB triage updates immediately reflect in UI.
  - Lifecycle safety: registers Shizuku listeners in `init` and explicitly unregisters in `onCleared()`.
  - Concrete component resolution (`disableTrackerComponents`): inspects manifest via `Tier1AScanner`, respects safe-listed system packages and critical service guards (`BIND_NOTIFICATION_LISTENER_SERVICE`, `BIND_ACCESSIBILITY_SERVICE`), and enqueues via `upsertPreservingSnapshotAndGen`.
  - Explicit `PrivacyGuardViewModel.Factory` companion object to avoid reflective R8 crashes.
- `android/app/src/main/java/org/privacyguard/android/ui/DashboardCard.kt`:
  - Displays Shizuku Binder connection status badge (Shell UID 2000, Permission Required with grant button, or Shizuku Offline).
  - Shield Telemetry banner (Private DNS status/hostname, Canary Sinkhole status, VPN Dual-Socket Probe leak status, and Re-probe action).
  - "Scan & Reconcile Now" action button with circular progress indicator.
- `android/app/src/main/java/org/privacyguard/android/ui/AppListScreen.kt`:
  - Outlined search bar by package name.
  - Filter chips: `All`, `High Risk (≥ 0.70)`, `Trackers`, and `Quarantined`.
  - App cards displaying package name, risk badge (color-coded red/amber/green), tracker count chip, framework tag, and OSINT verdict chip.
- `android/app/src/main/java/org/privacyguard/android/ui/AppDetailScreen.kt`:
  - Full static & ML triage breakdown (risk score, confidence percentage, detected framework, OSINT verdict, and signing certificate SHA-256).
  - Prominent Warning Banner if `CrashWatchdogWorker` triggered an unsafe rollback (exit reason 4–7).
  - Protected System Package notice when targeting safe-listed packages.
  - Interactive Action Controls: "Disable Tracker Components", "Restrict Background Networking", and "Restore Default State / Rollback".
  - Live Policy Operations table displaying all applied and pending ops with real-time status badges.
- `android/app/src/main/java/org/privacyguard/android/ui/MainActivity.kt`:
  - Hosts `PrivacyGuardAppScreen` in `ComponentActivity`, instantiating ViewModel via `PrivacyGuardViewModel.provideFactory(this)`.
  - Handles device back navigation between detail and overview screens with `BackHandler`.

---

## 2. Manifest, Privileges & Attack Surface

### Permissions Declared
- `android.permission.INTERNET`: Used exclusively for canary DNS resolution, Cloudflare trace SNI probe, and local loopback Ollama requests.
- `android.permission.ACCESS_NETWORK_STATE`: Used for `ConnectivityManager` active network, link properties, and network capabilities inspection.
- `android.permission.QUERY_ALL_PACKAGES`: Used for local package inventory and manifest component inspection.
- `moe.shizuku.manager.permission.API_V23`: Used for Shizuku binder delivery.

### Exported Components & Providers Audit
Inspection of the merged release manifest (`android/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml`) confirms exactly 3 exported components:
1. `org.privacyguard.android.ui.MainActivity`: `exported="true"`, with `android.intent.action.MAIN` and `android.intent.category.LAUNCHER`.
2. `rikka.shizuku.ShizukuProvider`: `exported="true"`, strictly guarded by `android.permission.INTERACT_ACROSS_USERS_FULL`.
3. `androidx.work.impl.background.systemjob.SystemJobService`: `exported="true"`, strictly guarded by `android.permission.BIND_JOB_SERVICE`.
4. `ScanService`: `exported="false"`, isolated under secondary process `android:process=":scan"`.
5. All third-party receivers (`ProfileInstallReceiver`, `DiagnosticsReceiver`) removed via `tools:node="remove"`.

### Shizuku Privilege Execution Boundary
- All Shizuku commands run via bound `ShizukuShellService` under shell UID (UID 2000).
- Hand-written commands are gated on strict prefix allowlist: `cmd connectivity`, `cmd appops`, `cmd package`, `pm disable`, `pm disable-user`, `pm default-state`, `pm enable`, `am kill`, `am set-standby-bucket`, `dumpsys package`, `dumpsys activity exit-info`.
- Arguments are validated with strict regex patterns: package names matching `^[a-zA-Z0-9_.]+$`, component names matching `^[a-zA-Z0-9_.$]+/[a-zA-Z0-9_.$]+$`, and permitted keywords.
- Critical system packages (`com.google.android.gms`, `com.android.vending`, `com.google.android.gsf`), notification listeners, and critical services are safe-listed from component modifications.

---

## 3. Outbound Network & Privacy Audit

| Endpoint | Method | Payloads / Fields | Telemetry Gates & Privacy Controls |
|---|---|---|---|
| `https://cloudflare-dns.com/cdn-cgi/trace` | GET | None; generic `User-Agent: PrivacyGuard/1.0`; zero cookies; no query parameters. | Active probe only to verify Private DNS, WARP, and detect egress tunnel leaks/stalls. |
| `app-measurement.com` | DNS Query | `InetAddress.getAllByName` lookup only. | Canary DNS probe only. Zero HTTP/TCP connection initiated. Checked strictly for sinkhole IPs (`0.0.0.0`, `127.0.0.1`, `::1`, `0:0:0:0:0:0:0:0`) or NXDOMAIN. |
| `http://127.0.0.1:11434/api/generate` | POST | JSON: `{ "model": "llama3.2", "prompt": "<stripped app metadata>", "format": "json" }` | Local LAN only (`network_security_config.xml` permits cleartext exclusively to loopback).<br>- Gate 1: Pinned certs for Google, Mozilla, F-Droid, AOSP bypassed without network.<br>- Gate 2: Sensitive categories (finance, health, dating, religion, VPN, password managers) never sent.<br>- Gate 3: Single-pass target execution; unmetered network + battery-not-low + $k=4$ decoy popular hashes. |

Zero user device identifiers (IMEI, Android ID, MAC, advertising ID, Wi-Fi SSIDs) or personal accounts ever touch outbound network requests.

---

## 4. Release Build & On-Device Verification

### 1. Test & Build Execution
Command:
```powershell
.\gradlew.bat testReleaseUnitTest assembleRelease --stacktrace
```
Output:
```
BUILD SUCCESSFUL in 1m 9s
66 actionable tasks: 17 executed, 49 up-to-date
```
Unit test execution:
- `Ed25519VerifierTest`: 4 tests passed (trackers verification, model verification, tampered data failure, corrupted signature failure).
- `LightGbmParityTest`: 1 test passed (model deserialization, 16 features, 4 classes, softmax summation to 1.0, isotonic risk bounds).

### 2. 16 KB Page Alignment Verification (`zipalign`)
Command:
```powershell
& "C:\Users\vcpav\AppData\Local\Android\Sdk\build-tools\35.0.0\zipalign.exe" -c -P 16 -v 4 "android/app/build/outputs/apk/release/app-release.apk"
```
Output:
```
 2703360 lib/arm64-v8a/libandroidx.graphics.path.so (OK)
 2719744 lib/arm64-v8a/libprivacyguard_scan.so (OK)
...
Verification succesful
```
`(2703360 % 16384 == 0)` and `(2719744 % 16384 == 0)` confirm 16 KB page alignment for both native libraries within the release APK archive.

### 3. NDK ELF Segment Alignment Verification (`llvm-readobj`)
Command:
```powershell
& "C:\Users\vcpav\AppData\Local\Android\Sdk\ndk\28.0.13004108\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readobj.exe" -l "android/app/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/arm64-v8a/libprivacyguard_scan.so"
```
Output:
```
  ProgramHeader {
    Type: PT_LOAD (0x1)
    Offset: 0x0
    VirtualAddress: 0x0
    PhysicalAddress: 0x0
    FileSize: 415840
    MemSize: 415840
    Flags [ (0x4)
      PF_R (0x4)
    ]
    Alignment: 16384
  }
  ProgramHeader {
    Type: PT_LOAD (0x1)
    Offset: 0x65860
    VirtualAddress: 0x69860
    PhysicalAddress: 0x69860
    FileSize: 467136
    MemSize: 467136
    Flags [ (0x5)
      PF_R (0x4)
      PF_X (0x1)
    ]
    Alignment: 16384
  }
  ProgramHeader {
    Type: PT_LOAD (0x1)
    Offset: 0xD7920
    VirtualAddress: 0xDF920
    PhysicalAddress: 0xDF920
    FileSize: 34192
    MemSize: 34528
    Flags [ (0x6)
      PF_R (0x4)
      PF_W (0x2)
    ]
    Alignment: 16384
  }
  ProgramHeader {
    Type: PT_LOAD (0x1)
    Offset: 0xDFEB0
    VirtualAddress: 0xEBEB0
    PhysicalAddress: 0xEBEB0
    FileSize: 480
    MemSize: 8512
    Flags [ (0x6)
      PF_R (0x4)
      PF_W (0x2)
    ]
    Alignment: 16384
  }
```
All four `PT_LOAD` segments exhibit `Alignment: 16384` (0x4000), fully compliant with Android 15+ 16 KB page requirements.
