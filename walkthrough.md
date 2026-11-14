# Walkthrough: Milestone M3.5 — Remediate All 9 Open Audit Items from v5

## 1. Summary of Changes by Component

### 1. Dual-Stack VPN Leak Probe (`ShieldProbe.kt`)
- Replaced the single-URL leak probe with explicit dual-stack address family probes:
  - IPv4 trace URL: `https://1.1.1.1/cdn-cgi/trace`
  - IPv6 trace URL: `https://[2606:4700:4700::1111]/cdn-cgi/trace`
- Implemented `probeTraceIp(network: Network, urlString: String): String?` using `HttpsURLConnection` with 3000ms connect/read timeouts, `useCaches = false`, `Connection: close`, returning null on any `IOException`/`RuntimeException` and disconnecting in `finally`.
- Implemented dual-stack leak detection:
  - `val v4Leak = vpnIpv4 != null && physIpv4 != null && vpnIpv4 == physIpv4`
  - `val v6Leak = vpnIpv6 != null && physIpv6 != null && vpnIpv6 == physIpv6`
  - `val isLeaking = v4Leak || v6Leak`
- Wrapped `Settings.Secure.getInt(cr, "always_on_vpn_lockdown", 0)` in `runCatching` to safely return `false` on `SecurityException`.

### 2. Cancel Blank-Snapshot PENDING Ops During Rollback (`ShizukuManager.kt`)
- In `rollbackPackage(packageName: String)`:
  - When iterating over ops for `packageName`, if an op has `status == "PENDING"` and `(op.snapshot.isNullOrBlank() || op.snapshot == "{}")`, calls `db.policyOpDao().markSuperseded(op.id, op.gen)` before `continue` so queued ops cannot execute after rollback finishes.

### 3. Surface `ROLLED_BACK_UNSAFE` Lock & Add User Unlock Path (`PolicyOpDao.kt`, `PrivacyGuardViewModel.kt`)
- In `PolicyOpDao.kt`:
  - Added `unlockRolledBackUnsafe(pkg: String, opType: String, now: Long = System.currentTimeMillis()): Int` updating `status = 'SUPERSEDED'` and `errClass = 'UNLOCKED_BY_USER'`.
- In `PrivacyGuardViewModel.kt`:
  - Checked return value of `upsertPreservingSnapshotAndGen(...)`: if `-1L`, notifies the UI via `_statusMessage.value` that the operation is locked due to a prior crash rollback (`ROLLED_BACK_UNSAFE`).
  - Added `unlockAndRetryPolicyOp(pkg: String, opType: String)` to unlock the op in Room and immediately re-trigger user-initiated policy execution.

### 4. Strict Component-Only `pm` Command Syntax (`ShizukuShellService.kt` & `ShizukuShellServiceTest.kt`)
- In `ShizukuShellService.validateCommand`:
  - When `binary == "pm"` and `subcmd in setOf("enable", "disable", "disable-user", "default-state")`:
    - Enforces `cmd.size == 5 && cmd[2] == "--user" && cmd[3].isNotEmpty() && cmd[3].all { it.isDigit() } && COMPONENT_REGEX.matches(cmd[4])`.
    - Rejects whole-package disable commands (missing component slash) and missing `--user <id>` flags.
- In `ShizukuShellServiceTest.kt`:
  - Added unit test asserting `pm disable-user --user 0 com.example.app/com.example.app.MyReceiver` is allowed.
  - Added unit test asserting `pm disable-user --user 0 com.example.app` (no component slash) is rejected.
  - Added unit test asserting `pm disable com.example.app/com.example.MyReceiver` (missing `--user <id>`) is rejected.

### 5. Deduplicate `CrashWatchdogWorker` Parser & Test Production Code (`CrashWatchdogWorker.kt` & `CrashWatchdogParsingTest.kt`)
- In `CrashWatchdogWorker.kt`:
  - Extracted exit-info parsing into companion method: `internal fun hasCrashInWindow(dumpsysOutput: String, appliedAtMs: Long, windowMs: Long = 60_000L): Boolean`.
  - `doWork()` calls `hasCrashInWindow(dumpsysResult.stdout, appliedAt, 60_000L)`.
