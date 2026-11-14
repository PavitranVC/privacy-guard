# Walkthrough: Final Release Remediation & Security Hardening (Post-M3 Principal Audit)

## 1. Files Created/Modified

### Shizuku Shell Sandbox & Lifecycle Hardening
- `android/app/src/main/java/org/privacyguard/android/shizuku/ShizukuShellService.kt`:
  - Added strict 3-token subcommand allowlist (`SUBCOMMANDS`) restricting `cmd connectivity`, `cmd appops`, `cmd package`, and `dumpsys activity`.
  - Enforced canonical binary execution path (`/system/bin/<binary>`).
  - Added bounded 2s pipe timeout on `stdoutFuture` and `stderrFuture` with `TimeoutException` cancellation to prevent hanging reader threads.
  - Guaranteed process cleanup via `finally { process.destroyForcibly() }`.
  - Hardened package regex (`^[a-zA-Z0-9_]+\.[a-zA-Z0-9_.]+$`) requiring at least one dot so command keywords cannot masquerade as packages.
  - Extracted pure `validateCommand(cmd)` in companion object for unit testability without Android `Binder` dependencies.
- `android/app/src/main/java/org/privacyguard/android/shizuku/ShizukuManager.kt`:
  - Configured `SupervisorJob() + CoroutineExceptionHandler` on `ShizukuManager` scope.
  - Added 2-second reconnect loop on `onServiceDisconnected`.
  - Added 10-second watchdog in `checkAndBindUserService` to reset `isBinding = false` on hung bind calls.
  - Enforced pre-mutation snapshot validation: verified non-empty snapshot and confirmed `savedRows == 1` before invoking component mutations.
  - Switched crash watchdog registration from `ExistingWorkPolicy.KEEP` to `ExistingWorkPolicy.REPLACE`.
  - Multi-status rollback filtering: processes eligible ops (`APPLIED`, `APPLIED_PARTIAL`, `DRIFT`, `APPLYING`, `FAILED_TRANSIENT`, `PENDING`) sorted by `id DESC`, with read-back verification matching `expectedState = if (state in 1..3) state else 0`.

### CrashWatchdogWorker & Room State Machine Hardening
- `android/app/src/main/java/org/privacyguard/android/work/CrashWatchdogWorker.kt`:
  - Fixed timestamp regex to capture full date-time (`timestamp=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})`), eliminating `substringBefore(" ")` date truncation that caused `ParseException`.
  - Added negative lookbehind regex (`(?<![A-Za-z])reason=([4-7])\b`) to eliminate false-positive collision with `subreason=4`.
  - Added numeric millisecond timestamp fallback (`timestamp=(\d{10,13})\b`).
  - Enforced retry cap at 5 attempts (`if (runAttemptCount >= 5) return Result.failure()`).
  - Re-queried fresh `PolicyOp.gen` from DB before calling `markRolledBackUnsafe`.
  - Rejected restoration from blank or `{}` snapshots.
- `android/app/src/main/java/org/privacyguard/android/data/dao/PolicyOpDao.kt`:
  - Hardened `saveSnapshot` SQL with `CASE WHEN snapshot IS NULL OR snapshot = '' OR snapshot = '{}' THEN :snapshot ELSE snapshot END`.
  - Added `@Query("SELECT COUNT(*) FROM policy_op WHERE pkg = :pkg AND op = :op AND status = 'ROLLED_BACK_UNSAFE'") suspend fun countLocked(...)`.
  - Enforced lock refusal in `upsertPreservingSnapshotAndGen`: returns `-1L` if package has an active `ROLLED_BACK_UNSAFE` record.
  - Added `userInitiated: Boolean = false` parameter: allows user to re-apply previously superseded or failed operations.
  - Scoped `markDriftPending` exclusively to `APPLIED`, `APPLIED_PARTIAL`, and `DRIFT` states.
  - Scoped `resetForNewVersion` with `AND status != 'ROLLED_BACK_UNSAFE'`.

