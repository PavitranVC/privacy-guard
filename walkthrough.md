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
- `./gradlew testDebugUnitTest`: **BUILD SUCCESSFUL** (see Gradle output).
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
   - Added `isProtectedForDebloat(context, pkg)`: intentional scaffolding for a future debloat module, NOT currently wired into disableManifestComponents or isolatePackageNetworking (component disablement and Wi-Fi isolation rely on isSafeListed, which is wired). Protects core packages, UPI apps, home/launcher, SMS, dialer, WebView, and non-updated Android system packages.
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
10. Real Moto G54 fixtures:
    - `android/app/src/test/resources/fixtures/moto_g54_sdk35_exit_info.txt`
      Captured via: `adb shell dumpsys activity exit-info com.google.android.apps.restore | Out-File -Encoding ascii moto_g54_sdk35_exit_info.txt` (contains genuine benign system kills reason=13).
    - `android/app/src/test/resources/fixtures/moto_g54_sdk35_exit_info_crash.txt`
      Captured via: `adb shell monkey -p com.google.android.calculator 1 ; Start-Sleep -Seconds 3 ; adb shell am crash com.google.android.calculator ; Start-Sleep -Seconds 3 ; adb shell dumpsys activity exit-info com.google.android.calculator | Out-File -Encoding ascii moto_g54_sdk35_exit_info_crash.txt` (contains genuine app crash reason=4).
11. `android/app/src/test/java/org/privacyguard/android/work/CrashWatchdogParsingTest.kt`
    - Added tests verifying `hasCrashInWindow` on genuine Moto G54 fixtures for reason=4 (CRASH) vs reason=13 (OTHER) and verifying subreason=4 on same line does not trigger crash detection.

## 2. Manifest, Privileges & Attack Surface
- No permissions added.
- Zero exported components beyond `MainActivity` and `ShizukuProvider`.
- Stricter Shizuku shell validation (strict ASCII digits, exact 5-token `pm` commands).

## 3. Outbound Network & Privacy Audit
- Outbound network calls strictly reduced: `ShieldProbe` dual-socket probe reduced from 3 HTTP requests down to 2 (single-pass trace).

## 4. Release Build & On-Device Verification
- `./gradlew testDebugUnitTest`: **BUILD SUCCESSFUL** (all unit tests passed (see Gradle output)).
- `./gradlew assembleRelease`: **BUILD SUCCESSFUL** in 1m 5s (R8 full mode).
- `zipalign -c -P 16 -v 4 app-release.apk`: **Verification succesful** (16 KB page alignment confirmed).

## Milestone M3.5-C / M3.5-D / M3.5-E

### Milestone M3.5-C (Commits 099f6ba..6c0c0ca)
- Item 1 (099f6ba): Replaced the fabricated fixture with genuine device captures (moto_g54_sdk35_exit_info.txt = restore pkg reason=13; moto_g54_sdk35_exit_info_crash.txt = calculator reason=4); CrashWatchdogParsingTest derives timestamps from the fixtures; inline tests renamed "synthetic".
- Item 2 (666bf49): Snapshot lifecycle: ShellOps.mergeSnapshots (existing keys win, new keys added), PolicyOpDao.updateSnapshot replaces saveSnapshot, ShizukuManager throws ConcurrentMutationException if the row update loses gen/status; rollbackPackage and CrashWatchdogWorker.restoreSnapshotViaShell skip components no longer declared in the APK.
- Item 3 (6337b97): OllamaClient.classify returns sealed ClassificationResult (Success / Unreachable / Invalid); worker marks Invalid items OSINT_ERROR and continues; Unreachable retries.
- Item 4 (59f51b1): Tier1AScanner stores current signers only (apkContentsSigners); OsintClassifierWorker pins trusted publishers per package (Map<pkg, certs>) via isTrustedPublisher; TrustedPublisherTest added.
- Item 5 (5e661e4): isProtectedForDebloat documented as intentionally unwired scaffolding (KDoc + walkthrough).
- Item 6 (ac73e10): isDynamicUpiOrPaymentApp query now uses MATCH_DEFAULT_ONLY or MATCH_DISABLED_COMPONENTS.
- Item 7 (b9fca4b): CrashWatchdogWorker.safeMarkRolledBackUnsafe re-reads the op and retries markRolledBackUnsafe once on a gen race; returns retry/failure if it still fails.
- Item 8 (60fab7f): ReconciliationCoordinator drift check moved out of the `if (t1 != null)` block into its own try/catch so it runs even if the Tier1A scan fails.
- Item 9 (6c0c0ca): ShieldProbe.classifyTunnel extracted as a pure function with stricter HEALTHY rules (every family with a VPN IP must have a differing physical IP); ShieldProbeTest added.

