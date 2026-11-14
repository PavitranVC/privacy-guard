# Implementation Plan: Milestone 1A — Project Scaffold, Room State Machine & Shizuku UserService Engine

## 1. Overview
This milestone establishes the foundational Android project architecture within `android/` adhering strictly to `GEMINI.md` invariants, `docs/architecture.md` (v5 Locked), and Android 15–16 guardrails.

No UI (Jetpack Compose) will be developed in this milestone—the focus is entirely on:
1. Hardened Gradle version catalog (`android/gradle/libs.versions.toml`), root & app `build.gradle.kts`, `proguard-rules.pro`, and `AndroidManifest.xml`.
2. Multi-process guarded `Application.onCreate()` with `HiddenApiBypass`.
3. Room Database (WAL + `synchronous=NORMAL`) with `@Transaction` DAOs (`reconcile_request`, `policy_op`, `capability`, `scan_failed`).
4. Coalesced event ingestion and single-thread serialized Shizuku executor (`Dispatchers.IO.limitedParallelism(1)`).
5. AIDL-backed Shizuku `UserService` (`void destroy() = 16777114`) executing `cmd` shell commands under shell UID.
6. Probed `ShellOps` including 4-step Wi-Fi isolation ladder, safe-listed component toggling (`COMPONENT_ENABLED_STATE_DISABLED_USER`), read-back verification, `am kill`, and 60-second crash auto-rollback (`ROLLED_BACK_UNSAFE`).

---

## 2. File Structure & Responsibilities

```
android/
├── gradle/
│   ├── libs.versions.toml             # Centralized version catalog (AGP, Kotlin, KSP, Room, Shizuku, HiddenAPI)
│   └── wrapper/
│       ├── gradle-wrapper.properties
│       └── gradle-wrapper.jar
├── build.gradle.kts                   # Root build script
├── settings.gradle.kts                # Settings configuration
├── gradlew & gradlew.bat
└── app/
    ├── build.gradle.kts               # App build script: R8, arm64-v8a, Refine, KSP, Room, Shizuku
    ├── proguard-rules.pro             # Complete R8 keep rules (UserService, AIDL, Room, HiddenApiBypass)
    └── src/main/
        ├── AndroidManifest.xml        # Minimal attack surface: only MainActivity exported, QUERY_ALL_PACKAGES
        ├── aidl/org/privacyguard/android/service/
        │   ├── IShellService.aidl     # AIDL: exec(String[] cmd) and destroy() = 16777114
        │   └── ShellResult.aidl       # Parcelable declaration
        └── java/org/privacyguard/android/
            ├── PrivacyGuardApp.kt     # Multi-process guard; HiddenApiBypass in main process
            ├── ui/
            │   └── MainActivity.kt    # Skeleton launcher activity (exported="true")
            ├── data/
            │   ├── AppDatabase.kt     # RoomDatabase (WAL, PRAGMA synchronous=NORMAL)
            │   ├── entity/
            │   │   ├── ReconcileRequest.kt  # pkg (PK), requestedAt
            │   │   ├── PolicyOp.kt          # indexed on (pkg, versionCode) and status, gen optimistic lock
            │   │   ├── Capability.kt        # op (PK), supported, buildFingerprint, probedAt
            │   │   └── ScanFailed.kt        # (pkg, versionCode) PK, crashCount
            │   └── dao/
            │       ├── ReconcileRequestDao.kt
            │       ├── PolicyOpDao.kt
            │       ├── CapabilityDao.kt
            │       └── ScanFailedDao.kt
            ├── shizuku/
            │   ├── ShellResult.kt           # Parcelable implementation (exitCode, stdout, stderr)
            │   ├── ShizukuShellService.kt   # UserService implementation running as shell UID
            │   ├── ShizukuManager.kt        # Service binding, binder listener, permission check, limitedParallelism(1)
            │   └── ShellOps.kt              # Wi-Fi isolation ladder, safe-list check, component disable, am kill, crash check
            └── trigger/
                ├── PackageChangeReceiver.kt # BroadcastReceiver for PACKAGE_ADDED/CHANGED/REPLACED + delta tracker
                ├── PowerSaveReceiver.kt     # ACTION_POWER_SAVE_WHITELIST_CHANGED trigger
                └── ReconciliationCoordinator.kt # Coalesced triggers -> reconcile_request
```

---

## 3. Detailed Component Design

### 3.1 Gradle Configuration (`libs.versions.toml` & `build.gradle.kts`)
- **Version Catalog:**
  - AGP `8.5.1`+
  - Kotlin `2.0.20`+
  - Room `2.6.1` + KSP
  - WorkManager `2.9.1`
  - `kotlinx.serialization` `1.7.3`
  - `androidx.profileinstaller` `1.4.1`
  - `dev.rikka.shizuku:api:13.1.5` & `dev.rikka.shizuku:provider:13.1.5`
  - `dev.rikka.hidden:stub:4.3.0` & `dev.rikka.hidden:compat:4.3.0`
  - `dev.rikka.tools.refine:gradle-plugin:4.4.0`
  - `org.lsposed.hiddenapibypass:hiddenapibypass:6.1`
  - **Zero** Firebase or Google Play Services libraries.
