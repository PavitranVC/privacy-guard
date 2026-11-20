# Privacy Guard: Vision & Roadmap

## 1. Product Vision
Privacy Guard is an on-device, privacy-first Android security and management utility designed for modern Android devices. Its identity and direction are defined by explicit design invariants:

- **Non-Root (Shizuku) First:** Provide maximum privacy enforcement and security capabilities on stock, unrooted Android devices using Shizuku shell privilege before considering root mechanisms [owner-decision, DECISIONS.md:D1].
- **Pragmatic Non-Root Enforcement:** Focus non-root actions strictly on viable Android 15 shell primitives: (1) Chain 3 per-package networking isolation, (2) background hibernation (AppOps `RUN_ANY_IN_BACKGROUND ignore` + standby bucket `restricted`), (3) package-level disable (`pm disable-user`), and (4) runtime permission grant/revoke [measured, DECISIONS.md:D5].
- **Honest UI (Reported vs Enforced):** Never mislead the user. The interface must explicitly distinguish reported configuration state in the system server from verified runtime enforcement [owner-decision, DECISIONS.md:D4].
- **Zero Personal Data Leakage:** Perform analysis locally or against offline Ed25519-signed rules; never upload APKs, personal inventories, Wi-Fi SSIDs, or accounts [GEMINI.md:6-10].
- **Fast Utility (Space Optimisation):** Provide fast space optimisation via user-selected one-click cache clearing without heavy background scanning bottlenecks [owner-decision, DECISIONS.md:D7].

---

## 2. Current Scope

### What Is Working
- **Per-Package Network Isolation:** Complete network cutoff using kernel Chain 3 (`cmd connectivity set-chain3-enabled true` + `set-package-networking-enabled false <pkg>`) and clean rollback verified on hardware [measured, STATUS.md].
- **Package Management:** Package-level disable and re-enable (`pm disable-user --user 0 <pkg>`) under shell privilege [measured, STATUS.md].
- **Runtime Permissions:** Permission grant and revocation via shell (`pm grant`, `pm revoke`) [measured, STATUS.md].
- **Standby Bucket Control:** Adjustment across buckets 10 (active) to 50 (never), including bucket 45 (restricted) [measured, STATUS.md].
- **Pluggable Privilege Layer:** `PrivilegeBackend` abstraction with centralized command validation allowlist (`ShizukuShellService.validateCommand`) [verified-in-repo, DECISIONS.md:D3].
- **Safe Testing Infrastructure:** Manual `androidTest` deployment workflow (`am instrument`) avoiding destructive APK uninstalls [measured, DECISIONS.md:D6].
- **Static Scanning & In-Process Model:** Manifest component analysis and C++17 JNI LightGBM evaluator loaded in isolated `:scan` process [verified-in-repo, STATUS.md].

### What Is Partial
- **OSINT Verification:** `OllamaClient.kt` successfully sends structured payloads to a loopback Ollama instance (`http://127.0.0.1:11434`) and stores results directly in Room, with trusted-publisher bypass and sensitive keyword filtering; however, it lacks multi-source consensus, cloud fallback, and public text parsing [verified-in-repo, STATUS.md].
- **Network Isolation Fallback:** The isolation ladder falls back to background hibernation if Chain 3 fails, but chain 9 fallback remains unverified [verified-in-repo, architecture.md].

### What Is Not Yet Proven
- **Actual AppOps Runtime Enforcement:** Setting AppOps to `ignore` alters the reported mode in `cmd appops get`, but whether the Android 15 system server actively halts background execution or leaks operations is completely unproven on-device [unproven, STATUS.md].
- **Per-App Cache Clearing:** Whether per-app cache clearing is viable under shell privilege is unproven (`pm clear --cache-only` timed out on hardware; in-process hidden API probe planned) [unproven, STATUS.md].
- **Classifier Detection Accuracy:** No empirical accuracy figures exist for the static LightGBM model on real-world malware corpora [unproven, STATUS.md].

---

## 3. Planned Evolution (Later)

- **Root Edition:** Optional root backend provider behind an explicit user toggle for rooted test devices; never auto-detected [owner-decision, DECISIONS.md:D2].
- **Multi-Source OSINT Consensus:** Deterministic final verdict logic requiring at least two independent Tier-1/2 sources before designating an app as `malicious` [planned, architecture.md, STATUS.md].
- **Cloud Fallback LLM:** Free cloud LLM fallback when the local Ollama instance is unreachable [planned, architecture.md, STATUS.md].
- **Public Report Evidence Extraction:** Extraction of structured claims `(claim, source_url, evidence_type)` from stripped public reports [planned, architecture.md, STATUS.md].
- **Online Hash Verifications:** Hash-only queries to VirusTotal/MalwareBazaar across APK digests on unmetered connections [planned, architecture.md, STATUS.md].
- **Model Accuracy Benchmarking:** Rigorous empirical evaluation and calibration of the LightGBM static classifier against Python `predict_proba` baselines and real-world datasets [planned, STATUS.md].
- **One-Click Space Optimisation:** Production release of fast, user-selected application cache clearing [owner-decision, DECISIONS.md:D7].