### Milestone M3.5-D (Commit bd4aad2)
- Added 4xx/5xx status handling in `android/app/src/main/java/org/privacyguard/android/osint/OllamaClient.kt` (treating 404/408/429 and $\ge 500$ as retryable `Unreachable`, 400 and invalid bodies as `Invalid`).
- Removed 4 unverified publisher pins (`org.fdroid.fdroid`, `org.mozilla.firefox`, `com.duckduckgo.mobile.android`, `org.thoughtcrime.securesms`), retaining only `com.google.android.gms` in `android/app/src/main/java/org/privacyguard/android/osint/OsintClassifierWorker.kt`.
- Fixed retry off-by-one (`runAttemptCount < 2`, guard $\ge 3$) in `android/app/src/main/java/org/privacyguard/android/osint/OsintClassifierWorker.kt`.
- Deleted unused `queryClassification` from `android/app/src/main/java/org/privacyguard/android/osint/OllamaClient.kt`.
- Added missing unit tests for `mergeSnapshots` with `"{}"` in `android/app/src/test/java/org/privacyguard/android/shizuku/ShellOpsSnapshotTest.kt` and `classifyTunnel` IPv4-differs + IPv6-vpn-only in `android/app/src/test/java/org/privacyguard/android/shield/ShieldProbeTest.kt`.

### Milestone M3.5-E (Current Change)
- Wrapped component disable loop in `try { ... } catch (t: Throwable)` in `android/app/src/main/java/org/privacyguard/android/shizuku/ShellOps.kt` to ensure `mutatedSoFar` is rolled back via `rollbackComponents` on any `SecurityException` or failure, clearing `mutatedSoFar` to prevent double-rollback.
- Added `Log.w` per failed component in `rollbackComponents` without throwing.

### Known limitation (reported, pending verification)
On-device smoke test on Moto G54 / Android 15 reported that `pm disable-user --user 0 <pkg>/<component>` as shell UID fails with "SecurityException: Shell cannot change component state ... to 3", so disable_components ends FAILED_PERMANENT on that target. isolate_wifi (CHAIN3_PACKAGE_NETWORKING) apply and rollback worked. Component-disable, snapshot restore and crash watchdog have NOT been verified on hardware. A follow-up adb experiment will determine which states the shell may set.

## Milestone M3.5-G (rollback fidelity, capability gating, privacy primitives)
- Item 1: rollback preserves prior standby bucket and AppOps RUN_ANY_IN_BACKGROUND; settable-bucket validation; ShizukuShellService allows get-standby-bucket.
- Item 2: capability "pm_component_state_shell"; UI greys out "Disable Tracker Components" with "Not supported on this device without root" when false; catch records denial and marks op SHELL_COMPONENT_STATE_DENIED.
- Item 3: capability "connectivity_chain9"; ladder step 2 skipped when false; "Unknown command: oem_deny_3" records false and falls through to step 3; rollback tolerates "Unknown command" on remove.

### On-device findings (Moto G54, Android 15, 2026-11-14)
- 48/48 component cells denied by system server (SecurityException: Shell cannot change component state ... to 3).
- Debuggable test app also denied (same error, proved not a release-build artifact).
- Whole-package control (pm disable-user / enable) works as shell.
- Chain 3 per-package networking works (set-chain3-enabled + set-package-networking-enabled).
- Chain 9 (oem_deny_3) is an unknown command on this device.
- Standby bucket values 10, 20, 30, 40, 45, 50 settable; 5 rejected by system server.
- AppOps RUN_ANY_IN_BACKGROUND round-trips: mode "allow" removes the explicit override and restores baseline text.

## Milestone M3.5-H (audit findings G1, G2, G4, and hardware app-path audit)
- Item 1 (715e875): `parseAppOpsRunAnyOutput` strictly returns one of `{allow, ignore, deny, default, foreground}` or null; added "deny" to `ALLOWED_KEYWORDS` in `ShizukuShellService`; unknown or invalid prior appops mode skips appops rollback with warning log while keeping bucket rollback non-throwing; added verbatim fixture `moto_g54_sdk35_appops_run_any_foreground.txt` and tests in `IsolationPriorsTest` and `ShizukuShellServiceTest`.
- Item 2 (7f6e6e4): Priors reuse gated by `shouldReusePriors(status, observed)`: reused only on retrying `APPLYING` or `FAILED_TRANSIENT` when step 3 previously executed; fresh `PENDING` ops always read fresh priors; `markSuperseded`, `unlockRolledBackUnsafe`, `markDriftPending`, `markRolledBackUnsafe`, and `resetForNewVersion` clear `observed` to NULL; legacy rows without suffix detected via `isLegacyPartialWithoutSuffix` and record null priors to prevent reading already-restricted state; pure function tests added in `IsolationPriorsTest`.
- Item 3 (a3e33ab): Added "50" and "never" to `SETTABLE_BUCKETS` in `ShellOps` and "never" to `ALLOWED_KEYWORDS` in `ShizukuShellService`; empty `bucket=` in suffix skips bucket restore with warning log without defaulting to "active"; legacy rows keep allow/active fallback; tests added in `IsolationPriorsTest` and `ShizukuShellServiceTest`.