- **App Module Configuration:**
  - `ndk.abiFilters += "arm64-v8a"`
  - `packaging.jniLibs.useLegacyPackaging = false`
  - Release build: `isMinifyEnabled = true`, `isShrinkResources = true`, `proguard-rules.pro`.
  - AIDL build feature enabled: `buildFeatures { aidl = true }`.

### 3.2 Manifest Hardening (`AndroidManifest.xml`)
- Zero exported components except `<activity android:name=".ui.MainActivity" android:exported="true">`.
- Declare `<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />`.
- Declare Shizuku provider integration via `rikka.shizuku.ShizukuProvider`.
- All background receivers registered dynamically or with `android:exported="false"`.

### 3.3 Application Lifecycle & Process Isolation (`PrivacyGuardApp.kt`)
- `Application.onCreate()` checks `Application.getProcessName()`.
- If `processName.endsWith(":scan")`: immediately return and skip Room, WorkManager, and Shizuku initialization.
- If main process:
  - Run `HiddenApiBypass.addHiddenApiExemptions("")`.
  - Initialize Room database instance.
  - Register Shizuku binder listener and triggers.

### 3.4 Room Database & Entities
- **Database Configuration:** `Room.databaseBuilder(...).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)` with `Callback` executing `execSQL("PRAGMA synchronous = NORMAL")`.
- **Entities:**
  - `ReconcileRequest(@PrimaryKey val pkg: String, val requestedAt: Long)`
  - `PolicyOp(id: Long, pkg: String, versionCode: Long, lastUpdateTime: Long, op: String, target: String, desired: String, observed: String?, status: String, errClass: String?, attempts: Int, nextAt: Long, gen: Long, snapshot: String?)` with index on `(pkg, versionCode)` and index on `status`.
  - `Capability(@PrimaryKey val op: String, val supported: Boolean, val buildFingerprint: String, val probedAt: Long)`
  - `ScanFailed(@NonNull val pkg: String, val versionCode: Long, val crashCount: Int)` with primary key `[pkg, versionCode]`.
- **Optimistic Locking:** Mutations use `UPDATE policy_op ... WHERE id = :id AND gen = :gen`.

### 3.5 Shizuku `UserService` & `ShellOps` Logic
- **AIDL Service:**
  `IShellService.aidl` declaring:
  - `ShellResult exec(in String[] cmd);`
  - `void destroy() = 16777114;`
- **UserService Implementation (`ShizukuShellService`):**
  Runs under shell UID (`com.android.shell`). Executes process via `Runtime.getRuntime().exec(cmd)` and collects stdout/stderr, returning `ShellResult`. In `destroy()`, calls `System.exit(0)`.
- **Capability Probing:**
  On binder connect, runs probe commands via Shizuku:
  - `cmd connectivity help` (checks `chain3` and `set-package-networking-enabled`)
  - `cmd appops`
  - `pm`
  Results cached in `capability` table with `Build.FINGERPRINT`.
- **Wi-Fi Isolation Ladder:**
  1. `cmd connectivity set-chain3-enabled true` + `cmd connectivity set-package-networking-enabled false <pkg>`.
  2. Fallback to `IConnectivityManager` firewall chain 9 binder command.
  3. Fallback to `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore` + `am set-standby-bucket <pkg> restricted`.
  4. Fallback status `"UNISOLATABLE_ON_WIFI"`.
  Multi-user UID calculation: `val uid = userId * 100000 + appId`. Shared-UID detection issues explicit warning.
- **Component Disabling & Crash-Safe Rollback:**
  - Protected safe-list:
    `com.google.android.gms`, `com.android.vending`, `com.android.gsf`, `InitializationProvider`, `FirebaseInitProvider`, `FirebaseMessagingService`, `FirebaseInstanceIdReceiver`, `ComponentDiscoveryService`, `GoogleApiActivity`, and OAuth activities.
  - Mutation: `COMPONENT_ENABLED_STATE_DISABLED_USER` with `DONT_KILL_APP` (or `pm disable-user --user 0 <component>`).
  - Read-back verification: checks `PackageManager.getComponentEnabledSetting` matches desired.
  - Process termination: `am kill --user 0 <pkg>` (strictly avoiding `am force-stop`).
  - Crash Auto-Rollback: monitors `ActivityManager.getHistoricalProcessExitReasons` within 60s for `REASON_CRASH` / `REASON_CRASH_NATIVE`. If crash observed, restores original snapshot and transitions status to `ROLLED_BACK_UNSAFE`.

---

## 4. Verification Plan
1. **Compilation & Release Build:**
   Execute `./gradlew assembleRelease` inside `android/` verifying zero R8 errors, full shrink/minify pass.
2. **16 KB Page Alignment & Packaging:**
   Verify `zipalign -c -P 16 -v 4 app-release.apk` to ensure 16 KB ELF/APK alignment for Android 15/16.
3. **Audit Compliance:**
   Verify manifest permissions and zero exported components (except `MainActivity`).
   Verify absence of Firebase and Google Play Services dependencies.
4. **Documentation:**
   Generate `walkthrough.md` per Section 3 of `GEMINI.md`.
