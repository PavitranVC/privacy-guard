# Implementation Plan: Milestone 1B Audit Remediation & Milestone 2 (Scan-to-Policy, Shield Probe & Local LAN Ollama)

## 1. Overview & Architectural Objectives
This plan executes **Part 1 (Milestone 1A & 1B Audit Remediations)** and **Part 2 (Milestone 2: Scan-to-Policy Wiring, Private DNS Shield Probe & Privacy-Preserving OSINT with Local LAN Ollama)** in strict conformance with `GEMINI.md`, `AUDIT_RULES.md`, and `docs/architecture.md`.

---

## 2. Proposed Changes & Component Architecture

### Part 1: Audit Remediations

#### 1. Build, Keystore & Proguard Hardening
- **`android/app/build.gradle.kts`**:
  - Remove hardcoded `"privacyguard123"` password. Read signing password from `keystore.properties` (or `KEYSTORE_PASSWORD` environment variable). If `keystore/release.jks` or `keystore.properties` is missing, auto-generate `keystore.properties` using a cryptographically secure 32-character password generated via `java.security.SecureRandom`.
  - Add C++ compiler flags: `-D_FORTIFY_SOURCE=2`, `-Wformat`, `-Werror=format-security`.
  - Add linker flag: `-Wl,--no-undefined`.
  - Add unit test dependency `testImplementation(libs.junit)`.
- **`android/gradle/libs.versions.toml`**:
  - Add `junit = "4.13.2"` under `[versions]` and `junit = { module = "junit:junit", version.ref = "junit" }` under `[libraries]`.
- **`android/app/src/main/cpp/CMakeLists.txt`**:
  - Add `-Wl,--no-undefined` to linker flags.
- **`android/app/proguard-rules.pro`**:
  - Narrow `ScanService` keep rule to:
    ```proguard
    -keepclassmembers class org.privacyguard.android.scan.ScanService {
        native <methods>;
    }
    ```
- **`android/app/src/main/java/org/privacyguard/android/PrivacyGuardApp.kt`**:
  - Replace blanket exemptions `""` with scoped exemptions:
    `HiddenApiBypass.addHiddenApiExemptions("Landroid/net/IConnectivityManager", "Landroid/content/pm/")`.

#### 2. Room State Machine, Concurrency & Shell Hardening
- **`AppDatabase.kt`**:
  - In `MIGRATION_1_2`, execute deduplication before index creation:
    `DELETE FROM policy_op WHERE id NOT IN (SELECT MIN(id) FROM policy_op GROUP BY pkg, op, target);`
- **`PolicyOpDao.kt` & `PolicyOp.kt`**:
  - Add `STATUS_APPLIED_PARTIAL = "APPLIED_PARTIAL"`.
  - Update `claimOp`:
    ```sql
    UPDATE policy_op
    SET status = 'APPLYING', gen = gen + 1, attempts = attempts + 1, nextAt = :leaseUntilMs
    WHERE id = :id AND gen = :expectedGen
      AND (status IN ('PENDING', 'FAILED_TRANSIENT') OR (status = 'APPLYING' AND nextAt <= :now))
      AND attempts < 5
    ```
  - Implement `saveSnapshot`: `UPDATE policy_op SET snapshot = COALESCE(snapshot, :snapshot) WHERE id = :id AND gen = :activeGen AND status = 'APPLYING'`.
  - Implement `upsertPreservingSnapshotAndGen`: if existing row has status in `('SUPERSEDED', 'APPLIED', 'APPLIED_PARTIAL', 'DRIFT')` and `existing.versionCode != newVersionCode`, reset `versionCode = :v, lastUpdateTime = :t, status = 'PENDING', attempts = 0, nextAt = 0, gen = gen + 1`. Never reset `ROLLED_BACK_UNSAFE`.
- **`ReconcileRequest.kt`, `ReconcileRequestDao.kt` & `ReconciliationCoordinator.kt`**:
  - Add monotonic `seq: Long` column to `reconcile_request`; bump on upsert; delete via `DELETE FROM reconcile_request WHERE pkg = :pkg AND seq <= :seenSeq`.
  - Limit mutex strictly to `triggerChangedPackagesCheck` (not across `processPending`). Use `ExistingWorkPolicy.APPEND_OR_REPLACE` for `reconcile_drain`.
  - If `Settings.Global.BOOT_COUNT` returns `-1`, fall back to `System.currentTimeMillis() - SystemClock.elapsedRealtime()` comparison against stored `SyncState`.
  - In `ReconciliationCoordinator.processPending`: run `Tier1AScanner.scanInstalledApp(pkg)` + `ScanOrchestrator.scanPackage(pkg)` across base and code splits, write to `app_scan_result`, verify live state of `APPLIED` ops, transition drifted ops to `PENDING`, and trigger `ShizukuManager.drainRunnableOps()`.