- In `CrashWatchdogParsingTest.kt`:
  - Deleted all duplicate regexes and parsers from the test file.
  - Invokes `CrashWatchdogWorker.hasCrashInWindow` directly against multi-block fixtures for `reason=4 (CRASH)`, `reason=5 (CRASH_NATIVE)`, `reason=6 (ANR)`, `reason=10 (USER_REQUESTED)`, out-of-window timestamps, numeric epoch timestamps, and `subreason=4` non-crash scenarios.

### 6. Clean Up OSINT Pins & Dead Decoys (`OsintClassifierWorker.kt`, `docs/architecture.md`)
- In `OsintClassifierWorker.kt`:
  - Deleted unused `DECOY_POPULAR_HASHES` constant.
  - Removed unverified F-Droid and Mozilla SHA-256 certificate pins, keeping only the verified Google Play Services pin.
  - Removed comments claiming `k=4` decoy queries are active.
- In `docs/architecture.md`:
  - Removed reference to "decoy popular hashes".

### 7. Fail-Safe When `CrashWatchdogWorker` Exhausts Retries While Shizuku Is Down (`CrashWatchdogWorker.kt`)
- In `CrashWatchdogWorker.doWork()`:
  - If Shizuku is not ready (or `dumpsys` fails) and `runAttemptCount >= 4`:
  - Re-reads op from `policyOpDao` and if still `APPLIED` or `APPLIED_PARTIAL`, calls `markDriftPending(freshOp.id, freshOp.gen)` before returning `Result.failure()` so `ReconciliationCoordinator` re-verifies upon reconnection.

### 8. Split `ShellOps.isSafeListed` into Core Protected vs. System Policy (`ShellOps.kt`)
- In `ShellOps.kt`:
  - Added `isCoreProtectedPackage(packageName: String): Boolean` protecting core system apps (OS, GMS, Play Store, SystemUI, InCallUI, Settings, Messaging, Dialer) and common Indian UPI/banking apps (`net.one97.paytm`, `com.phonepe.app`, `com.google.android.apps.nbu.paisa.user`, `in.org.npci.upiapp`, `in.amazon.mShop.android.shopping`, `com.whatsapp`, `com.csam.icici.bank.imobile`, `com.sbi.lotusintouch`, `com.axis.mobile`, `com.snapwork.hdfc`).
  - Added `isSystemApp(context: Context, pkg: String): Boolean`.
  - Refactored `isSafeListed` to invoke `isCoreProtectedPackage(pkg) || isSystemApp(context, pkg)`.

### 9. Re-queue Same-Version `FAILED_TRANSIENT` / `FAILED_PERMANENT` on Package Replaced (`PolicyOpDao.kt`, `ReconciliationCoordinator.kt`, `PackageChangeReceiver.kt`)
- In `PolicyOpDao.kt`:
  - Added `packageReplaced: Boolean = false` to `upsertPreservingSnapshotAndGen`.
  - Transitions `SUPERSEDED`, `FAILED_PERMANENT`, and `FAILED_TRANSIENT` back to `PENDING` when `userInitiated || packageReplaced` is true on the same version code (while preserving `-1L` lock on `ROLLED_BACK_UNSAFE`).
- In `ReconciliationCoordinator.kt`:
  - Added `handlePackageReplaced(context: Context, pkg: String)`.
  - In `processPending`, re-queues same-version failed ops via `upsertPreservingSnapshotAndGen(op, packageReplaced = true)`.
- In `PackageChangeReceiver.kt`:
  - Invokes `ReconciliationCoordinator.handlePackageReplaced(context, pkg)` upon receiving `ACTION_PACKAGE_REPLACED`.

---

## 2. Unit Test Results

### `testDebugUnitTest` Output
Command: `.\gradlew.bat testDebugUnitTest`
```text
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 12s
29 actionable tasks: 4 executed, 25 up-to-date
```

