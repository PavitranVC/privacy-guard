# Implementation Plan: Final Release Remediation & Security Hardening (Post-M3 Principal Audit)

Execute the comprehensive security, state-machine, and sandbox hardening pass across Milestones 1A–3 in strict accordance with `GEMINI.md` and `docs/architecture.md`.

---

## Part 1: Shizuku Shell Sandbox & Lifecycle Hardening

### 1. `ShizukuShellService.kt`
- **Strict 3-Token Subcommand Allowlist:**
  - Map allowed 3rd-token subcommands for multi-verb binaries:
    ```kotlin
    private val SUBCOMMANDS: Map<String, Set<String>> = mapOf(
        "cmd package" to setOf("resolve-activity", "query-services", "query-receivers", "query-activities", "list", "path"),
        "cmd appops" to setOf("set", "get"),
        "cmd connectivity" to setOf("set-chain3-enabled", "set-package-networking-enabled", "get-chain3-enabled"),
        "dumpsys activity" to setOf("exit-info")
    )
    ```
  - If a command matches a key in `SUBCOMMANDS`, require `cmd.size >= 3` and validate `cmd[2]` against the set; reject otherwise (`exitCode = -1, stderr = "Subcommand rejected"`).
  - Enforce strict argument rules per command type:
    - Component commands (`pm enable`, `pm disable`, `pm disable-user`, `pm default-state`): enforce `--user <id>` followed by component name matching `^[a-zA-Z0-9_.$]+/[a-zA-Z0-9_.$]+$`.
    - Package/appops/connectivity commands: package arguments matching `^[a-zA-Z0-9_.]+$`, numeric UIDs, flags `-*`, and allowed keywords (`restricted`, `rare`, `ignore`, `true`, `false`, `exit-info`, `set-chain3-enabled`, `set-package-networking-enabled`, `oem_deny_3`, `add`, `remove`, `set`, `get`, `RUN_ANY_IN_BACKGROUND`).
  - Prepend canonical path: execute `arrayOf("/system/bin/${binary}") + cmd.drop(1)`.
- **Pipe Timeout & Process Cleanup:**
  - Wrap in `try { ... } finally { process.destroyForcibly() }`.
  - Enforce a 2-second timeout on stream futures: `stdoutFuture.get(2, TimeUnit.SECONDS)` and `stderrFuture.get(2, TimeUnit.SECONDS)`, catching `TimeoutException` and returning partial output.

### 2. `ShizukuManager.kt`
- **SupervisorScope:**
  - Initialize scope with `SupervisorJob() + ioSingle + CoroutineExceptionHandler`.
- **Rebind & Disconnection Handling:**
  - In `onServiceDisconnected`, launch coroutine delaying 2000ms then call `checkAndBindUserService()`.
  - In `checkAndBindUserService()`, launch 10s watchdog to reset `isBinding = false` if service fails to connect.
- **Snapshot Persistence Verification:**
  - In `executePolicyOp` (`disable_components`): check `snapshotJson.isNotBlank() && snapshotJson != "{}"`.
  - Call `db.policyOpDao().saveSnapshot(op.id, activeGen, snapshotJson)`. Verify `savedRows == 1`. If not persisted, call `handleTransientFailure(op.id, activeGen, op.attempts, "SNAPSHOT_NOT_PERSISTED")` and abort mutation.
- **Row Check & Watchdog Policy:**
  - Update `val opSucceeded = (rowsUpdated == 1)`.
  - In `scheduleCrashWatchdog`, change `ExistingWorkPolicy.KEEP` to `ExistingWorkPolicy.REPLACE`.
- **Rollback Hardening (`rollbackPackage`):**
  - Filter target ops: `status in setOf(APPLIED, APPLIED_PARTIAL, DRIFT, APPLYING, FAILED_TRANSIENT, PENDING)` sorted by `id DESC`. Never touch `ROLLED_BACK_UNSAFE` or `SUPERSEDED`.
  - Supersede `PENDING` or blank-snapshot ops directly.
  - Verify `r1.exitCode == 0 && r2.exitCode == 0 && r3.exitCode == 0` for `isolate_wifi` rollback before marking superseded.
  - Read-back verification for component restoration: `val expectedState = if (state in 1..3) state else 0`; assert `observedState == expectedState`.

