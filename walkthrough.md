# Walkthrough: Milestone 1A/1B Audit Remediation & Milestone 2 (Scan-to-Policy, Shield Probe & OSINT Worker)

## 1. Files Created/Modified

### Build, Gradle, Hardening & Security Config
- `android/gradle/libs.versions.toml`: Centralized versions for Android Gradle Plugin 8.5.1+, Kotlin 2.0.20+, Room 2.6.1, WorkManager 2.9.1, Shizuku 13.1.5, JUnit 4.13.2, and hidden API dependencies.
- `android/app/build.gradle.kts`:
  - Enforced `minSdk = 34` (Android 14–16 exclusively, no legacy branches).
  - Secure random local release keystore password generation (`keystore.properties` / `release.jks`, gitignored).
  - CMake C++ hardening flags: `-D_FORTIFY_SOURCE=2 -Wformat -Werror=format-security` and linker flags `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--no-undefined`.
  - Proguard/R8 optimization with `isMinifyEnabled = true` and `isShrinkResources = true`.
- `android/app/proguard-rules.pro`:
  - Narrowed `ScanService` keep rule to `native <methods>;`.
  - Added `-assumenosideeffects` stripping all `Log.v/d/i/w` in release.
  - Kept `NativeScanResult` with all fields and `<init>`.
  - Kept `@Serializable` models and serializers in `org.privacyguard.android.osint.**`.
- `android/app/src/main/res/xml/network_security_config.xml`: Network security config explicitly forbidding cleartext globally while permitting cleartext exclusively to loopback (`127.0.0.1`, `localhost`) for local LAN Ollama inference.
- `android/app/src/main/AndroidManifest.xml`:
  - Declared `QUERY_ALL_PACKAGES`, `moe.shizuku.manager.permission.API_V23`, `INTERNET`, and `ACCESS_NETWORK_STATE`.
  - Exported strictly the launcher `MainActivity` and `rikka.shizuku.ShizukuProvider` (guarded by `android.permission.INTERACT_ACROSS_USERS_FULL`). Zero other exported components.
  - Declared `ScanService` under isolated secondary process `android:process=":scan"`.
- `android/app/src/main/java/org/privacyguard/android/PrivacyGuardApp.kt`:
  - Guarded `onCreate()` with `Application.getProcessName() != packageName` so `:scan` and secondary processes never initialize Room, WorkManager, or Shizuku.
  - Scoped `HiddenApiBypass.addHiddenApiExemptions("Landroid/net/IConnectivityManager", "Landroid/content/pm/")` instead of blanket exemptions.

### C++17 JNI Engine & Cryptographic Assets
- `android/app/src/main/cpp/CMakeLists.txt`: C++17 build targeting `privacyguard_scan` with `-Wl,--no-undefined` and 16 KB page alignment.
- `android/app/src/main/cpp/apk_mmap.hpp`: FD-based `ApkArchive(int fd)` with `ownsFd_ = false` to prevent Android 14+ `fdsan` double-close SIGABRT on Binder `ParcelFileDescriptor`s; Central Directory iteration bounds check; zero-copy `EntryView` for STORED entries; raw DEFLATE decompression via `inflateInit2(&strm, -MAX_WBITS)`.
- `android/app/src/main/cpp/aho_corasick.hpp`: Flat vector of nodes with fixed `uint32_t next[256]` transitions, simultaneously indexing dotted (`com.facebook.appevents`) and slash (`com/facebook/appevents/`) forms.
- `android/app/src/main/cpp/lgbm_evaluator.hpp`: Endianness-safe binary `loadModel`, packed structs, loop bounds checks, softmax, and piecewise-linear isotonic calibration returning `riskScore` and `maxConfidence`.
- `android/app/src/main/cpp/scanner_jni.cpp`: Thread-safe JNI bridge with `std::shared_ptr<const Engine>` under `std::mutex`, FD-based `nativeScanApkFd`, DEX regex `^classes[0-9]*\.dex$`, header bounds validation, short-name class ratio, 16-window entropy, and framework detection.
- `android/app/src/main/aidl/org/privacyguard/android/scan/IScanService.aidl`: IPC interface updated to `NativeScanResult scanApkFd(in ParcelFileDescriptor pfd)`.
- `android/app/src/main/java/org/privacyguard/android/scan/NativeScanResult.kt`: Parcelable encapsulating scan status, feature vector, risk score, max confidence, matched trackers, entropy, dynamic loading flag, and framework type.
- `android/app/src/main/java/org/privacyguard/android/crypto/Ed25519Verifier.kt`: Deterministic `Signature.getInstance("Ed25519")` validator using `java.util.Base64` (API 34+ compatible) against pinned SPKI public key (`MCowBQYDK2VwAyEAgCYpThvpyHUz6FbXD+eFlCHG818ajDs/UOcGH6HV6ew=`).
- `android/app/src/main/assets/trackers_ac.bin` & `.sig`: Deterministically signed tracker signatures.
- `android/app/src/main/assets/lgbm_model.bin` & `.sig`: Deterministically signed 16-feature 4-class LightGBM model with isotonic curves.

