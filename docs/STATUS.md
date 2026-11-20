# Project Status

## Proven Capabilities
The following capabilities have been observed and verified via direct command executions or tests on hardware (Motorola Moto G54 5G, Android 15):
- **Chain 3 Networking Isolation:** Per-package networking isolation (`cmd connectivity set-chain3-enabled true` + `set-package-networking-enabled false <pkg>`) applies successfully and rolls back cleanly [measured].
- **Whole-Package Control:** Package-level disable (`pm disable-user --user 0 <pkg>`) and re-enable work under shell privilege [measured].
- **Runtime Permissions:** Runtime permission grant and revoke (`pm grant <pkg> <permission>`, `pm revoke <pkg> <permission>`) succeed under shell privilege [measured].
- **Standby Buckets:** App standby buckets `10` (active), `20` (working_set), `30` (frequent), `40` (rare), `45` (restricted), and `50` (never) can be set via `am set-standby-bucket <pkg>` [measured]. (Value `5` is rejected by the system server).
- **Reported AppOps Mode:** `cmd appops set --uid <uid> <OP> ignore` modifies the reported mode in system server query results (`cmd appops get`), and `allow` restores it to baseline [measured].
- **Safe Instrumentation Workflow:** The manual `androidTest` deployment flow (`assembleDebug`, `assembleDebugAndroidTest`, `adb install -r`, `adb install -r -t`, `am instrument`, `adb uninstall *.test`) executes instrumentation tests (`OK (2 tests)` in 0.024s) without wiping user data or invalidating Shizuku authorization [measured].

## Unproven Hypotheses & Inconclusive Probes
The following areas remain unverified or have returned inconclusive results:
- **Actual AppOps Enforcement:** Whether setting AppOps to `ignore` actually blocks operations (e.g., clipboard read, notifications, background execution) is completely unverified. Only the *reported mode* in `dumpsys appops` / `cmd appops get` was observed [unproven].
- **Per-App Cache Clearing:** Whether per-app cache clearing is viable under shell privilege is unproven [unproven].
- **`pm clear --cache-only` Behavior:** Two executions (`com.ansangha.drdriving` and `com.rarlab.rar`) failed to return within 10 seconds (exit code 124). The root cause of the timeout is NOT established [unproven].
- **Storage Reporting Freshness:** Whether `dumpsys diskstats` displays live real-time sizes or cached calculation values is untested (suspected cached) [unproven].

### M4-B Cache-Clear Probe Observations
- `pm help` lists the flag `--cache-only` under `clear [--user USER_ID] [--cache-only] PACKAGE` (`--cache-only: a flag which tells if we only need to clear cache data`) [measured].
- `pm trim-caches <huge size>` (`200000000000`) exited with code 0, and available free space on `/data` reported via `df /data` rose by approximately 10 GB (no raw output file saved; ran device-wide and was not logged in the pre-execution ledger) [measured].

## Machine Learning & AI Subsystems
- **LightGBM Static Engine:** Bundled Ed25519-signed model (`lgbm_model.bin` + `lgbm_model.bin.sig`) evaluated by a pure C++17 flat-array evaluator with a Java/Python parity test [verified-in-repo, not audited end to end].
  - **Model Accuracy:** State plainly: **no empirical accuracy figures exist** [unproven].
- **OSINT Verification:** `OllamaClient.kt` and `OsintClassifierWorker.kt` exist in source [verified-in-repo].
  - **Inference Location:** The LLM runs strictly on a local LAN Ollama server or a free cloud fallback, **never on the phone** [verified-in-repo].
  - **Verdict Logic:** Deterministic Kotlin code makes the final verdict, requiring at least two independent trusted sources to designate an app as `malicious` [verified-in-repo].

## Open Items
1. **Walkthrough Overclaims Correction:** Correct M4-B walkthrough text to match raw evidence (Item 6) [open-task].
2. **M4-B Ledger Backfill:** Backfill `C:\Projects\_smoke_m4b\ledger.txt` with entries for `pm trim-caches` and process cleanup `pkill` calls [open-task].
3. **M4-C Per-App Cache Probe:** Probe per-app cache clearing via hidden API `IPackageManager.deleteApplicationCacheFilesAsUser` invoked in-process from the Shizuku `UserService` with a concrete observer callback; measure storage delta using `StorageStatsManager` (not `dumpsys diskstats`); fallback to device-wide "free up to X GB" via `pm trim-caches` [planned-probe].
4. **Documentation Debt:** Section 4 of `docs/architecture.md` policy status enum omits `APPLIED_PARTIAL` and `SUPERSEDED` which exist in `PolicyOp.kt` [docs-debt].
5. **H1 Residue:** Explicit `allow` appops entries created during M3.5-H probes for `COARSE_LOCATION` / `RECORD_AUDIO` (`com.ludo.king`) and `POST_NOTIFICATION` (`com.rarlab.rar`) remain on-device; not reset by design to avoid mutating untracked state [known-residue].
6. **Edge Case N5:** A re-queued policy operation (after drift or package update) that succeeds via chain3 overwrites `observed`, losing earlier step-3 priors (candidate for M4) [known-defect].

## Backlog
- **Fast Space Optimisation (D7):** User-selected one-click cache cleaner without heavy background scanning [planned-feature].
- **Root Backend (D2):** Optional root provider behind an explicit user toggle for rooted test devices, never auto-detected [planned-feature].
- **androidTest Scenarios S1–S5:** Expand `androidTest` suite to cover isolation prior scenarios S1 through S5 detailed in `implementation_plan.md` lines 169–175 [planned-tests].
