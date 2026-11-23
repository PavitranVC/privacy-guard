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

### 1. AIDL & Build/TOML Specification
- **CLI vs In-Process Invocation:**
  In M4-B, executing `pm clear --cache-only --user 0 <pkg>` via the shell process builder timed out after 10 seconds (exit code 124) on hardware (`com.ansangha.drdriving` and `com.rarlab.rar`). The underlying Android package cache deletion API is an in-process IPC method on `IPackageManager`, not a responsive command-line binary.
- **AIDL Interface Evolution:**
  Existing `IShellService.aidl` provides only `ShellResult exec(in String[] cmd)` and `void destroy()`. In-process execution within the bound Shizuku `UserService` requires extending `IShellService.aidl`:
  ```aidl
  package org.privacyguard.android.service;

  import org.privacyguard.android.service.ShellResult;

  interface IShellService {
      ShellResult exec(in String[] cmd) = 1;
      boolean clearPackageCache(in String pkg, int userId) = 2;
      void destroy() = 16777114;
  }
  ```
- **Build & TOML Changes:**
  - `android/gradle/libs.versions.toml`: **NO changes required**. Dependencies `hidden-stub = "4.4.0"`, `hidden-compat = "4.4.0"`, `refine = "4.4.0"`, and `hiddenapibypass = "6.1"` are already pinned (lines 10–13, 51–55).
  - `android/app/build.gradle.kts`: **NO changes required**. Plugin `alias(libs.plugins.refine)` (line 9) and dependencies `compileOnly(libs.hidden.stub)`, `implementation(libs.hidden.compat)`, `implementation(libs.refine.runtime)`, `compileOnly(libs.refine.annotation)`, and `implementation(libs.hiddenapibypass)` are already present (lines 180–184).

### 2. Hidden-API Stubs & Reflection Architecture
- **Target System API:**
  `IPackageManager.deleteApplicationCacheFilesAsUser(String packageName, int userId, IPackageDataObserver observer)` (or `deleteApplicationCacheFiles(String packageName, IPackageDataObserver observer)` on legacy/compat signatures).
- **Execution Context:**
  Runs inside `ShizukuShellService`, hosted in the Shizuku `UserService` process running under package `com.android.shell` (UID 2000).
- **Stubs vs Reflection (`UNPROVEN`):**
  - Whether `dev.rikka.hidden:stub:4.4.0` exposes `IPackageManager.deleteApplicationCacheFilesAsUser` and `IPackageDataObserver` on API 35 compile-time classpath without signature mismatches is `UNPROVEN`.
  - If stub definitions are missing or incomplete, invocation will utilize `org.lsposed.hiddenapibypass.HiddenApiBypass`:
    1. Obtain the `package` service binder: `android.os.ServiceManager.getService("package")`.
    2. Obtain the `IPackageManager` stub interface via `IPackageManager.Stub.asInterface(...)` or reflective `HiddenApiBypass.invoke`.
    3. Implement `IPackageDataObserver` via a dynamic Binder proxy (`java.lang.reflect.Proxy`) or local AIDL stub to intercept `onRemoveCompleted(String packageName, boolean succeeded)`.
    4. Await callback completion using a `CountDownLatch(1)` with a strict 5-second timeout.

### 3. Validation & Safe Allowlist Enforcement
- **Input Validation:**
  Before executing cache deletion, `ShizukuShellService.clearPackageCache(pkg, userId)` must enforce:
  1. `userId >= 0`.
  2. Package name matches `PKG_REGEX` (`^[a-zA-Z0-9_]+\.[a-zA-Z0-9_.]+$`).
- **Safety Boundaries & Denylist:**
  - Reject system packages: `android`, `com.android.systemui`, `com.android.settings`.
  - Reject telephony / messaging / dialer components.
  - Reject Google Mobile Services: `com.google.android.gms`, `com.android.vending`, `com.google.android.gsf`.
  - Reject Shizuku itself: `moe.shizuku.privileged.api`.
  - Reject Privacy Guard itself: `org.privacyguard.android`.
- **Probe Allowlist:**
  During M4-C probe validation, execution is hard-gated strictly to the three designated test packages:
  `com.ansangha.drdriving`, `com.ludo.king`, `com.rarlab.rar`.

### 4. Storage Measurement (`StorageStatsManager`)
- **Measurement Protocol:**
  Query `StorageStatsManager.queryStatsForPackage(UUID storageUuid, String packageName, UserHandle user)` immediately before and after cache deletion to record `getCacheBytes()`, `getDataBytes()`, and `getAppBytes()`. Delta in `getCacheBytes()` determines bytes freed.
- **Permission Evidence (Item 2 Hardware Verification):**
  - `StorageStatsManager.queryStatsForPackage` requires `android.permission.PACKAGE_USAGE_STATS`.
  - Raw device evidence from `dumpsys package com.android.shell` on Moto G54 Android 15 (`shell_pkg_dumpsys.txt`) proves `com.android.shell` (UID 2000) holds:
    - `android.permission.GET_PACKAGE_SIZE: granted=true` (lines 865, 1439)
    - `android.permission.PACKAGE_USAGE_STATS: granted=true` (lines 885, 1459)
    - `android.permission.DELETE_CACHE_FILES: granted=true` (lines 952, 1526)
    - `android.permission.CLEAR_APP_CACHE: granted=true` (lines 1033, 1607)
    - `android.permission.INTERACT_ACROSS_USERS: granted=true` (lines 971, 1545)
    - `android.permission.INTERACT_ACROSS_USERS_FULL: granted=true` (lines 876, 1450)
  - Because `ShizukuShellService` runs in UID 2000 (`com.android.shell`), it already holds `PACKAGE_USAGE_STATS` and cache management permissions without manual user prompts.
- **`UNPROVEN` Invariants:**
  - Whether `StorageStatsManager` reflects storage deltas immediately upon cache deletion or returns cached figures until system server reconciliation is `UNPROVEN` on hardware.

### 5. Fallback Mechanism (`pm trim-caches`)
- **Device-Wide Fallback:**
  If per-app cache deletion fails or is unsupported on specific OEM builds, fallback executes `pm trim-caches <desired_free_bytes>` (e.g. `200000000000`).
- **Scope & Warning Invariant:**
  `pm trim-caches` is strictly **DEVICE-WIDE**. It cannot target a single package.
  The UI must display an explicit warning:
  *"Device-wide cache trimming frees temporary files across all installed applications. Individual application cache selection is not supported in fallback mode."*

### 6. Probe Constraints & Safety Rules
- On-device probe testing restricted exclusively to `com.ansangha.drdriving`, `com.ludo.king`, and `com.rarlab.rar`.
- Every probe execution logged in `C:\Projects\_smoke_m4c\ledger.txt` with timestamp, exact command/API, exit code, matching undo, and dedicated raw output captures.
- **NEVER** run plain `pm clear <pkg>` without `--cache-only`.