- **`ShizukuShellService.kt`, `ShellOps.kt` & `ShizukuManager.kt`**:
  - Stream reader: `Executors.newCachedThreadPool()` draining up to EOF while capping stored output at 256 KB.
  - Command validation: allowlist pairs (`cmd connectivity`, `cmd appops`, `cmd package`, `pm disable-user`, `pm default-state`, `pm enable`, `pm disable`, `am kill`, `am set-standby-bucket`, `dumpsys activity`); validate package args with `^[a-zA-Z0-9_.]+$` and component args with `^[a-zA-Z0-9_.$]+/[a-zA-Z0-9_.$]+$`.
  - `Capability`: composite primary key `@Entity(primaryKeys = ["op", "buildFingerprint"])`.
  - Probing: check `CapabilityDao` before running Chain 3 or `IConnectivityManager.setFirewallChainEnabled(9, true)`.
  - `ShizukuManager`: rethrow `CancellationException`, wrap DB state updates in `withContext(NonCancellable)`, move `check(rows == 1)` outside shell execution block.
- **`CrashWatchdogWorker.kt`**:
  - Route execution through `ShizukuManager.exec(cmd)` on `ioSingle`.
  - Parse `dumpsys activity exit-info <pkg>` by `ApplicationExitInfo #` blocks; parse timestamp with `SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)`; match `reason=(4|5|6|7)\b` within `appliedAt..(appliedAt + 60_000L)`.
  - Restore each component in snapshot to its recorded integer state (`0 -> default-state`, `1 -> enable`, `2 -> disable`, `3 -> disable-user`), verify read-back, and mark `ROLLED_BACK_UNSAFE` only if all succeed.

#### 3. C++17 Engine, Ed25519 & Isolated `:scan` Process
- **`apk_mmap.hpp`**:
  - Open FD/file with `O_RDONLY | O_NONBLOCK | O_CLOEXEC` and check `S_ISREG(st.st_mode)`.
  - Support `ApkArchive(int fd)`.
  - Validate EOCD comment length: `uint16_t cl; memcpy(&cl, raw + i + 20, 2); if (i + 22 + cl != fileSize_) continue;`.
  - Bound CD iteration with `if (currOffset - cdOffset >= cdSize) break;`.
  - For `STORED` (`method == 0`), require `compressedSize == uncompressedSize` and provide zero-copy `EntryView { const uint8_t* data; size_t size; }`.
  - For `DEFLATE` (`method == 8`), require `ret == Z_STREAM_END && strm.total_out == entry.uncompressedSize`.
- **`aho_corasick.hpp`**:
  - Flat vector of nodes with fixed `uint32_t next[256]` transition array.
  - Automatically insert both dotted form (`com.facebook.appevents`) and slash form (`com/facebook/appevents/`).
- **`lgbm_evaluator.hpp` & `assets/lgbm_model.bin`**:
  - Generate a real, valid binary `lgbm_model.bin` (magic `'L','G','B','M'`, `num_features = 16`, 4 classes, real decision trees, calibrated piecewise linear isotonic curves) and sign with pinned Ed25519 key to produce `lgbm_model.bin.sig`.
  - In `loadModel`: check magic, bounds check, loop safety, return class probabilities and `maxConfidence`.
  - Correct fallback heuristic indices to `features[3]` (entropy), `features[4]` (dynamic loading), `features[5]` (native code), `features[7]` (tracker count).
- **`scanner_jni.cpp`**:
  - Hold immutable `Engine` in `std::shared_ptr<const Engine>` under `std::mutex`.
  - Accept `nativeScanApkFd(JNIEnv*, jobject, jint fd)`.
  - Match DEX `^classes[0-9]*\.dex$`, validate DEX header (`header_size == 0x70`, `file_size == view.size`, `class_defs_size * 32 <= view.size`), parse DEX `string_data`, compute obfuscated short-name class ratio (`features[2]`), and compute Shannon entropy across 16 evenly spaced 4 KB windows (`features[3]`).
  - Prioritize `libflutter.so`, `libhermes.so`, and `libil2cpp.so` framework checks.
  - Return `scanStatus` (0=OK, 1=PARSE_FAILED, 2=QUARANTINED, 3=INIT_FAILED) and `maxConfidence` in `NativeScanResult`.
- **`IScanService.aidl` & `NativeScanResult.aidl` / `NativeScanResult.kt`**:
  - `IScanService.aidl`:
    ```aidl
    interface IScanService {
        NativeScanResult scanApkFd(in ParcelFileDescriptor pfd);
    }
    ```
  - `NativeScanResult`: add `scanStatus: Int` and `maxConfidence: Double`.
- **`ScanService.kt`, `ScanOrchestrator.kt`, `Tier1AScanner.kt`**:
  - `:scan` reads and cryptographically verifies its own assets (`assets/trackers_ac.bin` and `assets/lgbm_model.bin`) via `Ed25519Verifier` before engine init.
  - Pass `ParcelFileDescriptor` to `:scan`.
  - `ScanOrchestrator`: single-flight `Mutex`, `CompletableDeferred` + `withTimeout(5_000L)` binding, 30s call timeout (quarantine trigger on timeout), 30s idle unbind, merge across code splits (`splitSourceDirs.filterNot { it.contains("config.") }`).
  - `Tier1AScanner`: `PackageInfoFlags.of(flags.toLong() or PackageManager.MATCH_DISABLED_COMPONENTS.toLong())`, per-component fallback on `TransactionTooLargeException`, inspect `ServiceInfo.permission` for `BIND_ACCESSIBILITY_SERVICE` / `BIND_NOTIFICATION_LISTENER_SERVICE`, match tracker prefixes with `name == sig || name.startsWith("$sig.")`, hash all sorted `apkContentsSigners`.