---

## Part 2: CrashWatchdogWorker & Room State Machine (`PolicyOpDao.kt`)

### 1. `CrashWatchdogWorker.kt`
- **Full Timestamp Regex:**
  - Replace `substringBefore(" ")` with `Regex("""timestamp=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})""")`.
- **Reason Regex Collision Fix:**
  - Use negative lookbehind and word boundary: `Regex("""(?<![A-Za-z])reason=([4-7])\b""")`.
- **Blank Snapshot & Retry Guard:**
  - Return `false` if snapshot is blank or `"{}"`.
  - Cap retries at 5: `if (runAttemptCount >= 5) return Result.failure()`.
  - Re-read fresh op via `val freshOp = db.policyOpDao().getById(op.id)` and pass `freshOp.gen` to `markRolledBackUnsafe`.
  - In component restore: handle state `0` and `4` explicitly (`expectedState = if (state in 1..3) state else 0`).

### 2. `PolicyOpDao.kt`
- **Empty Snapshot Guard:**
  - Update `saveSnapshot` query: `SET snapshot = CASE WHEN snapshot IS NULL OR snapshot = '' OR snapshot = '{}' THEN :snapshot ELSE snapshot END`.
- **`ROLLED_BACK_UNSAFE` Protection:**
  - Add `@Query("SELECT COUNT(*) FROM policy_op WHERE pkg = :pkg AND op = :op AND status = 'ROLLED_BACK_UNSAFE'") suspend fun countLocked(pkg: String, op: String): Int`.
  - In `upsertPreservingSnapshotAndGen`, if `countLocked(op.pkg, op.op) > 0`, return `-1L`.
  - In `markDriftPending`, add `AND status IN ('APPLIED', 'APPLIED_PARTIAL', 'DRIFT')`.
  - In `resetForNewVersion`, add `AND status != 'ROLLED_BACK_UNSAFE'`.
- **User Re-Apply After Rollback:**
  - Add `userInitiated: Boolean = false` to `upsertPreservingSnapshotAndGen`.
  - When `!versionChanged && userInitiated && existing.status in setOf("SUPERSEDED", "FAILED_PERMANENT", "FAILED_TRANSIENT")`, reset row to allow clean re-apply.

---

## Part 3: ShieldProbe, OsintClassifierWorker, ViewModel & Gradle Signing

### 1. `ShieldProbe.kt`
- **Offline False Positive Fix:**
  - Check `activeNetwork == null` early.
  - In canary DNS check, if `UnknownHostException` is caught for `app-measurement.com`, verify control resolution `InetAddress.getAllByName("cloudflare-dns.com")` succeeds before flagging sinkhole verified.
- **Physical Underlying Network & Dual-Stack Leak Probe:**
  - Discover underlying physical network via `cm.allNetworks` matching non-VPN, internet-capable, validated network.
  - Resolve `Inet4Address` and `Inet6Address` for `cloudflare-dns.com` on active and underlying networks.
  - Probe trace using custom `SSLSocketFactory` connecting underlying socket via `network.socketFactory.createSocket(inetAddr, 443)` and wrapping with `(SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(...)`.
  - If IPv4 or IPv6 egress IPs match across VPN and physical networks, report `TunnelStatus.LEAKING`.
- **Socket & Connection Cleanup:**
  - Set `Connection: close` and disconnect in `finally`.

### 2. `OsintClassifierWorker.kt` & `ReconcileDrainWorker.kt`
- **Cert Pin Cleanup:**
  - Remove public AOSP test key and 40-hex SHA-1 entry; keep only valid 64-hex SHA-256 production publisher pins.
- **Cancellation & Retry Caps:**
  - Catch and rethrow `CancellationException`.
  - Cap retries via `if (runAttemptCount >= 5) return Result.failure()`.

### 3. `PrivacyGuardViewModel.kt` & `AppDetailScreen.kt`
- **Main-Thread I/O Fix:**
  - Initialize `_shieldStatus = MutableStateFlow(UiState().shieldStatus)`; populate asynchronously on `Dispatchers.IO` in `init`.