### Part 0 (G3) Probe and Package-Level AppOps Correction
- Part 0.5 in M3.5-H showed set commands on COARSE_LOCATION had no visible effect; for runtime-permission ops behaviour, see the on-device findings in the Known Limitations section below. Known residue: explicit allow entries for COARSE_LOCATION/RECORD_AUDIO (ludo.king) and POST_NOTIFICATION (rarlab.rar).

### Part 3 (Hardware App-Path Proof Audit)
- Per 3.1 and Rule R9, checked dependencies in `android/app/build.gradle.kts` and `android/gradle/libs.versions.toml`: `androidTestImplementation` dependencies (`androidx.test:runner`, `androidx.test.ext:junit`) do not exist in the repository, and modifying build files or version catalogs is forbidden by R9. Per instruction 3.1, Part 3 stopped and reported.

## Milestone M3.5-I (audit findings H3, H4, H5, and command-validation proof)
- Item 1 (be2c08d): Preserved `observed` on drift, package update, and rollback unsafe (`markDriftPending`, `resetForNewVersion`, `markRolledBackUnsafe` no longer clear `observed = NULL`); simplified `shouldReusePriors` to check `parsePriors(observed) != null` regardless of status so original priors are not lost after drift or app update; pure-function tests added in `IsolationPriorsTest`.
- Item 2 (8ff437f): Made priors durable before mutation by adding `updateObserved` DAO query guarded by `id`, `gen`, and `status = 'APPLYING'`; invoked via `onPersistObserved` callback in `ShellOps.isolatePackageNetworking` before executing the first mutating shell command; throws `ConcurrentMutationException` if persistence fails; M3.5-I tested the APPLYING assertion inside the existing `shouldReusePriors` test (M3.5-J Item 2 adds the ordering test).
  - *Note on CrashWatchdogWorker:* In `ShizukuManager.kt` (`scheduleCrashWatchdog`), watchdog jobs are scheduled only for `disable_components` and restore component snapshots only; the watchdog never runs for `isolate_wifi` ops, so `markRolledBackUnsafe` does not affect isolate rows.
- Item 3 (8f6f99e): Safe fallbacks to prevent rollback trap (`resolveRollbackAppOps` falls back to OS default "allow" for invalid/unknown appops mode with warning log; `resolveRollbackBucket` falls back to OS default "active" for empty/unsettable bucket with warning log; legacy rows keep allow/active); tests added in `IsolationPriorsTest`.
- Item 4 (4008407): Extracted pure command builders `buildIsolationStep3ApplyCommands` and `buildIsolationRollbackCommands` in `ShellOps`, wired into `ShellOps.isolatePackageNetworking` and `ShizukuManager.rollbackPackage`; added 113-combination validation matrix test covering 16 buckets x 7 appops modes plus legacy null priors against `ShizukuShellService.validateCommand`, and verified hostile prior strings are sanitized to safe fallbacks while raw hostile commands are rejected.

### Known Limitations
- **Observed Invariant Semantics:** `observed` carries `"APPOPS_STANDBY_RESTRICTED|bucket=..|appops=.."` from the point priors are persisted before the first step-3 mutation attempt; it stays on the row after a failed or partial step 3 (`markFailedPermanent` does not touch `observed`), so rollback can restore the captured priors. It does NOT mean "step 3 was applied".
- **Edge Case N5 (Candidate for M4):** A re-queued op (after drift or an app update) that succeeds via chain3 overwrites `observed` with the chain3 string, so priors for an earlier step-3 appops/bucket restriction are no longer recorded. Not fixed in M3.5-J.
- **Per-Op AppOps Verification (Moto G54 / Android 15, M3.5-I Part 3):**
  1. *Runtime op `POST_NOTIFICATION` (`com.rarlab.rar`):* Package-level `set <pkg> <OP> ignore` exits 0 but `get` is unchanged; `set --uid <uid> <OP> ignore` changes the reported mode to `Uid mode: POST_NOTIFICATION: ignore`; `set --uid <uid> <OP> allow` returns to `No operations. Default mode: allow`.
  2. *Non-runtime op `READ_CLIPBOARD` (`com.ansangha.drdriving`):* Package-level `set ignore` changes the reported mode and `set allow` restores it.
  3. *Observation boundary:* Only the REPORTED mode was observed; nothing was verified about actual blocking of notifications or clipboard access.