### Exact Pass/Fail Breakdown
| Test Suite | Total Tests | Passed | Failed | Errors | Skipped |
|---|---|---|---|---|---|
| `Ed25519VerifierTest` | 4 | 4 | 0 | 0 | 0 |
| `LightGbmParityTest` | 1 | 1 | 0 | 0 | 0 |
| `ShizukuShellServiceTest` | 12 | 12 | 0 | 0 | 0 |
| `CrashWatchdogParsingTest` | 8 | 8 | 0 | 0 | 0 |
| **Total** | **25** | **25** | **0** | **0** | **0** |

### Compiler Warnings Report
- Deprecation warning on API 34 ConnectivityManager API:
  `w: .../ShieldProbe.kt:80:42 'val allNetworks: (Array<Network!>..Array<out Network!>)' is deprecated. Deprecated in Java.`
  (This is an expected standard Android SDK deprecation note on `allNetworks`; no compiler errors).
- Zero other compiler warnings or errors.
- Schema version maintained at v3 with zero entity column modifications.

---

# Walkthrough: Milestone M3.5-A — State-Machine, Drift Loop, Rollback & Watchdog Remediation

## 1. Files Created/Modified

1. `android/app/src/main/java/org/privacyguard/android/shizuku/ShellOps.kt`
   - Added `effectiveTargets(context, packageName, rawTargets)` helper: parses comma-delimited component lists, queries `PackageManager` strictly for declared services and broadcast receivers using `MATCH_DISABLED_COMPONENTS` (so already-disabled components are returned during drift evaluation), excludes content providers and activities, guards accessibility and notification listener services, and filters out safe-listed components and dynamic protected components.
   - Refactored `createPreMutationSnapshot` and `disableManifestComponents` to use `effectiveTargets`.
2. `android/app/src/main/java/org/privacyguard/android/ui/PrivacyGuardViewModel.kt`
   - In `disableTrackerComponents`: removed content providers from candidate target gathering; invokes `ShellOps.effectiveTargets` and stops early with status message if no mutable services/receivers exist.
   - Evaluates `upsertPreservingSnapshotAndGen` return codes with distinct UI messages: `-1L` surfaces crash rollback lock (`ROLLED_BACK_UNSAFE`) or insert conflict, `0L` notifies operation is already active, queued, or user-rolled-back, `> 0L` queues op and drains runnable ops.
3. `android/app/src/main/java/org/privacyguard/android/trigger/ReconciliationCoordinator.kt`
   - In `isOpDrifted`: resolves comma-delimited targets via `ShellOps.effectiveTargets` and checks enabled state on individual component names (preventing false drift loops).
   - In `processPending`: removed periodic auto-re-queueing of `FAILED_TRANSIENT` and `FAILED_PERMANENT` ops during regular scans.
   - In `handlePackageReplaced`: queries live `PackageInfo` for updated `versionCode` and `lastUpdateTime`, filters ops strictly excluding `SUPERSEDED`, and passes `packageReplaced = true`.
4. `android/app/src/main/java/org/privacyguard/android/shizuku/ShizukuManager.kt`
   - In `rollbackPackage`: implemented method-aware `isolate_wifi` reversal matching `op.observed` with `startsWith`:
     - `"CHAIN3_PACKAGE_NETWORKING"` -> `cmd connectivity set-package-networking-enabled true <pkg>` (does NOT run `set-chain3-enabled false` which is a global toggle).
     - `"CHAIN9_BINDER_FIREWALL"` -> `cmd connectivity oem_deny_3 remove <uid>`.
     - `"APPOPS_STANDBY_RESTRICTED"` -> `cmd appops set --uid <uid> RUN_ANY_IN_BACKGROUND allow` and `am set-standby-bucket --user <u> <pkg> active`.
     - Blank observed -> runs all three, succeeding if appops and standby-bucket exit 0.
   - `isolate_wifi` ops do not attempt snapshot restoration; handles blank-snapshot component disable ops by superseding `PENDING`, `APPLYING`, or `FAILED_TRANSIENT` ops; validates `markSuperseded` returned rows (`rows == 1`).
