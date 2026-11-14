# Walkthrough: Milestone 1A — Android Project Scaffold, Room State Machine & Shizuku UserService Engine

## 1. Files Created/Modified
- `android/gradle/libs.versions.toml`: Centralized version catalog with AGP 8.6.1, Kotlin 2.0.20, Room 2.6.1 + KSP, WorkManager 2.9.1, Shizuku 13.1.5, Rikka Refine & Hidden API stubs 4.4.0, HiddenApiBypass 6.1; zero Firebase or Play Services dependencies.
- `android/build.gradle.kts`: Root Gradle build script applying Android application, Kotlin, KSP, and Refine plugins.
- `android/settings.gradle.kts`: Configured plugin and dependency repositories (Google, MavenCentral, Xposed API).
- `android/gradle.properties`: AndroidX enabled (`android.useAndroidX=true`), memory tunings, and compileSdk suppression.
- `android/local.properties`: Configured local Android SDK path.
- `android/app/build.gradle.kts`: App module build configuration with `arm64-v8a` ABI filter, `useLegacyPackaging = false`, AIDL + BuildConfig enabled, R8 shrink and minify enabled on release.
- `android/app/proguard-rules.pro`: Complete R8 keep rules for Shizuku `UserService`, AIDL interfaces/stubs, Room entities/DB, HiddenApiBypass, Refine, and WorkManager workers.
- `android/app/src/main/AndroidManifest.xml`: Hardened manifest declaring `QUERY_ALL_PACKAGES` and Shizuku permissions; sole exported application component is `MainActivity`.
- `android/app/src/main/aidl/org/privacyguard/android/service/ShellResult.aidl`: Parcelable AIDL declaration for command execution results.
- `android/app/src/main/aidl/org/privacyguard/android/service/IShellService.aidl`: AIDL interface declaring `exec(String[] cmd) = 1` and `destroy() = 16777114`.
- `android/app/src/main/java/org/privacyguard/android/service/ShellResult.kt`: Parcelable implementation returning exitCode, stdout, and stderr.
- `android/app/src/main/java/org/privacyguard/android/shizuku/ShizukuShellService.kt`: Bound Shizuku `UserService` running under shell UID executing `cmd` shell processes and handling transaction `destroy()`.
- `android/app/src/main/java/org/privacyguard/android/shizuku/ShellOps.kt`: Wi-Fi isolation ladder (Chain 3 -> Chain 9 -> AppOps/Standby -> `"UNISOLATABLE_ON_WIFI"`), UID calculation (`userId * 100000 + appId`), safe-list protection, component toggling (`COMPONENT_ENABLED_STATE_DISABLED_USER`), read-back verification, and `am kill --user 0 <pkg>`.
- `android/app/src/main/java/org/privacyguard/android/shizuku/ShizukuManager.kt`: Shizuku binder lifecycle manager, permission checker, optimistic locking orchestrator, and single-thread serialized `Dispatchers.IO.limitedParallelism(1)` executor.
- `android/app/src/main/java/org/privacyguard/android/work/CrashWatchdogWorker.kt`: Durable 60-second WorkManager coroutine worker inspecting `ActivityManager.getHistoricalProcessExitReasons` and executing automatic snapshot rollback (`ROLLED_BACK_UNSAFE`).
- `android/app/src/main/java/org/privacyguard/android/data/entity/ReconcileRequest.kt`: Room entity for coalesced package reconciliation requests.
- `android/app/src/main/java/org/privacyguard/android/data/entity/PolicyOp.kt`: Room entity for policy operations indexed on `(pkg, versionCode)` and `status` with generation token for optimistic locking.
- `android/app/src/main/java/org/privacyguard/android/data/entity/Capability.kt`: Room entity caching probed shell capabilities with device fingerprint.
- `android/app/src/main/java/org/privacyguard/android/data/entity/ScanFailed.kt`: Room entity tracking `:scan` crash counts for poison-pill quarantine.
- `android/app/src/main/java/org/privacyguard/android/data/dao/ReconcileRequestDao.kt`: Room DAO with `@Transaction` batch pop and `INSERT OR IGNORE`.
- `android/app/src/main/java/org/privacyguard/android/data/dao/PolicyOpDao.kt`: Room DAO implementing optimistic locking updates and runnable queries.
- `android/app/src/main/java/org/privacyguard/android/data/dao/CapabilityDao.kt`: Room DAO for querying and upserting shell capabilities.
- `android/app/src/main/java/org/privacyguard/android/data/dao/ScanFailedDao.kt`: Room DAO for crash recording and quarantine determination.
- `android/app/src/main/java/org/privacyguard/android/data/AppDatabase.kt`: Room database configured with WAL mode and `PRAGMA synchronous = NORMAL`.
- `android/app/src/main/java/org/privacyguard/android/trigger/PackageChangeReceiver.kt`: Dynamic broadcast receiver for package addition/replacement/removal inserting into `reconcile_request`.
- `android/app/src/main/java/org/privacyguard/android/trigger/PowerSaveReceiver.kt`: Dynamic broadcast receiver for power save whitelist changes triggering reconciliation check.
- `android/app/src/main/java/org/privacyguard/android/trigger/ReconciliationCoordinator.kt`: Coordinator handling delta checks via `PackageManager.getChangedPackages(lastSeq)` and coalesced execution.
- `android/app/src/main/java/org/privacyguard/android/ui/MainActivity.kt`: Skeleton launcher activity (sole app-exported component).
- `android/app/src/main/java/org/privacyguard/android/PrivacyGuardApp.kt`: Application entry point with strict multi-process early return (`processName != packageName`), `HiddenApiBypass.addHiddenApiExemptions("")` on main process, and dynamic receiver registrations with explicit export flags (`RECEIVER_EXPORTED` for system package events).