### ShieldProbe, OSINT & UI Hardening
- `android/app/src/main/java/org/privacyguard/android/shield/ShieldProbe.kt`:
  - Guarded against offline false positive: verified `activeNetwork != null` before executing canary probe.
  - Hardened canary probe: verifies `cloudflare-dns.com` resolves before declaring `CANARY_TRACKER_HOST` sinkholed.
  - Physical underlying network discovery: queried `cm.allNetworks` for validated non-VPN network with internet capabilities (eliminating `@SystemApi` `getUnderlyingNetworks` dependency).
  - Dual-stack physical vs VPN egress leak detection.
  - Added `Connection: close` and `finally { conn?.disconnect() }` to prevent socket leaks.
- `android/app/src/main/java/org/privacyguard/android/osint/OsintClassifierWorker.kt`:
  - Removed 40-character SHA-1 fingerprint and insecure AOSP testkey from `TRUSTED_PUBLISHER_CERTS`.
  - Capped retry attempts at 5 and properly rethrew `CancellationException`.
- `android/app/src/main/java/org/privacyguard/android/work/ReconcileDrainWorker.kt`:
  - Capped retry attempts at 5 and rethrew `CancellationException`.
- `android/app/src/main/java/org/privacyguard/android/ui/PrivacyGuardViewModel.kt`:
  - Replaced synchronous main-thread `ShieldProbe.checkShield` initialization with `MutableStateFlow(UiState().shieldStatus)`, keeping all probe I/O on `Dispatchers.IO` via `reprobeShield()`.
  - Added `ShellOps.isSafeListed` guard on `restrictBackgroundNetworking`.
  - Used actual package `pkgInfo.longVersionCode` and `pkgInfo.lastUpdateTime` in policy op construction.
  - Passed `userInitiated = true` to `upsertPreservingSnapshotAndGen`.
- `android/app/src/main/java/org/privacyguard/android/ui/AppDetailScreen.kt`:
  - Added `enabled = !isProtected` to background networking restriction button.

### Build & Signing Hardening
- `android/app/build.gradle.kts`:
  - Removed configuration-time file generation and hardcoded `"privacyguard123"` fallback.
  - Read release credentials cleanly from `keystore/keystore.properties` or `PG_KEYSTORE_*` environment variables.
  - Gracefully fallback to `signingConfig = null` when credentials are absent, with `-PrequireReleaseSigning=true` enforcement in task graph.

### Unit Tests Created
- `android/app/src/test/java/org/privacyguard/android/shizuku/ShizukuShellServiceTest.kt`: Unit tests for command validation, subcommand whitelist, package regex, and component syntax.
- `android/app/src/test/java/org/privacyguard/android/work/CrashWatchdogParsingTest.kt`: Unit tests for `dumpsys` exit-info parsing, negative lookbehind reason regex, and date-time/numeric timestamp parsing.

---

## 2. Manifest, Privileges & Attack Surface

### Merged Manifest Audit
Inspection of the merged release manifest (`android/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml`) confirms:
- **`minSdkVersion`:** 34 (Android 14)
- **`targetSdkVersion`:** 35 (Android 15)
- **Exported Activities:** `org.privacyguard.android.ui.MainActivity` (`exported="true"` with `MAIN`/`LAUNCHER` intent filter).
- **Exported Providers:** `rikka.shizuku.ShizukuProvider` (`exported="true"`, strictly guarded by `android.permission.INTERACT_ACROSS_USERS_FULL`).
- **Exported Services:** `androidx.work.impl.background.systemjob.SystemJobService` (`exported="true"`, guarded by `android.permission.BIND_JOB_SERVICE` for platform JobScheduler).
- **Isolated Processes:** `org.privacyguard.android.scan.ScanService` (`exported="false"`, `android:process=":scan"`).
- **Attack Surface:** Exactly zero unauthorized or unprotected exported components.

