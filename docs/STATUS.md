# Project Status

## Proven Capabilities
The following capabilities have been observed and verified via direct command executions or tests on hardware (Motorola Moto G54 5G, Android 15):
- **Chain 3 Networking Isolation:** Per-package networking isolation (`cmd connectivity set-chain3-enabled true` + `set-package-networking-enabled false <pkg>`) applies successfully and rolls back cleanly [measured].
- **Whole-Package Control:** Package-level disable (`pm disable-user --user 0 <pkg>`) and re-enable work under shell privilege [measured].
- **Runtime Permissions:** Runtime permission grant and revoke (`pm grant <pkg> <permission>`, `pm revoke <pkg> <permission>`) succeed under shell privilege [measured].
- **Standby Buckets:** App standby buckets `10` (active), `20` (working_set), `30` (frequent), `40` (rare), `45` (restricted), and `50` (never) can be set via `am set-standby-bucket <pkg>` [measured]. (Value `5` is rejected by the system server).
- **Reported AppOps Mode:** `cmd appops set --uid <uid> <OP> ignore` modifies the reported mode in system server query results (`cmd appops get`), and `allow` restores it to baseline [measured].
- **Safe Instrumentation Workflow:** The manual `androidTest` deployment flow (`assembleDebug`, `assembleDebugAndroidTest`, `adb install -r`, `adb install -r -t`, `am instrument`, `adb uninstall *.test`) executes instrumentation tests without wiping user data or invalidating Shizuku authorization [measured].
- **StorageStatsManager Query:** Main-process `StorageStatsManager.queryStatsForPackage` via declared `PACKAGE_USAGE_STATS` + `cmd appops set org.privacyguard.android GET_USAGE_STATS allow` accurately queries live per-package `appBytes`, `dataBytes`, and `cacheBytes` (`StorageStatsProbeTest`, `p5`, `p14`, `p23`, `p26`) [measured].
- **Shell Privilege Package Grants:** `com.android.shell` (UID 2000) holds granted permissions `android.permission.DELETE_CACHE_FILES`, `android.permission.ACCESS_CACHE_FILESYSTEM`, and `android.permission.PACKAGE_USAGE_STATS` (`dumpsys package com.android.shell`, `shell_pkg_dumpsys.txt`) [measured].
- **Hidden API Stub Inspection:** `javap` inspection confirms `deleteApplicationCacheFiles*` and `IPackageDataObserver` are absent from `dev.rikka.hidden:stub:4.4.0` [verified-in-repo].
- **Device-Wide Cache Trimming:** `pm trim-caches <targetBytes>` executed synchronously in 1.9s (exit code 0); measured device `/data` available space increase of +555,824 KB (~542.8 MB) from 82,115,532 KB to 82,671,356 KB (`p22` vs `p25`) with target package caches untouched (LRU eviction device-wide) [measured].

## Unproven Hypotheses & Inconclusive Probes
The following areas remain unverified or have returned inconclusive results:
- **Actual AppOps Enforcement:** Whether setting AppOps to `ignore` actually blocks operations (e.g., clipboard read, notifications, background execution) is completely unverified. Only the *reported mode* in `dumpsys appops` / `cmd appops get` was observed [unproven].
- **Per-App Cache Clearing via CLI:** `pm clear --cache-only --user 0 <pkg>` is non-viable on Android 15 / Moto G54; 4/4 timeouts across 2 packages (10s × 2 in M4-B on `com.ansangha.drdriving` and `com.rarlab.rar`; 120s on `com.ansangha.drdriving` in `p6`; 60s untouched control on `com.rarlab.rar` in `p15`), 0 bytes freed across all attempts (`cacheBytes` strictly unchanged). The root cause of the timeout remains unestablished [unproven].
- **Per-App Cache Trim Invariance:** In `pm trim-caches` probe (`p22`–`p26`), device `/data` free space rose by 555,824 KB (~542.8 MB), while per-app caches for recently probed test targets (`com.ansangha.drdriving`, `com.ludo.king`, `com.rarlab.rar`) showed 0 bytes delta due to LRU eviction behavior [measured].
- **Storage Reporting Freshness:** Whether `dumpsys diskstats` displays live real-time sizes or cached calculation values is untested (suspected cached) [unproven].

### M4-B Cache-Clear Probe Observations
- `pm help` lists the flag `--cache-only` under `clear [--user USER_ID] [--cache-only] PACKAGE` (`--cache-only: a flag which tells if we only need to clear cache data`) [measured].
- `pm trim-caches <huge size>` (`200000000000`) exited with code 0, and available free space on `/data` reported via `df /data` rose by approximately 10 GB (no raw output file saved; ran device-wide and was not logged in the pre-execution ledger) [measured].