---

### Part 2: Milestone 2 Implementation

#### 1. App Scan Result Room Entity & Migration 2->3
- **`android/app/src/main/java/org/privacyguard/android/data/entity/AppScanResult.kt`**:
  - Primary key: `pkg: String`
  - Columns: `versionCode: Long`, `certSha256: String`, `apkSha256: String`, `scanStatus: Int`, `riskScore: Double`, `maxConfidence: Double`, `matchedTrackersJson: String`, `dangerousPermissionsJson: String`, `detectedFramework: String`, `osintVerdict: String`, `scannedAt: Long`.
- **`AppScanResultDao.kt`**:
  - Upsert scan result, query by package, query high risk (`riskScore >= 0.70`), query low confidence (`maxConfidence < 0.90`).
- **`AppDatabase.kt`**:
  - Increment database version to 3 with `MIGRATION_2_3` (creating `app_scan_result`, recreating `capability` with composite PK `(op, buildFingerprint)`, and adding `seq` to `reconcile_request`). Export `schemas/3.json`.

#### 2. Private DNS & Dual-Socket Shield Telemetry (No `VpnService`)
- **`android/app/src/main/java/org/privacyguard/android/shield/ShieldProbe.kt`**:
  - Strictly zero `VpnService` inheritance or declarations.
  - Passive check: `ConnectivityManager.getLinkProperties(activeNetwork)?.isPrivateDnsActive` and `privateDnsServerName`.
  - Active canary checks:
    1. Resolve tracker canary domain (`app-measurement.com` or `graph.facebook.com`) via `InetAddress.getAllByName`. Sinkhole verified if resolving to `0.0.0.0`, `127.0.0.1`, or `NXDOMAIN`.
    2. Query `https://1.1.1.1/cdn-cgi/trace` with 5s timeout, extracting `dns=` and `warp=`.
  - Structured result: `ShieldStatus(isPrivateDnsActive, privateDnsServer, isSinkholeVerified, dohWarpStatus)`.

#### 3. Privacy-Preserving OSINT Worker & Local LAN Ollama
- **`android/app/src/main/java/org/privacyguard/android/osint/OllamaClient.kt`**:
  - Communicates with local loopback / LAN endpoint (`http://127.0.0.1:11434/api/generate`) via `HttpURLConnection` with strict 10s connect/read timeouts.
  - Zero cloud dependencies or telemetry; defaults to offline/disabled if unconfigured.
  - Uses `kotlinx.serialization` with strict JSON schema: `(verdict: String, confidence: Double, reasoning: String)`.
- **`android/app/src/main/java/org/privacyguard/android/osint/OsintClassifierWorker.kt`**:
  - WorkManager `CoroutineWorker` scheduled with `NetworkType.UNMETERED` and `setRequiresBatteryNotLow(true)`.
  - Filter: runs only when `maxConfidence < 0.90` and `osintVerdict == "PENDING"`.
  - **Gate 1 (Trusted Publisher Skip):** Checks `certSha256` against pinned list (Google, Mozilla, F-Droid, AOSP); marks `CLEAN_TRUSTED_PUBLISHER` without network request.
  - **Gate 2 (Sensitive Category Denylist):** Checks `ApplicationInfo.CATEGORY_*` and sensitive denylist (banking, health, password manager, VPN); marks `EXCLUDED_SENSITIVE_CATEGORY` without network request.
  - **Gate 3 (k-Anonymity & Decoy Hashes):** Shuffles real `apkSha256` with 4 deterministic popular decoy hashes (`k = 4`), rate-limited via token bucket.
  - Executes LAN Ollama inference on anonymized package features, updates `AppScanResult.osintVerdict`, and records decision in Room.

---

## 3. Verification & Validation Protocol
1. **Unit Testing:**
   - Create `android/app/src/test/java/org/privacyguard/android/crypto/Ed25519VerifierTest.kt`: verify Ed25519 asset signature validation and tamper rejection.
   - Create `android/app/src/test/java/org/privacyguard/android/scan/LightGbmParityTest.kt`: verify flat-array evaluation logic and isotonic calibration behavior.
2. **Build & Release Verification:**
   - Run `.\gradlew.bat testReleaseUnitTest assembleRelease --stacktrace` (must succeed with 0 errors).
3. **Audit Verification:**
   - Merged manifest: confirm **only** `MainActivity` and `rikka.shizuku.ShizukuProvider` are exported.
   - 16 KB APK alignment: verify with `zipalign -c -P 16 -v 4`.
   - 16 KB ELF segment alignment: verify with `llvm-readobj -l` (`Alignment: 16384` on all `PT_LOAD` segments).
4. **Deliverable:**
   - Update `walkthrough.md` with complete audit report.