### Room State Machine & Shizuku Service Engine
- `android/app/src/main/java/org/privacyguard/android/data/entity/AppScanResult.kt` & `AppScanResultDao.kt`: Entity and DAO for caching static and ML triage results, detected frameworks, and OSINT verdicts.
- `android/app/src/main/java/org/privacyguard/android/data/entity/Capability.kt` & `CapabilityDao.kt`: Composite primary key `(op, buildFingerprint)`.
- `android/app/src/main/java/org/privacyguard/android/data/entity/ReconcileRequest.kt` & `ReconcileRequestDao.kt`: Monotonic `seq: Long` column, `upsertRequestWithSeq`, and `deleteProcessed(pkg, seenSeq)`.
- `android/app/src/main/java/org/privacyguard/android/data/entity/PolicyOp.kt`: Added `STATUS_APPLIED_PARTIAL`.
- `android/app/src/main/java/org/privacyguard/android/data/dao/PolicyOpDao.kt`: Atomic `claimOp` with 60s lease timeout and retry bounds, `saveSnapshot`, `markAppliedPartial`, `resetForNewVersion`, and `upsertPreservingSnapshotAndGen`.
- `android/app/src/main/java/org/privacyguard/android/data/AppDatabase.kt`: Bumped schema version to 3; non-destructive `MIGRATION_1_2` (duplicate cleanup + unique index) and `MIGRATION_2_3` (`app_scan_result`, `reconcile_request.seq`, composite `capability`).
- `android/app/schemas/org.privacyguard.android.data.AppDatabase/3.json`: Exported Room schema for version 3.
- `android/app/src/main/java/org/privacyguard/android/shizuku/ShizukuShellService.kt`: Allowlist verification, stdin closure, cached thread pool, and EOF stream draining with 256 KB caps.
- `android/app/src/main/java/org/privacyguard/android/shizuku/ShellOps.kt`: Word boundary command checking, capability checks, read-back verification, and protected package guards.
- `android/app/src/main/java/org/privacyguard/android/shizuku/ShizukuManager.kt`: Single-thread serialized executor (`ioSingle`), cancellation safety, and non-cancellable DB updates.
- `android/app/src/main/java/org/privacyguard/android/work/CrashWatchdogWorker.kt`: 60-second crash verification using `dumpsys activity exit-info`, parsing exit reasons (4, 5, 6, 7) and restoring snapshot on crash.

### Milestone 2: Scan Pipeline, Shield Probe & OSINT Worker
- `android/app/src/main/java/org/privacyguard/android/scan/ScanService.kt`: Process `:scan` engine verifying asset Ed25519 signatures before native initialization, scanning APKs via `pfd.use { nativeScanApkFd(it.fd) }`.
- `android/app/src/main/java/org/privacyguard/android/scan/ScanOrchestrator.kt`: Single-flight binding mutex, 5s connection timeout, 30s scan timeout, 30s idle unbind, atomic crash pre-logging with 2-strike poison pill quarantine, and code split aggregation.
- `android/app/src/main/java/org/privacyguard/android/scan/tier1a/Tier1AScanner.kt`: Heuristic manifest analyzer checking services, receivers, providers, permissions, and cert SHA-256 with `TransactionTooLargeException` fallback.
- `android/app/src/main/java/org/privacyguard/android/trigger/ReconciliationCoordinator.kt`: Orchestrates delta updates and reboot detection with fallback when `BOOT_COUNT` is -1 (`now - syncState.updatedAt > uptime`), extracts tracker signatures, computes streaming APK SHA-256, upserts `AppScanResult`, checks policy drift, and queues OSINT classification.
- `android/app/src/main/java/org/privacyguard/android/shield/ShieldProbe.kt`: Active VPN and Private DNS telemetry probe without `VpnService`:
  - Passive checks: `isPrivateDnsActive`, `privateDnsServerName`, `TRANSPORT_VPN`, and `always_on_vpn_lockdown`.
  - Active Canary Probe: Resolves `app-measurement.com` to verify DNS sinkhole blocking (`0.0.0.0`, `127.0.0.1`, `::1`, or NXDOMAIN).
  - Active Dual-Socket Probe: Queries `https://cloudflare-dns.com/cdn-cgi/trace` over VPN and underlying network to detect stalled tunnels or egress leaks.
