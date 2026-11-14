# Workspace Invariants: Privacy-First Android Security Scanner

@[Architecture Spec](docs/architecture.md)

## 1. Zero Personal Data & Anti-Fingerprinting Isolation
- NEVER run `adb` commands that pull installed app inventories, device IDs, Wi-Fi SSIDs, accounts, or personal logs into the workspace. Test strictly against synthetic/designated test packages.
- NEVER hardcode API keys, personal domains, or device fingerprints in source files or tests.
- Prefer Ed25519-signed offline snapshots (`Signature.getInstance("Ed25519")`, API 33+) for known-bad hashes, LightGBM weights, and Exodus tracker prefixes; never bulk-query 150+ installed hashes on first launch.
- Enforce a hard sensitive-category denylist (health, dating, religion, LGBTQ, finance, VPN/Tor, stalkerware detectors) plus user-marked exclusions that NEVER touch the network. Skip online lookups for system apps and trusted-publisher signing certs (Google, Mozilla, F-Droid).
- Online lookups run one at a time on UNMETERED networks with randomized multi-day jitter, decoy popular-hash requests, fixed generic `User-Agent`, zero cookies, and minimized payloads (`{pkg, versionCode, sha256, stripped_public_text}` only).
- Export NOTHING in `AndroidManifest.xml` except the launcher `MainActivity` and the `android.permission.INTERACT_ACROSS_USERS_FULL`-guarded `rikka.shizuku.ShizukuProvider` for Shizuku binder delivery. Use explicit intents only; expose zero external triggers for Shizuku actions.

## 2. Vibe-Coding & Android 15–16 Guardrails
- Always generate `implementation_plan.md` for approval before modifying `libs.versions.toml`, `build.gradle.kts`, `CMakeLists.txt`, or AIDL files.
- Centralize all Android dependency versions in `android/gradle/libs.versions.toml` with zero Google Play Services or Firebase SDKs. Use `kotlinx.serialization` (never Gson) and `dev.rikka.hidden:stub`/`compat` + `Refine` + `org.lsposed.hiddenapibypass` + Shizuku `UserService` (never hand-written hidden AIDLs or private `Shizuku.newProcess`).
- Gate every Shizuku call on `Shizuku.pingBinder()` + `Shizuku.checkSelfPermission()`, and execute exclusively on a single-thread `Dispatchers.IO.limitedParallelism(1)` executor in the main process.
- Process & Native rules: `Application.onCreate()` MUST check `Application.getProcessName()` so `:scan` never initializes Room, WorkManager, or Shizuku. Target `arm64-v8a` only, `useLegacyPackaging = false`, AGP 8.5.1+ / NDK r28+ with 16 KB page alignment (`zipalign -c -P 16 -v 4`), dynamic `sysconf(_SC_PAGESIZE)` in `mmap`, `inflateInit2(&s, -MAX_WBITS)` for raw ZIP deflate with output caps, and complete R8 `-keep` rules verified in `release` builds from Milestone 1 onward.

## 3. Mandatory Walkthrough Output (For External Audit)
After completing ANY task, write/update `walkthrough.md` AND print a concise summary in chat containing:
1. **Files Created/Modified** (exact paths + 1-line role).
2. **Manifest, Privileges & Attack Surface** (permissions, exported flags, Shizuku `UserService`/`cmd` calls).
3. **Outbound Network & Privacy Audit** (every URL/endpoint touched and exact payload fields, or "None").
4. **Release Build & On-Device Verification** (exact `./gradlew assembleRelease` / `zipalign -c -P 16 -v 4` / test output).