---

## 2. Manifest, Privileges & Attack Surface
- **Declared Permissions:**
  - `android.permission.QUERY_ALL_PACKAGES` (required for system-wide manifest inspection)
  - `moe.shizuku.manager.permission.API_V23` (required for Shizuku IPC)
- **Exported Flags & Components:**
  - `org.privacyguard.android.ui.MainActivity`: `android:exported="true"` (sole application entry point).
  - Zero other application components exported.
  - Background receivers are registered dynamically with explicit flags:
    - `PackageChangeReceiver`: `Context.RECEIVER_EXPORTED` (receives system UID 1000 broadcasts).
    - `PowerSaveReceiver`: `Context.RECEIVER_NOT_EXPORTED`.
- **Shizuku Privilege Logic:**
  - `ShizukuShellService`: Bound UserService executing under UID `com.android.shell`.
  - Transaction `destroy() = 16777114` calls `System.exit(0)`.
  - Mutations gated by `Shizuku.pingBinder()` + `Shizuku.checkSelfPermission() == PERMISSION_GRANTED`.
  - Process killing strictly uses `am kill --user 0 <pkg>` (never `am force-stop`).
  - Protected safe-list strictly preserves Google Mobile Services, Play Store, and Firebase initialization/messaging services.

---

## 3. Outbound Network & Privacy Audit
- **Outbound Network Calls:** None.
- **Payloads / Endpoints Touched:** None. All operations execute strictly on-device using Room WAL persistence and local IPC via Shizuku shell service.

---

## 4. Release Build & On-Device Verification
- **Gradle Release Compilation:**
  Command: `.\gradlew.bat assembleRelease --stacktrace`
  Output: `BUILD SUCCESSFUL in 1m 57s` (52 actionable tasks: 20 executed, 32 up-to-date).
  R8 code minification and resource shrinking completed with zero errors.
- **16 KB Page Alignment Verification:**
  Command: `zipalign -c -P 16 -v 4 app-release.apk`
  Output:
  ```
  Verifying alignment of c:\Projects\privacy-guard\android\app\build\outputs\apk\release\app-release.apk (4)...
  ...
  Verification succesful
  ```
  Result: 100% 16-byte alignment verified on the release APK.