- `android/app/src/main/java/org/privacyguard/android/osint/OllamaClient.kt`: Local LAN Ollama HTTP client (`http://127.0.0.1:11434/api/generate`) using `kotlinx.serialization` with strict JSON schema response parsing.
- `android/app/src/main/java/org/privacyguard/android/osint/OsintClassifierWorker.kt`: Rate-limited WorkManager worker enforcing 3 telemetry protection gates:
  - Gate 1: Pinned cert SHA-256 bypass for trusted publishers (Google, Mozilla, F-Droid, AOSP).
  - Gate 2: Sensitive category denylist (finance, health, dating, religion, LGBTQ, VPN/Tor, password managers) that NEVER touch the network.
  - Gate 3: $k$-Anonymity with $k = 4$ decoy popular benign hashes, unmetered network constraint, and battery-not-low guard.

### Unit Tests
- `android/app/src/test/java/org/privacyguard/android/crypto/Ed25519VerifierTest.kt`: Unit tests verifying `trackers_ac.bin` and `lgbm_model.bin` against pinned SPKI public key, verifying bit tampering and signature truncation failures.
- `android/app/src/test/java/org/privacyguard/android/scan/LightGbmParityTest.kt`: Unit tests verifying binary model deserialization, tree evaluation, softmax, and isotonic calibration curves.

---

## 2. Manifest, Privileges & Attack Surface

### Permissions Declared
- `android.permission.INTERNET`: For Cloudflare trace SNI probe, canary DNS resolution, and local LAN Ollama requests.
- `android.permission.ACCESS_NETWORK_STATE`: For querying `ConnectivityManager` active network, link properties, and network capabilities.
- `android.permission.QUERY_ALL_PACKAGES`: For local package inventory and manifest component inspection.
- `moe.shizuku.manager.permission.API_V23`: For Shizuku binder delivery.

### Exported Components & Providers Audit
Inspection of the merged release manifest (`android/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml`) confirms:
1. `org.privacyguard.android.ui.MainActivity`: `exported="true"`, with `android.intent.action.MAIN` and `android.intent.category.LAUNCHER`.
2. `rikka.shizuku.ShizukuProvider`: `exported="true"`, strictly guarded by `android.permission.INTERACT_ACROSS_USERS_FULL`.
3. `androidx.work.impl.background.systemjob.SystemJobService`: `exported="true"`, strictly guarded by `android.permission.BIND_JOB_SERVICE`.
4. `ScanService`: `exported="false"`, running in isolated process `android:process=":scan"`.
5. All third-party receivers (`ProfileInstallReceiver`, `DiagnosticsReceiver`) removed via `tools:node="remove"`.

### Shizuku Privilege Execution Boundary
- All Shizuku commands run via bound `ShizukuShellService` under shell UID (UID 2000).
- Hand-written commands are gated on strict prefix allowlist: `cmd connectivity`, `cmd appops`, `pm disable`, `pm disable-user`, `pm default-state`, `pm enable`, `am kill`, `dumpsys package`, `dumpsys activity exit-info`.
- Arguments are validated with strict regex patterns `^[a-zA-Z0-9_.]+$`.
- Critical system packages (`com.google.android.gms`, `com.android.vending`, `com.google.android.gsf`), notification listeners, and critical services are safe-listed from component modifications.

---

## 3. Outbound Network & Privacy Audit

