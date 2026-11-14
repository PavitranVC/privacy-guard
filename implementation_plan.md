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