5. `android/app/src/main/java/org/privacyguard/android/work/CrashWatchdogWorker.kt`
   - In `doWork`: if a process crash is detected within 60s and snapshot restore retries are exhausted (`runAttemptCount >= 4`), transitions op to `markRolledBackUnsafe` (never `markDriftPending`) to prevent infinite crash loops.
6. `android/app/src/main/java/org/privacyguard/android/data/dao/PolicyOpDao.kt`
   - Cleaned `unlockRolledBackUnsafe` signature and query (removed redundant `:now = :now`).
   - In `upsertPreservingSnapshotAndGen`: returns `-1L` on locked/conflict, `0L` on existing/active/user-rolled-back (`STATUS_SUPERSEDED` with `ROLLED_BACK_BY_USER`), and `> 0L` on newly queued or reset ops.
7. `android/app/src/main/java/org/privacyguard/android/ui/AppDetailScreen.kt`
   - Added `onUnlockAndRetry: (String, String) -> Unit` parameter.
   - Rendered `"Unlock & Retry"` button in the `hasUnsafeRollback` warning banner with a confirmation `AlertDialog` ("Override Crash Protection?").
8. `android/app/src/main/java/org/privacyguard/android/ui/MainActivity.kt`
   - Wired `onUnlockAndRetry = { pkg, op -> viewModel.unlockAndRetryPolicyOp(pkg, op) }` into `AppDetailScreen`.
9. `android/app/src/test/java/org/privacyguard/android/shizuku/ShizukuShellServiceTest.kt`
   - Added unit test validating that all rollback commands (chain3 package networking, chain9 uid removal, appops allow, standby-bucket active, pm default-state) pass Shizuku shell allowlist validation.

## 2. Manifest, Privileges & Attack Surface
- No new Android permissions requested.
- Exported components remain strictly zero except the launcher `MainActivity` and `ShizukuProvider` (`android.permission.INTERACT_ACROSS_USERS_FULL`).
- Shizuku shell commands strictly gated by prefix and subcommand allowlists in `ShizukuShellService`.

## 3. Outbound Network & Privacy Audit
- Outbound endpoints touched: None.
- Purely on-device static analysis, Room state machine, and Shizuku shell policy execution.

## 4. Release Build & On-Device Verification
- `./gradlew testDebugUnitTest`: **BUILD SUCCESSFUL** (26 tests passed across all suites).
- `./gradlew testReleaseUnitTest assembleRelease`: **BUILD SUCCESSFUL** (in 59s, R8 full mode, 16 KB page-aligned native libs).
- `zipalign -c -P 16 -v 4 app-release.apk`: **Verification succesful** (16 KB page alignment confirmed).
- Native C++ files untouched.

---

# Walkthrough: Milestone M3.5-B — UPI Protection, Debloat Gate, ShieldProbe Single-Pass, Lineage Certs, & Real Moto G54 Golden Fixtures

## 1. Files Created/Modified

1. `android/app/src/main/java/org/privacyguard/android/data/dao/PolicyOpDao.kt`
   - In `upsertPreservingSnapshotAndGen`: on `versionChanged`, if `userInitiated || packageReplaced`, resets ops in `STATUS_FAILED_PERMANENT` and `STATUS_FAILED_TRANSIENT` alongside superseded/applied/drift; changed final fallthrough to return `0L` (preventing false `> 0L` queue reporting on unmodified rows).
2. `android/app/src/main/java/org/privacyguard/android/ui/AppDetailScreen.kt`
   - In `AlertDialog` confirm button: finds all ops in `ops` with `STATUS_ROLLED_BACK_UNSAFE` and invokes `onUnlockAndRetry(app.pkg, it.op)` for each (falling back to `OP_DISABLE_COMPONENTS`).