| Endpoint | Method | Payloads / Fields | Telemetry Gates & Privacy Controls |
|---|---|---|---|
| `https://cloudflare-dns.com/cdn-cgi/trace` | GET | Zero personal data; generic `User-Agent: PrivacyGuard/1.0`; zero cookies; no query parameters. | Active probe only to verify Private DNS, WARP, and detect egress tunnel leaks/stalls. |
| `app-measurement.com` | DNS Query | Standard `InetAddress.getAllByName` lookup. | Canary DNS probe only. Zero HTTP/TCP connection initiated. Checked strictly for sinkhole IPs (`0.0.0.0`, `127.0.0.1`, `::1`) or NXDOMAIN. |
| `http://127.0.0.1:11434/api/generate` | POST | JSON: `{ "model": "llama3.2", "prompt": "<stripped app metadata>", "format": "json" }` | Local LAN only (`network_security_config.xml` permits cleartext exclusively to loopback).<br>- Gate 1: Trusted publishers (Google, Mozilla, F-Droid) bypassed.<br>- Gate 2: Sensitive categories (finance, health, dating, religion, VPN, password managers) never sent.<br>- Gate 3: Unmetered network + battery-not-low + $k=4$ decoy popular hashes. |

Zero user device identifiers (IMEI, Android ID, MAC, advertising ID, Wi-Fi SSIDs) or personal accounts ever touch outbound network requests.

---

## 4. Release Build & On-Device Verification

### 1. Test & Build Execution
Command:
```powershell
.\gradlew.bat testReleaseUnitTest assembleRelease --stacktrace
```
Output:
```
BUILD SUCCESSFUL in 28s
66 actionable tasks: 19 executed, 47 up-to-date
```
Unit test execution:
- `Ed25519VerifierTest`: 4 tests passed (trackers verification, model verification, tampered data failure, corrupted signature failure).
- `LightGbmParityTest`: 1 test passed (model deserialization, 16 features, 4 classes, softmax summation to 1.0, isotonic risk bounds).

### 2. 16 KB Page Alignment Verification (`zipalign`)
Command:
```powershell
& "C:\Users\vcpav\AppData\Local\Android\Sdk\build-tools\35.0.0\zipalign.exe" -c -P 16 -v 4 "android/app/build/outputs/apk/release/app-release.apk"
```
Output:
```
 1212416 lib/arm64-v8a/libprivacyguard_scan.so (OK)
...
Verification succesful
```
`(1212416 % 16384 == 0)` confirms 16 KB page alignment within the release APK archive.

### 3. NDK ELF Segment Alignment Verification (`llvm-readobj`)
Command:
```powershell
& "C:\Users\vcpav\AppData\Local\Android\Sdk\ndk\28.0.13004108\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readobj.exe" -l "android/app/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/arm64-v8a/libprivacyguard_scan.so"
```
Output:
```
  ProgramHeader {
    Type: PT_LOAD (0x1)
    Offset: 0x0
    VirtualAddress: 0x0
    PhysicalAddress: 0x0
    FileSize: 415840
    MemSize: 415840
    Flags [ (0x4)
      PF_R (0x4)
    ]
    Alignment: 16384
  }
  ProgramHeader {
    Type: PT_LOAD (0x1)
    Offset: 0x65860
    VirtualAddress: 0x69860
    PhysicalAddress: 0x69860
    FileSize: 467136
    MemSize: 467136
    Flags [ (0x5)
      PF_R (0x4)
      PF_X (0x1)
    ]
    Alignment: 16384
  }
  ProgramHeader {
    Type: PT_LOAD (0x1)
    Offset: 0xD7920
    VirtualAddress: 0xDF920
    PhysicalAddress: 0xDF920
    FileSize: 34192
    MemSize: 34528
    Flags [ (0x6)
      PF_R (0x4)
      PF_W (0x2)
    ]
    Alignment: 16384
  }
  ProgramHeader {
    Type: PT_LOAD (0x1)
    Offset: 0xDFEB0
    VirtualAddress: 0xEBEB0
    PhysicalAddress: 0xEBEB0
    FileSize: 480
    MemSize: 8512
    Flags [ (0x6)
      PF_R (0x4)
      PF_W (0x2)
    ]
    Alignment: 16384
  }
```
All four `PT_LOAD` segments exhibit `Alignment: 16384` (0x4000), fully compliant with Android 15+ 16 KB page requirements.
