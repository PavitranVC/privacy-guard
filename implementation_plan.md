# Implementation Plan: Milestone 3 — M2 Edge-Case Polish, Jetpack Compose UI & Interactive Policy Controls

Implement **Milestone 3** of `privacy-guard`: edge-case remediations for Milestone 2, modern Material 3 Jetpack Compose UI, reactive Room `Flow`s, ViewModel state management, interactive tracker eradication controls, and end-to-end verification under R8 release mode and 16 KB page alignment.

---

## Part 1: Milestone 2 Edge-Case Remediations

1. **`ShizukuShellService.kt` & `ShellOps.kt` Allowlist Synchronization:**
   - Update `ALLOWED_COMMAND_PREFIXES` in `ShizukuShellService` to include:
     - `"cmd connectivity"`
     - `"cmd appops"`
     - `"cmd package"`
     - `"pm disable-user"`
     - `"pm default-state"`
     - `"pm enable"`
     - `"pm disable"`
     - `"am kill"`
     - `"am set-standby-bucket"`
     - `"dumpsys package"`
     - `"dumpsys activity exit-info"`
     - `"dumpsys activity"`
   - Refine argument validation in `ShizukuShellService.exec`:
     - Allow component name arguments matching `^[a-zA-Z0-9_.$]+/[a-zA-Z0-9_.$]+$`.
     - Allow package name arguments matching `^[a-zA-Z0-9_.]+$`.
     - Allow numeric UIDs, flags starting with `-` or `--`, and permitted command keywords (`set-chain3-enabled`, `set-package-networking-enabled`, `oem_deny_3`, `add`, `remove`, `set`, `get`, `restricted`, `rare`, `ignore`, `true`, `false`, `exit-info`, `RUN_ANY_IN_BACKGROUND`).

2. **`ShieldProbe.kt` IPv6 Sinkhole Coverage:**
   - Update `probeCanarySinkhole()` to verify `addresses.isNotEmpty()` and ensure IPv6 unspecified addresses (`::` / `0:0:0:0:0:0:0:0`) are recognized alongside `0.0.0.0`, `127.0.0.1`, and `::1`:
     ```kotlin
     addresses.isNotEmpty() && addresses.all { addr ->
         addr.isAnyLocalAddress ||
         addr.isLoopbackAddress ||
         addr.hostAddress == "0.0.0.0" ||
         addr.hostAddress == "::" ||
         addr.hostAddress == "0:0:0:0:0:0:0:0" ||
         addr.hostAddress == "127.0.0.1" ||
         addr.hostAddress == "::1"
     }
     ```

3. **`OsintClassifierWorker.kt` Loopback Inference Efficiency:**
   - Maintain strict single-pass LLM inference for target packages over local loopback Ollama (`http://127.0.0.1:11434/api/generate`), explicitly avoiding redundant $k=4$ inference loops for decoy hashes on loopback.

4. **Documentation & Spec Synchronization:**
   - Explicitly document the production pinned SPKI Ed25519 public key (`MCowBQYDK2VwAyEAgCYpThvpyHUz6FbXD+eFlCHG818ajDs/UOcGH6HV6ew=`) in `docs/architecture.md` and `AUDIT_RULES.md`.

---

## Part 2: Jetpack Compose UI & Reactive Architecture

### 1. Build & Dependency Configuration (`libs.versions.toml`, `build.gradle.kts`)
- In `android/gradle/libs.versions.toml`:
  - Add `kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }`
  - Add Compose BOM (`2024.09.02`), `activity-compose` (`1.9.2`), and `lifecycle` (`2.8.6`).
  - Declare Compose libraries: `androidx-compose-bom`, `androidx-compose-ui`, `androidx-compose-ui-graphics`, `androidx-compose-ui-tooling-preview`, `androidx-compose-material3`, `androidx-activity-compose`, `androidx-lifecycle-runtime-compose`, `androidx-lifecycle-viewmodel-compose`, `androidx-compose-material-icons-extended`.
- In `android/build.gradle.kts`:
  - Register `alias(libs.plugins.kotlin.compose) apply false`.
- In `android/app/build.gradle.kts`:
  - Apply `alias(libs.plugins.kotlin.compose)`.
  - Enable `buildFeatures { compose = true }`.
  - Add Compose dependencies without Google Play Services or Firebase.
- In `android/app/proguard-rules.pro`:
  - Add keep rules for Compose Material 3 and ViewModel reflection in R8 full mode.