## Machine Learning & AI Subsystems

### IMPLEMENTED [verified-in-repo]
- **Static Classifier (LightGBM):**
  - Signed model binary and signature assets (`lgbm_model.bin`, `lgbm_model.bin.sig`) are loaded and Ed25519-verified in `ScanService.kt` lines 33–46, initializing the C++ flat-array evaluator in `:scan` via `nativeInitEngine` (`ScanService.kt` line 48; `android/app/src/main/cpp/scanner_jni.cpp` lines 328–360).
  - Invocation pipeline: `ReconciliationCoordinator.kt` line 155 (`scanOrchestrator.scanPackageWithSplits`) calls `ScanOrchestrator.kt` line 107 (`service.scanApkFd(pfd)`), which enters `ScanService.kt` line 86 (`nativeScanApkFd(descriptor.fd)`) and invokes `engine->evaluator.evaluate(features)` in `android/app/src/main/cpp/scanner_jni.cpp` line 400.
  - `LightGbmParityTest.kt` lines 186–216 evaluates synthetic test vectors against a Kotlin reimplementation of the decision tree model (`KotlinModel.evaluate`), not against C++ JNI output or Python `predict_proba`.
  - **Model Accuracy:** State plainly: **no empirical accuracy figures exist** [unproven].
- **OSINT Verification (Local Ollama):**
  - `OllamaClient.kt` lines 35–62 sends package name, cert SHA-256, permissions, and trackers to `http://127.0.0.1:11434/api/generate` with model `llama3.2` and receives `{verdict, confidence, reasoning}`.
  - Note on endpoint: Code comments and logs describe this as "local LAN" (`OsintClassifierWorker.kt` lines 54, 67, 85), but the configured URL is loopback `http://127.0.0.1:11434` (`OllamaClient.kt` line 35).
  - `OsintClassifierWorker.kt` lines 76–77 formats the result as `"VERDICT:confidence"` and stores it directly into Room via `AppScanResultDao.updateOsintVerdict`.
  - Pre-query gating: Trusted-publisher cert shortcut (`OsintClassifierWorker.kt` line 41, with only `"com.google.android.gms"` pinned in `TRUSTED_PUBLISHER_CERTS` at lines 116–120) and a package-name sensitive-keyword denylist (`OsintClassifierWorker.kt` line 48, lines 103–106).

### PLANNED (per docs/architecture.md, not implemented)
- **Deterministic Two-Source Consensus:** Final verdict logic requiring at least two Tier-1/2 independent sources to declare an app `malicious` is not implemented in code [planned].
- **Cloud Fallback:** Free cloud fallback LLM for when the local Ollama instance is unreachable is not implemented in code [planned].
- **Public Report Evidence Extraction:** Extraction of strict JSON `(claim, source_url, evidence_type)` from stripped public web/report text is not implemented in code [planned].
- **Online Hash Lookups:** VirusTotal and MalwareBazaar hash check workers are not implemented in code [planned].

## Open Items
1. **Walkthrough Overclaims Correction:** DONE in commit a87d59b.
2. **M4-B Ledger Incomplete:** DONE (note appended to `_smoke_m4b/ledger.txt` regarding unlogged trim-caches and pkill).
3. **M4-C Cache-Clear Probe:** DONE (Phase 0, 0b, Stage 0, Stage 0b, and Item 1 completed; `pm clear --cache-only` non-viable on Android 15 / Moto G54 with 4/4 timeouts; `pm trim-caches` viable device-wide reclaiming 542.8 MB; `StorageStatsManager` viable for per-app stats).
4. **Documentation Debt:** DONE (Section 4 of `docs/architecture.md` synced with `PolicyOp.kt` status enum).
5. **H1 Residue:** Explicit `allow` appops entries created during M3.5-H probes for `COARSE_LOCATION` / `RECORD_AUDIO` (`com.ludo.king`) and `POST_NOTIFICATION` (`com.rarlab.rar`) remain on-device; not reset by design to avoid mutating untracked state [known-residue].
6. **Edge Case N5:** A re-queued policy operation (after drift or package update) that succeeds via chain3 overwrites `observed`, losing earlier step-3 priors (candidate for M4) [known-defect].

## Backlog
- **Fast Space Optimisation (D7):** User-selected one-click cache cleaner without heavy background scanning [planned-feature].
- **Root Backend (D2):** Optional root provider behind an explicit user toggle for rooted test devices, never auto-detected [planned-feature].
- **androidTest Scenarios S1–S5:** Expand `androidTest` suite to cover isolation prior scenarios S1 through S5 detailed in `implementation_plan.md` lines 169–175 [planned-tests].
