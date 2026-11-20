# Environment Specification

## Target Device
- **Device Model:** Motorola Moto G54 5G (`cancunf_g_sysq` / `cancunf`) [owner-stated; verified via adb getprop]
- **OS Version:** Android 15 (SDK 35) [owner-stated; verified in repo build config and adb getprop]
- **Build Fingerprint:** `motorola/cancunf_g_sysq/cancunf:15/V1TDS35H.83-20-5-14/24971a-330658:user/release-keys` [owner-stated; verified via adb getprop]
- **Root Status:** Not rooted [owner-stated]

## Privilege Architecture
- **Provider:** Shizuku [verified-in-repo]
- **Execution Context:** Shell UID 2000 [measured]
- **Service Binding:** Bound Shizuku `UserService` implementing AIDL interface `IShellService` (`android/app/src/main/aidl/org/privacyguard/android/service/IShellService.aidl`) [verified-in-repo]
- **Lifecycle Requirement:** Must be manually started on the phone (via Wireless Debugging or PC adb) after each device reboot [owner-stated]

## Software & Build Stack
All stack versions are verified directly from repository configuration files:
- **Build & Optimization:** R8 full mode enabled via `isMinifyEnabled = true`, `isShrinkResources = true`, and `proguard-android-optimize.txt` (`android/app/build.gradle.kts` lines 99-108) [verified-in-repo]
- **Native Engine:** C++17 isolated `:scan` engine configured with `set(CMAKE_CXX_STANDARD 17)` (`android/app/src/main/cpp/CMakeLists.txt` line 5) and `cppFlags += listOf("-std=c++17", ...)` (`android/app/build.gradle.kts` line 69) [verified-in-repo]
- **Persistence:** Room schema version 3 (`android/app/src/main/java/org/privacyguard/android/data/AppDatabase.kt` line 31) running in Write-Ahead Logging (WAL) mode with `PRAGMA synchronous = NORMAL` (`AppDatabase.kt` lines 125, 130) [verified-in-repo]. The underlying AndroidX Room library dependency is version `2.6.1` (`android/gradle/libs.versions.toml` line 5) [verified-in-repo].
- **Android SDK:** `compileSdk = 35`, `targetSdk = 35`, `minSdk = 34` (`android/app/build.gradle.kts` lines 50, 55-56) [verified-in-repo]
- **NDK:** NDK version `28.0.13004108`, restricted to `arm64-v8a` (`android/app/build.gradle.kts` lines 51, 63) [verified-in-repo]
- **Gradle & Tooling:** AGP `8.6.1` (`android/gradle/libs.versions.toml` line 2); Kotlin `2.0.20` (`libs.versions.toml` line 3); KSP `2.0.20-1.0.25` (`libs.versions.toml` line 4) [verified-in-repo]
- **Shizuku API:** `13.1.5` (`android/gradle/libs.versions.toml` line 9) [verified-in-repo]
- **Testing Dependencies:** `androidx-test-runner = "1.6.2"`, `androidx-test-ext-junit = "1.2.1"` (`android/gradle/libs.versions.toml` lines 19-20) [verified-in-repo]

## Host Environment
- **Host OS:** Windows PC [owner-stated]
- **Repository Path:** `C:\Projects\privacy-guard` [owner-stated]
- **Gradle Invocation:** Executed via `.\gradlew.bat` from `android\` [owner-stated]
- **ADB Command:** Invoked via variable `$ADB` pointing to the Android SDK platform-tools binary (`C:\Users\vcpav\AppData\Local\Android\Sdk\platform-tools\adb.exe`) [owner-stated]

## External Evidence Directories
Raw command outputs, device dumps, and execution ledgers are preserved strictly outside the git repository on the host machine:
- `C:\Projects\_smoke_e\`
- `C:\Projects\_smoke_g\`
- `C:\Projects\_smoke_h\`
- `C:\Projects\_smoke_i\`
- `C:\Projects\_smoke_m4b\`

## On-Device Testing Targets
Strict safety restriction: on-device experimentation and command execution must target **ONLY** the following three packages [owner-decision]:
1. `com.ansangha.drdriving` (UID 10355)
2. `com.ludo.king` (UID 10047)
3. `com.rarlab.rar` (UID 10273)

Never touch system apps, launchers, Google Mobile Services (GMS), dialer, SMS, Shizuku, or `org.privacyguard.android` itself [standing-rule].

## Forbidden Commands
- **`gradlew connectedAndroidTest`:** FORBIDDEN. Built-in Gradle target uninstalls existing APKs prior to test execution, destroying user-granted Shizuku authorization and database state [owner-decision].
- **Plain `pm clear <pkg>` without `--cache-only`:** FORBIDDEN. Wipes application user data and settings [standing-rule].
- **Uninstalling the main app (`org.privacyguard.android`):** FORBIDDEN. Destroys device pairing and grants [standing-rule].

## Approved Manual androidTest Flow
To execute on-device instrumentation tests while preserving Shizuku pairing tokens and Room data, use the approved manual flow (`implementation_plan.md` lines 145-167) [owner-decision]:

1. Build debug and test APKs:
   ```powershell
   cd android ; .\gradlew.bat assembleDebug assembleDebugAndroidTest
   ```
2. Reinstall application APK without wiping permissions or data (`-r`):
   ```powershell
   & $ADB install -r android/app/build/outputs/apk/debug/app-debug.apk
   ```
3. Install test APK with test permission (`-r -t`):
   ```powershell
   & $ADB install -r -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
   ```
4. Run instrumentation tests inside the app process (inheriting the user's Shizuku binder grant):
   ```powershell
   & $ADB shell am instrument -w -e class org.privacyguard.android.AppPathSmokeTest org.privacyguard.android.test/androidx.test.runner.AndroidJUnitRunner
   ```
5. Teardown: Uninstall **ONLY** the test instrumentation package, never the main app:
   ```powershell
   & $ADB uninstall org.privacyguard.android.test
   ```
