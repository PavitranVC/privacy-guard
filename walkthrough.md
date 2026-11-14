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
- Native C++ files untouched.