- **Safe-List & Accurate Timestamps:**
  - In `restrictBackgroundNetworking`: check `ShellOps.isSafeListed(app, pkg)`.
  - In `AppDetailScreen.kt`: disable background restriction button if `isProtected`.
  - In both policy enqueue methods: fetch `pkgInfo` from PackageManager and set actual `versionCode` and `lastUpdateTime`, passing `userInitiated = true`.

### 4. `android/app/build.gradle.kts`
- Remove configuration-time writes of `"privacyguard123"` and hardcoded password fallbacks.
- Read release signing config exclusively from `keystore.properties` or environment variables (`PG_KEYSTORE_FILE`, `PG_KEYSTORE_PASSWORD`, `PG_KEY_ALIAS`, `PG_KEY_PASSWORD`). If absent, set `release.signingConfig = null`. Support `-PrequireReleaseSigning=true` enforcement check in task graph.

---

## Verification & Deliverables
1. Run `.\gradlew.bat testReleaseUnitTest assembleRelease --stacktrace`.
2. Run `zipalign -c -P 16 -v 4` on `app-release.apk` (or unsigned release APK) and `llvm-readobj -l` on `libprivacyguard_scan.so`.
3. Output `walkthrough.md`.

---

## Proposed: androidTest dependencies (AWAITING USER APPROVAL)

### 1. Proposed `android/gradle/libs.versions.toml` Entries
Under `[versions]`:
```toml
androidx-test-runner = "1.6.2"
androidx-test-ext-junit = "1.2.1"
```

Under `[libraries]`:
```toml
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidx-test-runner" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidx-test-ext-junit" }
```

### 2. Proposed `android/app/build.gradle.kts` Lines
Under `dependencies { ... }`:
```kotlin
    // On-Device Instrumentation Testing
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
```

### 3. Instrumentation Runner Class
Configured in `defaultConfig`:
`testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` (already declared in `android/app/build.gradle.kts` line 60).

### 4. Manual Run Flow (Preserving Shizuku Grants & App State)
Do NOT use `./gradlew connectedAndroidTest` (which wipes application data and revokes user-granted Shizuku permissions).
Instead, follow this manual deployment and execution flow:
1. Build debug and test APKs:
   ```powershell
   cd android ; .\gradlew.bat assembleDebug assembleDebugAndroidTest
   ```
2. Reinstall application APK without wiping permissions/data (`-r`):
   ```powershell
   & $ADB install -r android/app/build/outputs/apk/debug/app-debug.apk
   ```
3. Install test APK with test permission (`-r -t`):
   ```powershell
   & $ADB install -r -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
   ```