3. `android/app/src/main/java/org/privacyguard/android/shizuku/ShellOps.kt`
   - Added `isDynamicUpiOrPaymentApp(context, pkg)`: dynamically queries `ACTION_VIEW` for `upi://pay` to protect any installed UPI application from mutations.
   - Added `isProtectedForDebloat(context, pkg)`: gates debloat actions while permitting OEM system bloat disabling by protecting core packages, UPI apps, home/launcher, SMS, dialer, WebView, and non-updated Android system packages.
   - Preserved `isSafeListed(context, pkg, componentName: String? = null)` signature and added `isDynamicUpiOrPaymentApp(context, pkg)` check.
4. `android/app/src/main/java/org/privacyguard/android/shield/ShieldProbe.kt`
   - Added `TunnelStatus.INCONCLUSIVE`.
   - Refactored dual-socket trace probing to single-pass `TraceResponse(val ip: String?, val warp: String?)` via `probeTrace(network, url)`, parsing `ip=` and `warp=` in a single HTTP request and eliminating redundant calls.
   - When VPN is active, only reports `TunnelStatus.HEALTHY` if a comparable physical network family probe exists and does not match; if physical probes are unavailable/timed out, reports `TunnelStatus.INCONCLUSIVE`.
5. `android/app/src/main/java/org/privacyguard/android/ui/DashboardCard.kt`
   - Handled `TunnelStatus.INCONCLUSIVE` displaying `"VPN Active (Leak Check Inconclusive)"` with amber warning color `Color(0xFFEF6C00)`.
6. `android/app/src/main/java/org/privacyguard/android/osint/OsintClassifierWorker.kt`
   - Added Google Play Services APK Signature Scheme v3 rotated leaf cert (`5f2391277b1dbd489000467e4c2fa6af802430080457dce2f618992e9dfb5402`) to `TRUSTED_PUBLISHER_CERTS` alongside original root signer.
   - Gate 1 checks comma-separated certificates against trusted publishers.
   - In Gate 3, if Ollama is unreachable (`verdict == null`), returns `Result.retry()` (when `runAttemptCount < 4`) or `Result.failure()`.
7. `android/app/src/main/java/org/privacyguard/android/scan/tier1a/Tier1AScanner.kt`
   - In `extractSigningCertSha256`, hashes BOTH `apkContentsSigners` and `signingCertificateHistory` when `hasMultipleSigners()` is false, deduplicating lowercase SHA-256 digests so rotated packages match lineage or leaf.
8. `android/app/src/main/java/org/privacyguard/android/shizuku/ShizukuShellService.kt`
   - Replaced `Char.isDigit()` with strict ASCII digit check `it in '0'..'9'`, rejecting Unicode digits.
9. `android/app/src/test/java/org/privacyguard/android/shizuku/ShizukuShellServiceTest.kt`
   - Added negative tests for whole-package pm commands, non-ASCII Unicode user IDs (`٠`), combined flags (`--user=0`), and extra trailing tokens.
10. `android/app/src/test/resources/fixtures/moto_g54_sdk35_exit_info.txt`
    - Real Moto G54 5G (Android 15 / SDK 35) `dumpsys activity exit-info` golden fixture.
11. `android/app/src/test/java/org/privacyguard/android/work/CrashWatchdogParsingTest.kt`
    - Added tests verifying `hasCrashInWindow` on the real Moto G54 fixture for reason=4 (CRASH at 18:16:12) vs reason=13 (OTHER at 07:48:20) and verifying subreason=4 on same line does not trigger crash detection.

## 2. Manifest, Privileges & Attack Surface
- No permissions added.
- Zero exported components beyond `MainActivity` and `ShizukuProvider`.
- Stricter Shizuku shell validation (strict ASCII digits, exact 5-token `pm` commands).

## 3. Outbound Network & Privacy Audit
- Outbound network calls strictly reduced: `ShieldProbe` dual-socket probe reduced from 3 HTTP requests down to 2 (single-pass trace).

## 4. Release Build & On-Device Verification
- `./gradlew testDebugUnitTest`: **BUILD SUCCESSFUL** (29 tests passed across all 4 suites).
- `./gradlew assembleRelease`: **BUILD SUCCESSFUL** in 1m 5s (R8 full mode).
- `zipalign -c -P 16 -v 4 app-release.apk`: **Verification succesful** (16 KB page alignment confirmed).