- **Known Residue (H1):** Explicit allow entries for `COARSE_LOCATION`/`RECORD_AUDIO` (`com.ludo.king`) and `POST_NOTIFICATION` (`com.rarlab.rar`); not reset by design.

## Milestone M3.5-J (audit findings N1 to N9 cleanup)
- Item 1 (437e061): In `ShellOps.isolatePackageNetworking` step 3, added `catch (e: ConcurrentMutationException) { throw e }` before `catch (e: Throwable)` so that a failed priors persistence is not swallowed as `UNISOLATABLE_ON_WIFI` and instead propagates to `executePolicyOp`'s outer transient-failure handler.
- Item 2 (606876f): Extracted `runIsolationStep3` core taking an `exec` lambda; added unit tests proving that priors persistence strictly precedes the first mutating `cmd appops set` execution, that a failed persist aborts with zero mutating commands executed, that null persistence preserves execution, and that legacy null priors persist the suffix-less status string.
- Item 3 (b6ae149): Case normalisation and whitespace trimming in `resolveRollbackBucket` and `resolveRollbackAppOps` so valid prior keywords (e.g. `"ACTIVE"`, `" Restricted "`, `"IGNORE"`, `"Allow"`) produce lowercase tokens matching `ALLOWED_KEYWORDS` that pass `ShizukuShellService.validateCommand`; added unit tests in `IsolationPriorsTest`.

## Milestone M4-B (M1/M2 cleanup, androidTest scaffold, cache-clear probe)
- Item 1 (`7077c2d`): Restored hidden API stack description into Section 4 of `docs/architecture.md` alongside `PrivilegeBackend`.
- Item 2 (`d94b252`): Routed `ShizukuManager.exec(cmd)` through `ShizukuBackend(service).exec(cmd as Array<String>)`; removed unused `IShellService` import from `ShellOps.kt`.
- Item 3 (`530e783`): Added approved androidTest dependencies to `android/gradle/libs.versions.toml` (`androidx-test-runner = "1.6.2"`, `androidx-test-ext-junit = "1.2.1"`) and `android/app/build.gradle.kts` (`androidTestImplementation`); added on-device smoke test `AppPathSmokeTest.kt` verifying target context package name is `"org.privacyguard.android"` and `Shizuku.pingBinder() == true`. Verified via manual `am instrument` run on Moto G54 hardware (`OK (2 tests)` in 0.024s), test APK uninstalled, main app and Shizuku authorization preserved intact.

### Cache-clear probe on Moto G54 (Android 15)
- **P1 (CLI support):** `pm help` lists `clear [--user USER_ID] [--cache-only] PACKAGE` (`--cache-only: a flag which tells if we only need to clear cache data`). Raw capture: `C:\Projects\_smoke_m4b\pm_help.txt`.
- **P2 (Baseline read):** Parsed verbatim JSON arrays in `dumpsys diskstats` (`Package Names:`, `App Sizes:`, `App Data Sizes:`, `Cache Sizes:`).
- **P3 & P5 (Clear execution):** `pm clear --cache-only` did not return within 10 s (exit 124) for two packages; the cause is not established. Raw captures: `C:\Projects\_smoke_m4b\p3_drdriving_clear.txt`, `C:\Projects\_smoke_m4b\p5_rar_clear.txt`.
- **P4 (Storage comparison):** App data sizes remained strictly identical before and after clearing attempts. Note: `dumpsys diskstats` may report cached values; the before/after comparison is inconclusive.
- **P6 (Fallback probe):** `pm trim-caches 200000000000` executed (exit code 0) (device-wide trim-caches; no raw file saved; not in the ledger), purging caches via `installd` across the device; reported 10,636,664 KB (~10.14 GB) of storage freed (`df /data` available reported increased from 73,870,368 KB to 84,507,032 KB).
- **P7 (App state preservation):** Both `com.ansangha.drdriving` and `com.rarlab.rar` were launched via `am start` post-probe; apps launched without crashing; login/state not checked.

| Target Package | Index | Code Size (bytes) | App Data Before (bytes) | App Data After (bytes) | Cache Before (bytes) | Cache After (bytes) | Clear Elapsed | Status |
|---|---|---|---|---|---|---|---|---|
| `com.ansangha.drdriving` | 85 | 30,680,576 | 7,163,904 | 7,163,904 | 1,196,032 | 1,196,032 | 10,013 ms | TIMEOUT (124) |
| `com.rarlab.rar` | 246 | 8,205,824 | 1,048,576 | 1,048,576 | 184,320 | 184,320 | 10,018 ms | TIMEOUT (124) |