### Shizuku Privilege Execution Boundary
- Bound `ShizukuShellService` runs strictly under Shell UID 2000.
- Allowlisted binary execution strictly prepends `/system/bin/<binary>`.
- Subcommand whitelist prevents bypasses (`cmd package uninstall` rejected; `cmd connectivity set-chain3-enabled` allowed).
- All component mutations pass through `isSafeListed` checks protecting GMS, Play Store, notification listeners, and critical system components.

---

## 3. Outbound Network & Privacy Audit

| Endpoint | Protocol | Payload Fields | Privacy Guardrails & Telemetry Controls |
|---|---|---|---|
| `https://cloudflare-dns.com/cdn-cgi/trace` | HTTPS | None; `Connection: close`; generic `User-Agent: PrivacyGuard/1.0`; zero cookies. | Active dual-socket probe only (VPN vs physical network) to detect egress leaks and verify Private DNS/WARP. |
| `app-measurement.com` | DNS Query | `InetAddress.getAllByName` lookup only. | Canary DNS sinkhole probe only; zero HTTP/TCP connections. Gated behind active network check and secondary resolver check. |
| `http://127.0.0.1:11434/api/generate` | HTTP | JSON: `{ "model": "llama3.2", "prompt": "<stripped app metadata>", "format": "json" }` | Local loopback Ollama LAN only.<br>- Gate 1: Pinned certs for Google, Mozilla, F-Droid skipped without network.<br>- Gate 2: Sensitive category denylist never touches network.<br>- Gate 3: Capped at 5 attempts; decoy benign hashes ($k=4$) for k-anonymity. |

Zero personal identifiers (device IDs, advertising IDs, account tokens, Wi-Fi SSIDs, or telemetry) ever leave the device.

---

## 4. Release Build & On-Device Verification

### 1. Release Unit Tests
Command: `.\gradlew.bat testReleaseUnitTest --stacktrace`
```text
> Task :app:testReleaseUnitTest
BUILD SUCCESSFUL in 28s
30 actionable tasks: 9 executed, 21 up-to-date
```
- Total test count: 15 passed, 0 failed.
- Tested: `Ed25519VerifierTest`, `LightGbmParityTest`, `ShizukuShellServiceTest`, `CrashWatchdogParsingTest`.

### 2. Release APK Build (R8 Full Mode & Resource Shrinking)
Command: `.\gradlew.bat assembleRelease --stacktrace`
```text
> Task :app:minifyReleaseWithR8
> Task :app:shrinkReleaseRes
> Task :app:packageRelease
> Task :app:assembleRelease
BUILD SUCCESSFUL in 1m 6s
58 actionable tasks: 11 executed, 47 up-to-date
```
Output artifact: `android/app/build/outputs/apk/release/app-release.apk`

### 3. 16 KB Page Alignment Verification (Android 15+ Invariant)
Command: `zipalign -c -P 16 -v 4 android/app/build/outputs/apk/release/app-release.apk`
```text
Verifying alignment of android/app/build/outputs/apk/release/app-release.apk (4)...
 2719744 lib/arm64-v8a/libandroidx.graphics.path.so (OK)
 2736128 lib/arm64-v8a/libprivacyguard_scan.so (OK)
...
Verification succesful
```
Offset check: `2736128 % 16384 = 0` (clean 16 KB alignment).

### 4. ELF Program Headers Alignment Verification
Command: `llvm-readobj -l android/app/build/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib/arm64-v8a/libprivacyguard_scan.so`
```text
ProgramHeaders [
  ProgramHeader {
    Type: PT_LOAD (0x1)
    Alignment: 16384
  }
  ProgramHeader {
    Type: PT_LOAD (0x1)
    Alignment: 16384
  }
  ProgramHeader {
    Type: PT_LOAD (0x1)
    Alignment: 16384
  }
  ProgramHeader {
    Type: PT_LOAD (0x1)
    Alignment: 16384
  }
]
```
All ELF `PT_LOAD` segments strictly enforce 16,384 bytes alignment.