4. Run instrumentation tests inside the app process (inheriting the user's Shizuku binder grant):
   ```powershell
   & $ADB shell am instrument -w -e class org.privacyguard.android.shizuku.AppPathIsolationTest org.privacyguard.android.test/androidx.test.runner.AndroidJUnitRunner
   ```
5. Teardown: Uninstall ONLY the test instrumentation package, NEVER the main app:
   ```powershell
   & $ADB uninstall org.privacyguard.android.test
   ```

### 5. On-Device Test Scenarios (S1–S5)
- **S1 (Rare / Ignore):** Prior bucket `rare` (40) + appops `ignore` -> isolate -> rollback must give 40 / `ignore`.
- **S2 (Baseline / Allow):** Default prior (`10` / `active`, no override) -> isolate -> rollback must give 10 and baseline text `"No operations. Default mode: allow"`.
- **S3 (Frequent / Deny - Audit G1):** Prior bucket `frequent` (30) + appops `deny` -> isolate -> rollback must give 30 / `deny`.
- **S4 (State Mutation Re-Apply - Audit G2):** Default prior -> isolate -> rollback -> set bucket `40` and appops `ignore` by hand -> isolate again -> rollback must give 40 / `ignore`, NOT 10 / `allow`.
- **S5 (Never / Restricted):** Prior bucket `never` (50) -> isolate -> rollback must give 50. If OS refuses to assign bucket 50 during setup, report the raw error and skip gracefully.

### 6. Operational Risks & Invariants
- **NEVER use `./gradlew connectedAndroidTest`:** Gradle's built-in target executes `pm uninstall` before installing test artifacts, destroying Shizuku pairing tokens, user-granted binder authorization, and Room database records.
- **Capability Row Cleanup:** Ensure any capability probe overrides or test policy rows injected into Room during hardware instrumentation runs are cleaned up or isolated to test package identifiers (`com.ansangha.drdriving`, etc.), leaving zero orphaned policy rows.

---

## M4-C: Per-App Cache Clear Probe (DRAFT - AWAITING OWNER APPROVAL)

### 1. Stage 0: CLI Long-Timeout Probe (Prior to AIDL / Code Changes)
- **Rationale:**
  The command-line invocation `pm clear --cache-only --user 0 <pkg>` executes the exact same underlying system server IPC path (`IPackageManager.deleteApplicationCacheFilesAsUser` paired with an internal observer latch). The cause of the 10-second timeouts observed during M4-B on hardware (`com.ansangha.drdriving` and `com.rarlab.rar`) is `UNPROVEN`—it remains unverified whether the system server was stalled, performing background disk indexing, or simply required more than 10 seconds to complete cache cleanup on large directories.
- **Probe Protocol:**
  Execute `pm clear --cache-only --user 0 <pkg>` on ONE designated test package (`com.ansangha.drdriving`) with a **120-second timeout** via Shizuku shell. Record exact exit code, elapsed execution milliseconds, and raw terminal output into `C:\Projects\_smoke_m4c\ledger.txt`.
- **Measurement via Main App Process:**
  Measure bytes freed directly from the **MAIN application process** (`org.privacyguard.android`) using Android's standard `StorageStatsManager.queryStatsForPackage(UUID, String, UserHandle)`. This completely eliminates the architectural challenge of querying `StorageStatsManager` inside the Shizuku `UserService` (which lacks a full Context and system service resolution).
  - *Usage Access Permission (`PACKAGE_USAGE_STATS`):* Must be granted to the main application process. This will be granted either via standard user navigation to Android "Usage Access" settings or via privileged shell command `cmd appops set org.privacyguard.android GET_USAGE_STATS allow`. Runtime grant behaviour via appops is marked as `UNPROVEN` until tested on hardware.
  - *Storage Reporting Latency:* Whether `StorageStatsManager.queryStatsForPackage` immediately reflects cache deletion deltas or returns cached metrics until periodic system calculation is marked as `UNPROVEN`.
- **Stage Gate:**
  Proceed to Stage 1 **ONLY** if Stage 0 fails (non-zero exit code, error) or times out at the full 120 seconds. If Stage 0 succeeds, in-process binder hooks and AIDL additions are wholly unnecessary.

### 2. Stage 1: In-Process Binder Probe (Conditional on Stage 0 Failure)
- **AIDL Interface & PrivilegeBackend Integration:**
  If Stage 0 fails, `IShellService.aidl` will be extended with:
  ```aidl
  package org.privacyguard.android.service;

  import org.privacyguard.android.service.ShellResult;

  interface IShellService {
      ShellResult exec(in String[] cmd) = 1;
      boolean clearPackageCache(in String pkg, int userId) = 2;
      void destroy() = 16777114;
  }
  ```
  `PrivilegeBackend.kt` will expose `fun clearPackageCache(pkg: String, userId: Int): Boolean`, implemented in `ShizukuBackend` to delegate to `shellService.clearPackageCache(pkg, userId)`.
- **HiddenApiBypass in UserService:**
  Because `PrivacyGuardApp.onCreate()` executes only in the main application process, `HiddenApiBypass.addHiddenApiExemptions("")` is not active in the secondary Shizuku `UserService` process (`com.android.shell`, UID 2000). If reflection is required in `UserService`, `HiddenApiBypass` must be initialized within `ShizukuShellService`.
- **Observer & Stubs Rule (Grounded in Item 1 Bytecode Inspection):**
  Per project safety invariants, dynamic proxies (`java.lang.reflect.Proxy`) and hand-rolled local AIDL stub copies are prohibited. The observer callback MUST be a concrete `Binder` subclass implementing `IPackageDataObserver.Stub` from `dev.rikka.hidden:stub:4.4.0`.
  - **Bytecode Inspection Finding:** Bytecode verification via `javap` on `stub-4.4.0-api.jar` conclusively proved:
    1. `android.content.pm.IPackageDataObserver` and `android.content.pm.IPackageDataObserver$Stub` are completely **MISSING** from `dev.rikka.hidden:stub:4.4.0`.
    2. `android.content.pm.IPackageManager` in `stub:4.4.0` does **NOT** contain `deleteApplicationCacheFiles` or `deleteApplicationCacheFilesAsUser`.
  - **Mandatory Hard Gate:** Because both `deleteApplicationCacheFilesAsUser` and `IPackageDataObserver.Stub` are missing from `dev.rikka.hidden:stub:4.4.0`: **STOP and ask the owner** before proceeding to Stage 1.
- **Build & TOML Invariant:**
  Confirm zero changes required to build scripts:
  - `android/gradle/libs.versions.toml`: No changes required (versions pinned at lines 10–13, 51–55).
  - `android/app/build.gradle.kts`: No changes required (plugin line 9, dependencies lines 180–184).

### 3. Validation & Safe Allowlist Enforcement
- **Input Validation:**
  Before executing any cache deletion, `ShizukuShellService` must enforce:
  1. `userId >= 0`.
  2. Package name matches `PKG_REGEX` (`^[a-zA-Z0-9_]+\.[a-zA-Z0-9_.]+$`).
- **Safety Boundaries & Denylist:**
  - Reject system packages: `android`, `com.android.systemui`, `com.android.settings`.
  - Reject telephony / messaging / dialer packages.
  - Reject Google Mobile Services: `com.google.android.gms`, `com.android.vending`, `com.google.android.gsf`.
  - Reject Shizuku itself: `moe.shizuku.privileged.api`.
  - Reject Privacy Guard itself: `org.privacyguard.android`.
- **Probe Allowlist:**
  During M4-C probe validation, execution is hard-gated strictly to the three designated test packages:
  `com.ansangha.drdriving`, `com.ludo.king`, `com.rarlab.rar`.

### 4. Fallback Mechanism: `pm trim-caches`
- **Allowlist & Prefix Validation:**
  Add `pm trim-caches` to `ShizukuShellService.ALLOWED_COMMAND_PREFIXES`. Enforce strict syntax validation: command must be `pm trim-caches <bytes>` where `<bytes>` contains **digits only** (`cmd[2].all { it in '0'..'9' }`).
- **User-Chosen Size Invariant:**
  The target size is strictly user-selected (e.g. 500 MB, 1 GB, 5 GB). **No hardcoded 200 GB default**.
- **UI Warning Invariant:**
  `pm trim-caches` is **DEVICE-WIDE**. It cannot target an individual application.
  The UI must display an explicit warning:
  *"Device-wide cache trimming frees temporary files across all installed applications. Individual application cache selection is not supported in fallback mode."*
- **Success Criterion:**
  **Exit code 0 is NOT evidence of success.** The system server routinely returns exit code 0 even if no caches were pruned. Success must be verified strictly by measuring an actual increase in available disk space via `StorageStatsManager` or `StatFs`.

### 5. Probe Constraints & Safety Rules
- On-device probe testing restricted exclusively to `com.ansangha.drdriving`, `com.ludo.king`, and `com.rarlab.rar`.
- Every probe command logged in `C:\Projects\_smoke_m4c\ledger.txt` with timestamp, exact command/API, exit code, matching undo, and dedicated raw output capture file.
- **NEVER** run plain `pm clear <pkg>` without `--cache-only`.
- **NEVER** run `gradlew connectedAndroidTest`.

---

## M4-D: Fast Space Optimiser (DRAFT - AWAITING OWNER APPROVAL)

### 1. Architectural Foundation & Grounding in M4-C Evidence
Milestone M4-C hardware probes on Moto G54 (Android 15) established two definitive facts:
1. `pm clear --cache-only --user 0 <pkg>` is **non-viable**: it hangs indefinitely in the system server (4/4 timeouts up to 120s; 0 bytes freed) on multiple distinct packages.
2. `pm trim-caches <targetBytes>` is **viable device-wide**: executed in 1.9s, reclaiming +555,824 KB (~542.8 MB) of storage measured via `df -k /data`.
3. Main-process `StorageStatsManager.queryStatsForPackage` is **viable and fast**: requires `PACKAGE_USAGE_STATS` (already declared in `AndroidManifest.xml` in M4-C commit `e6989a4`) and accurately reports live per-app `appBytes`, `dataBytes`, and `cacheBytes` in under 100 ms without AIDL or IPC overhead.

### 2. Per-App Cache Breakdown (Read-Only, Fast)
- **Mechanism:** Main application process queries `StorageStatsManager.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())`.
- **Usage Access Onboarding Flow:**
  - Check whether `AppOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)` returns `MODE_ALLOWED`.
  - If allowed: immediately query storage statistics.
  - If not allowed:
    - If Shizuku is active and authorized: execute `cmd appops set org.privacyguard.android GET_USAGE_STATS allow` via `PrivilegeBackend` (already proven in M4-C probes `p4`, `p13`, `p21`).
    - If Shizuku is inactive: prompt user with standard intent `Settings.ACTION_USAGE_ACCESS_SETTINGS`.
- **Planned New Kotlin Source Files (Zero Build/AIDL Edits):**
  - `android/app/src/main/java/org/privacyguard/android/storage/StorageStatsHelper.kt`: Wraps `StorageStatsManager` query logic, returning immutable `PackageStorageStats(pkg, appBytes, dataBytes, cacheBytes)`.
  - `android/app/src/main/java/org/privacyguard/android/storage/TrimCachesCommandBuilder.kt`: Pure function command builder and validator.
- **Build Invariants:** Zero changes to `android/gradle/libs.versions.toml`, `build.gradle.kts`, `CMakeLists.txt`, or `*.aidl`.

### 3. Device-Wide Cache Trim (`pm trim-caches`)
- **Allowlist & Prefix Validation:**
  - Extend `ShizukuShellService.ALLOWED_COMMAND_PREFIXES` to support `pm trim-caches`.
  - Strict validator:
    ```kotlin
    binary == "pm" && subcmd == "trim-caches" &&
    cmd.size == 3 && cmd[2].isNotEmpty() && cmd[2].all { it in '0'..'9' }
    ```
  - Comprehensive unit test coverage in `ShizukuShellServiceTest.kt` verifying allowed numeric invocations and rejecting negative numbers, non-digits, extra arguments, and missing sizes.
- **Target Size Calculation (No Hardcoded Defaults):**
  - Read current free bytes using `StatFs(Environment.getDataDirectory().path).availableBytes`.
  - User chooses a desired reclaim delta (e.g. +250 MB, +500 MB, +1 GB).
  - Target argument is computed strictly as:
    `targetBytes = currentFreeBytes + chosenDeltaBytes`
- **Mandatory UI Warning Invariant:**
  - The UI must prominently state:
    *"Device-wide cache trimming requests the Android system to purge temporary cache files across all applications using system LRU policies. Individual application cache selection is not supported at shell privilege on Android 15."*
- **Measurement of Freed Space:**
  - Success is evaluated exclusively by comparing `StatFs` / `df -k /data` free space before and after execution. Exit code 0 is treated only as command termination, not as evidence of space reclaimed.

### 4. Optional Per-App Deep-Link (Manual User Cleanup)
- For users wishing to clear an individual application's cache:
- Provide an "App Info" button next to each package in the breakdown list that opens:
  ```kotlin
  Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.fromParts("package", pkg, null)
  }
  ```
  This allows the user to tap Android's native per-app "Clear cache" button directly in System Settings without requiring any elevated privileges.

### 5. Honest UI & Verification Constraints
- Distinguish clearly between:
  1. *Per-App Reported Cache:* Live metrics reported by `StorageStatsManager`.
  2. *Device-Wide Measured Delta:* Actual bytes reclaimed across `/data` post-trim.
- Zero background scanning loops: stats queries run strictly on-demand when the space optimiser screen is opened.