### 2. Room Flow Extensions
- `AppScanResultDao.kt`:
  - Add `@Query("SELECT * FROM app_scan_result ORDER BY riskScore DESC") fun observeAllSortedByRisk(): Flow<List<AppScanResult>>`.
- `PolicyOpDao.kt`:
  - Add `@Query("SELECT * FROM policy_op WHERE pkg = :pkg ORDER BY id ASC") fun observeByPackage(pkg: String): Flow<List<PolicyOp>>`.
  - Add `@Query("SELECT * FROM policy_op ORDER BY id ASC") fun observeAll(): Flow<List<PolicyOp>>`.
- `CapabilityDao.kt`:
  - Add `@Query("SELECT * FROM capability") fun observeAllCapabilities(): Flow<List<Capability>>`.

### 3. State Management (`PrivacyGuardViewModel.kt`)
- Define immutable `UiState`:
  - `shizukuStatus`: `ShizukuConnectionStatus` (`CONNECTED`, `PERMISSION_REQUIRED`, `UNAVAILABLE`)
  - `activeCapabilities`: `List<Capability>`
  - `isScanning`: `Boolean`
  - `shieldStatus`: `ShieldTelemetry`
  - `apps`: `List<AppScanResult>`
  - `filteredApps`: computed based on `filter` (`ALL`, `HIGH_RISK`, `TRACKERS_DETECTED`, `QUARANTINED`) and `searchQuery`
  - `selectedApp`: `AppScanResult?`
  - `selectedAppOps`: `List<PolicyOp>`
  - `hasUnsafeRollback`: `Boolean`
  - `isSafeListed`: `Boolean`
  - `messageBanner`: `String?`
- Actions & Operations:
  - `reprobeShield()`: background probe via `ShieldProbe.checkShield(context)`.
  - `requestShizukuPermission()`: calls `ShizukuManager.requestPermission(1001)`.
  - `triggerScanAndReconcile()`: triggers package inventory scan + drains reconcile requests.
  - `disableTrackerComponents(pkg, components)`: creates `PolicyOp`s with `OP_DISABLE_COMPONENTS` and triggers `drainRunnableOps()`.
  - `restrictBackground(pkg)`: creates `PolicyOp` with `OP_ISOLATE_WIFI` and triggers `drainRunnableOps()`.
  - `rollbackPackage(pkg)`: restores component states to initial snapshot (`pm default-state`/`enable`) and triggers `drainRunnableOps()`.

### 4. UI Screens & Components
- **`PrivacyGuardTheme.kt`**: Dark/Light Material 3 theme with high-contrast privacy badges.
- **`MainActivity.kt`**: `ComponentActivity` hosting `PrivacyGuardAppScreen` with `collectAsStateWithLifecycle()`.
- **`DashboardCard.kt`**:
  - Shizuku Binder status chip (Green with UID 2000; Amber "Grant Permission" button; Red "Service Unavailable").
  - Shield Telemetry card: Private DNS hostname, Canary sinkhole indicator, Dual-socket VPN tunnel health badge, and "Re-probe" button.
  - "Scan & Reconcile" action button with circular progress indicator.
- **`AppListScreen.kt`**:
  - Filter chips row + Search bar.
  - List of app cards showing package name, risk badge, tracker count, framework chip, and OSINT verdict chip.
  - Clicking an app navigates to `AppDetailScreen`.
- **`AppDetailScreen.kt`**:
  - Full static & ML triage metrics (risk score, confidence, entropy, signing cert SHA-256).
  - Prominent warning banner if `ROLLED_BACK_UNSAFE` was triggered by `CrashWatchdogWorker`.
  - System package protection notice if safe-listed.
  - List of detected tracker components with one-click "Disable Tracker Components" button.
  - Background & Wi-Fi isolation control ladder.
  - "Rollback / Restore Snapshot" button.
  - Live policy operations table with real-time status updates (`PENDING`, `APPLYING`, `APPLIED`, `APPLIED_PARTIAL`, `DRIFT`, `ROLLED_BACK_UNSAFE`).

---

## Part 3: Verification & Audit Protocol
1. **Compilation & Unit Tests:**
   `.\gradlew.bat testReleaseUnitTest assembleRelease --stacktrace`
2. **Merged Manifest Audit:**
   Verify ONLY `MainActivity`, `ShizukuProvider`, and `SystemJobService` are exported.
3. **16 KB Alignment Check:**
   `zipalign -c -P 16 -v 4 app-release.apk`
   `llvm-readobj -l libprivacyguard_scan.so` (confirm all `PT_LOAD` segments at `Alignment: 16384`).
4. **Documentation:**
   Update `walkthrough.md`.
