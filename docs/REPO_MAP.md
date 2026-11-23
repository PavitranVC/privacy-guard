# Privacy Guard: Repository Map

Comprehensive index of all 98 git-tracked files across the repository, documenting exact line counts, roles, directory hierarchy, and key architectural signatures.

## 1. Directory Structure
```text
.
|-- .gitignore
|-- AUDIT_RULES.md
|-- CONTEXT.md
|-- GEMINI.md
|-- implementation_plan.md
|-- walkthrough.md
|-- docs/
|   |-- architecture.md
|   |-- DECISIONS.md
|   |-- ENVIRONMENT.md
|   |-- REPO_MAP.md
|   |-- STATUS.md
|   |-- VISION.md
|   |-- WORKING_RULES.md
|   \-- experiments/
|       \-- m3.5-g/
|           |-- device_facts.txt
|           |-- help_appops.txt
|           |-- help_connectivity.txt
|           \-- matrix.csv
\-- android/
    |-- build.gradle.kts
    |-- gradle.properties
    |-- gradlew
    |-- gradlew.bat
    |-- settings.gradle.kts
    |-- gradle/
    |   |-- libs.versions.toml
    |   \-- wrapper/
    |       |-- gradle-wrapper.jar
    |       \-- gradle-wrapper.properties
    \-- app/
        |-- build.gradle.kts
        |-- proguard-rules.pro
        |-- schemas/
        |   \-- org.privacyguard.android.data.AppDatabase/
        |       |-- 2.json
        |       \-- 3.json
        \-- src/
            |-- androidTest/
            |   \-- java/org/privacyguard/android/AppPathSmokeTest.kt
            |-- main/
            |   |-- AndroidManifest.xml
            |   |-- aidl/org/privacyguard/android/
            |   |   |-- scan/ (IScanService.aidl, NativeScanResult.aidl)
            |   |   \-- service/ (IShellService.aidl, ShellResult.aidl)
            |   |-- assets/ (lgbm_model.bin, trackers_ac.bin + signatures)
            |   |-- cpp/ (CMakeLists.txt, aho_corasick.hpp, apk_mmap.hpp, lgbm_evaluator.hpp, scanner_jni.cpp)
            |   |-- java/org/privacyguard/android/
            |   |   |-- PrivacyGuardApp.kt
            |   |   |-- crypto/ (Ed25519Verifier.kt)
            |   |   |-- data/ (AppDatabase.kt, dao/, entity/)
            |   |   |-- osint/ (OllamaClient.kt, OsintClassifierWorker.kt)
            |   |   |-- scan/ (NativeScanResult.kt, ScanOrchestrator.kt, ScanService.kt, tier1a/)
            |   |   |-- service/ (ShellResult.kt)
            |   |   |-- shield/ (ShieldProbe.kt)
            |   |   |-- shizuku/ (PrivilegeBackend.kt, ShellOps.kt, ShizukuManager.kt, ShizukuShellService.kt)
            |   |   |-- trigger/ (PackageChangeReceiver.kt, PowerSaveReceiver.kt, ReconciliationCoordinator.kt)
            |   |   |-- ui/ (AppDetailScreen.kt, AppListScreen.kt, DashboardCard.kt, MainActivity.kt, ...)
            |   |   \-- work/ (CrashWatchdogWorker.kt, ReconcileDrainWorker.kt)
            |   \-- res/xml/network_security_config.xml
            \-- test/
                |-- java/org/privacyguard/android/ (10 test suites)
                \-- resources/fixtures/ (5 hardware device captures)
```

## 2. Tracked Files Catalog
| File Path | Lines | Role |
|:---|:---:|:---|
| `.gitignore` | 32 | Git ignore rules for build artifacts, keystores, and IDE files. |
| `AUDIT_RULES.md` | 7 | Audit guidelines and evidence standards for external review. |
| `CONTEXT.md` | 24 | Project root memory and document pointer for agents and contributors. |
| `GEMINI.md` | 26 | Architectural constraints, invariant rules, and security guidelines. |
| `android/app/build.gradle.kts` | 192 | Application build script, SDK/NDK configuration, dependencies, and R8 rules. |
| `android/app/proguard-rules.pro` | 60 | R8 and ProGuard keep rules for Shizuku, Refine, and native JNI bindings. |
| `android/app/schemas/org.privacyguard.android.data.AppDatabase/2.json` | 284 | Room schema definition export for database version 2. |
| `android/app/schemas/org.privacyguard.android.data.AppDatabase/3.json` | 377 | Room schema definition export for database version 3. |
| `android/app/src/androidTest/java/org/privacyguard/android/AppPathSmokeTest.kt` | 24 | On-device instrumentation smoke test verifying Shizuku binder and package access. |
| `android/app/src/main/AndroidManifest.xml` | 68 | Application manifest declaring permissions, receivers, and :scan process. |
| `android/app/src/main/aidl/org/privacyguard/android/scan/IScanService.aidl` | 8 | AIDL interface for isolated :scan APK scanning process. |
| `android/app/src/main/aidl/org/privacyguard/android/scan/NativeScanResult.aidl` | 3 | AIDL parcelable declaration for NativeScanResult. |
| `android/app/src/main/aidl/org/privacyguard/android/service/IShellService.aidl` | 8 | AIDL interface for privileged Shizuku UserService commands. |
| `android/app/src/main/aidl/org/privacyguard/android/service/ShellResult.aidl` | 3 | AIDL parcelable declaration for ShellResult. |
| `android/app/src/main/assets/lgbm_model.bin` | 5 | Binary LightGBM model tree array asset. |
| `android/app/src/main/assets/lgbm_model.bin.sig` | 2 | Ed25519 signature verifying integrity of lgbm_model.bin. |
| `android/app/src/main/assets/trackers_ac.bin` | 14 | Binary Aho-Corasick automaton asset for tracker signature detection. |
| `android/app/src/main/assets/trackers_ac.bin.sig` | 3 | Ed25519 signature verifying integrity of trackers_ac.bin. |
| `android/app/src/main/cpp/CMakeLists.txt` | 28 | CMake build configuration for native C++17 APK scanner engine. |
| `android/app/src/main/cpp/aho_corasick.hpp` | 130 | Header-only Aho-Corasick string matching implementation for tracker matching. |
| `android/app/src/main/cpp/apk_mmap.hpp` | 285 | Low-level APK ZIP parsing and memory-mapped file reader. |
| `android/app/src/main/cpp/lgbm_evaluator.hpp` | 244 | C++ flat-array LightGBM inference evaluator with isotonic calibration. |
| `android/app/src/main/cpp/scanner_jni.cpp` | 415 | JNI bridge between ScanService and C++ static analysis engine. |
| `android/app/src/main/java/org/privacyguard/android/PrivacyGuardApp.kt` | 73 | Application class initializing Room, receivers, and HiddenApiBypass in main process. |
| `android/app/src/main/java/org/privacyguard/android/crypto/Ed25519Verifier.kt` | 35 | Cryptographic verification utility for Ed25519 signatures using public SPKI. |
| `android/app/src/main/java/org/privacyguard/android/data/AppDatabase.kt` | 136 | Room database definition holding scan cache, policies, and capabilities. |
| `android/app/src/main/java/org/privacyguard/android/data/dao/AppScanResultDao.kt` | 33 | Data access object for application scan results and OSINT verdicts. |
| `android/app/src/main/java/org/privacyguard/android/data/dao/CapabilityDao.kt` | 30 | Data access object for probed device shell capabilities. |
| `android/app/src/main/java/org/privacyguard/android/data/dao/PolicyOpDao.kt` | 255 | Data access object for policy operations, drift, and rollback states. |
| `android/app/src/main/java/org/privacyguard/android/data/dao/ReconcileRequestDao.kt` | 41 | Data access object for package reconciliation queue requests. |
| `android/app/src/main/java/org/privacyguard/android/data/dao/ScanFailedDao.kt` | 33 | Data access object for tracking and quarantining crashed package scans. |
| `android/app/src/main/java/org/privacyguard/android/data/dao/SyncStateDao.kt` | 16 | Data access object for synchronization state and timestamps. |
| `android/app/src/main/java/org/privacyguard/android/data/entity/AppScanResult.kt` | 21 | Room entity storing manifest analysis, static scan, and OSINT verdict. |
| `android/app/src/main/java/org/privacyguard/android/data/entity/Capability.kt` | 14 | Room entity storing verified device command capability states. |
| `android/app/src/main/java/org/privacyguard/android/data/entity/PolicyOp.kt` | 43 | Room entity tracking desired vs observed policy remediation operations. |
| `android/app/src/main/java/org/privacyguard/android/data/entity/ReconcileRequest.kt` | 12 | Room entity queuing package changes for background reconciliation. |
| `android/app/src/main/java/org/privacyguard/android/data/entity/ScanFailed.kt` | 14 | Room entity recording package scan crashes for poison-pill quarantine. |
| `android/app/src/main/java/org/privacyguard/android/data/entity/SyncState.kt` | 13 | Room entity recording background sync and rule version metadata. |
| `android/app/src/main/java/org/privacyguard/android/osint/OllamaClient.kt` | 115 | HTTP client querying local Ollama instance for LLM risk assessment. |
| `android/app/src/main/java/org/privacyguard/android/osint/OsintClassifierWorker.kt` | 160 | WorkManager worker executing OSINT analysis with trusted shortcuts. |
| `android/app/src/main/java/org/privacyguard/android/scan/NativeScanResult.kt` | 53 | Data class and Parcelable representation of native APK scan results. |
| `android/app/src/main/java/org/privacyguard/android/scan/ScanOrchestrator.kt` | 211 | Orchestrator managing APK split scans and IPC binding to :scan process. |
| `android/app/src/main/java/org/privacyguard/android/scan/ScanService.kt` | 104 | Bound service running in :scan process hosting native JNI engine. |
| `android/app/src/main/java/org/privacyguard/android/scan/tier1a/Tier1AScanner.kt` | 167 | Fast synchronous manifest scanner inspecting permissions and components. |
| `android/app/src/main/java/org/privacyguard/android/service/ShellResult.kt` | 29 | Data class and Parcelable holding exit code, stdout, and stderr. |
| `android/app/src/main/java/org/privacyguard/android/shield/ShieldProbe.kt` | 228 | Network probe evaluating VPN tunnel leaks, DNS, and WARP status. |
| `android/app/src/main/java/org/privacyguard/android/shizuku/PrivilegeBackend.kt` | 22 | Pluggable abstraction interface and Shizuku delegation for shell execution. |
| `android/app/src/main/java/org/privacyguard/android/shizuku/ShellOps.kt` | 719 | High-level shell remediation operations (chain3, buckets, appops). |
| `android/app/src/main/java/org/privacyguard/android/shizuku/ShizukuManager.kt` | 553 | Manager managing Shizuku binder lifecycle, service binding, and token events. |
| `android/app/src/main/java/org/privacyguard/android/shizuku/ShizukuShellService.kt` | 190 | Shizuku UserService implementation enforcing strict command allowlist. |
| `android/app/src/main/java/org/privacyguard/android/trigger/PackageChangeReceiver.kt` | 43 | BroadcastReceiver listening for package additions, updates, and removals. |
| `android/app/src/main/java/org/privacyguard/android/trigger/PowerSaveReceiver.kt` | 33 | BroadcastReceiver responding to power save and battery optimization changes. |
| `android/app/src/main/java/org/privacyguard/android/trigger/ReconciliationCoordinator.kt` | 317 | Coordinator processing reconciliation queue, scanning, and remediation. |
| `android/app/src/main/java/org/privacyguard/android/ui/AppDetailScreen.kt` | 488 | Compose screen displaying detailed scan findings, permissions, and policy actions. |
| `android/app/src/main/java/org/privacyguard/android/ui/AppListScreen.kt` | 270 | Compose screen rendering searchable list of installed applications and risk. |
| `android/app/src/main/java/org/privacyguard/android/ui/DashboardCard.kt` | 311 | Compose dashboard card showing system security status and overview. |
| `android/app/src/main/java/org/privacyguard/android/ui/MainActivity.kt` | 92 | Main application activity hosting navigation and Compose UI. |
| `android/app/src/main/java/org/privacyguard/android/ui/PrivacyGuardViewModel.kt` | 440 | ViewModel orchestrating UI state, app querying, and user remediation requests. |
| `android/app/src/main/java/org/privacyguard/android/ui/Theme.kt` | 67 | Material 3 design theme, typography, and color palette definitions. |
| `android/app/src/main/java/org/privacyguard/android/work/CrashWatchdogWorker.kt` | 221 | Worker monitoring target package crashes to automatically rollback unsafe policies. |
| `android/app/src/main/java/org/privacyguard/android/work/ReconcileDrainWorker.kt` | 29 | WorkManager worker triggering queued reconciliation processing. |
| `android/app/src/main/res/xml/network_security_config.xml` | 8 | Network security configuration allowing loopback cleartext traffic for Ollama. |
| `android/app/src/test/java/org/privacyguard/android/crypto/Ed25519VerifierTest.kt` | 75 | Unit tests verifying Ed25519 signature checks against known keypairs. |
| `android/app/src/test/java/org/privacyguard/android/osint/TrustedPublisherTest.kt` | 39 | Unit tests verifying trusted-publisher certificate fingerprint shortcuts. |
| `android/app/src/test/java/org/privacyguard/android/scan/LightGbmParityTest.kt` | 217 | Unit tests checking synthetic feature vector inference against Kotlin model. |
| `android/app/src/test/java/org/privacyguard/android/shield/ShieldProbeTest.kt` | 103 | Unit tests verifying shield telemetry parsing and network leak detection. |
| `android/app/src/test/java/org/privacyguard/android/shizuku/Chain9GatingTest.kt` | 107 | Unit tests verifying capability gating when chain9 is unsupported. |
| `android/app/src/test/java/org/privacyguard/android/shizuku/ComponentStateGatingTest.kt` | 100 | Unit tests verifying component enable/disable restrictions under shell. |
| `android/app/src/test/java/org/privacyguard/android/shizuku/IsolationPriorsTest.kt` | 454 | Unit tests validating prior state preservation during isolation rollback. |
| `android/app/src/test/java/org/privacyguard/android/shizuku/PrivilegeBackendTest.kt` | 71 | Unit tests verifying PrivilegeBackend interface delegation and allowlisting. |
| `android/app/src/test/java/org/privacyguard/android/shizuku/ShellOpsSnapshotTest.kt` | 47 | Unit tests validating JSON serialization and parsing of policy snapshots. |
| `android/app/src/test/java/org/privacyguard/android/shizuku/ShizukuShellServiceTest.kt` | 208 | Unit tests checking ShizukuShellService command allowlist validation. |
| `android/app/src/test/java/org/privacyguard/android/work/CrashWatchdogParsingTest.kt` | 192 | Unit tests verifying dumpsys exit-info crash parsing across hardware fixtures. |
| `android/app/src/test/resources/fixtures/moto_g54_sdk35_appops_run_any.txt` | 22 | Raw device fixture of appops get RUN_ANY_IN_BACKGROUND on Moto G54. |
| `android/app/src/test/resources/fixtures/moto_g54_sdk35_appops_run_any_foreground.txt` | 1 | Raw device fixture of foreground appops state on Moto G54. |
| `android/app/src/test/resources/fixtures/moto_g54_sdk35_exit_info.txt` | 77 | Raw device fixture of historical process exit reasons on Moto G54. |
| `android/app/src/test/resources/fixtures/moto_g54_sdk35_exit_info_crash.txt` | 68 | Raw device fixture of crash process exit info on Moto G54. |
| `android/app/src/test/resources/fixtures/moto_g54_sdk35_standby_bucket.txt` | 105 | Raw device fixture of standby buckets on Moto G54. |
| `android/build.gradle.kts` | 8 | Root project build configuration registering plugins and buildscript dependencies. |
| `android/gradle.properties` | 4 | Gradle JVM memory and parallel build properties. |
| `android/gradle/libs.versions.toml` | 73 | Version catalog pinning all AndroidX, Kotlin, Room, Shizuku, and build plugins. |
| `android/gradle/wrapper/gradle-wrapper.jar` | 310 | Gradle wrapper bootstrap binary. |
| `android/gradle/wrapper/gradle-wrapper.properties` | 7 | Gradle wrapper configuration pointing to Gradle 8.9 distribution. |
| `android/gradlew` | 252 | Unix shell script invoking Gradle wrapper. |
| `android/gradlew.bat` | 94 | Windows batch script invoking Gradle wrapper. |
| `android/settings.gradle.kts` | 23 | Settings script defining root project name and repository resolution rules. |
| `docs/DECISIONS.md` | 15 | Chronological log of architectural decisions, owner choices, and hardware measurements. |
| `docs/ENVIRONMENT.md` | 75 | Hardware, software, privilege, and test environment specifications. |
| `docs/STATUS.md` | 54 | Status log distinguishing proven hardware capabilities from unproven hypotheses. |
| `docs/VISION.md` | 44 | Product vision, scope, proven features, and planned future evolution. |
| `docs/WORKING_RULES.md` | 52 | Mandatory engineering rules, safety invariants, and verification protocols. |
| `docs/architecture.md` | 39 | Detailed technical architecture specification for scanning, OSINT, and Shizuku. |
| `docs/experiments/m3.5-g/device_facts.txt` | 5 | Raw device capabilities captured during M3.5-G hardware experiments. |
| `docs/experiments/m3.5-g/help_appops.txt` | 25 | Raw device capture of cmd appops help output. |
| `docs/experiments/m3.5-g/help_connectivity.txt` | 22 | Raw device capture of cmd connectivity help output. |
| `docs/experiments/m3.5-g/matrix.csv` | 49 | Hardware execution matrix results for shell privileged primitives. |
| `implementation_plan.md` | 260 | Architectural implementation plans, dependency proposals, and risk evaluations. |
| `walkthrough.md` | 294 | Factual audit log of executed milestone tasks, commits, and verification outputs. |

## 3. Key Architectural Signatures

### `android/app/src/main/aidl/org/privacyguard/android/scan/IScanService.aidl`
```aidl
interface IScanService {
    NativeScanResult scanApkFd(in ParcelFileDescriptor pfd);
}
```

### `android/app/src/main/aidl/org/privacyguard/android/scan/NativeScanResult.aidl`
```aidl
parcelable NativeScanResult;
```

### `android/app/src/main/aidl/org/privacyguard/android/service/IShellService.aidl`
```aidl
interface IShellService {
    ShellResult exec(in String[] cmd) = 1;
    void destroy() = 16777114;
}
```

### `android/app/src/main/aidl/org/privacyguard/android/service/ShellResult.aidl`
```aidl
parcelable ShellResult;
```

### `android/app/src/main/cpp/aho_corasick.hpp`
```cpp
class AhoCorasick {
public:
    void build(const std::vector<std::string>& patterns);
    std::vector<int> search(const char* data, size_t len) const;
};
```

### `android/app/src/main/cpp/apk_mmap.hpp`
```cpp
class MmapEntry {
public:
    bool open_entry(int fd, off_t offset, size_t size);
    void madvise_sequential();
    void unmap();
    const uint8_t* data() const;
    size_t size() const;
};
```

### `android/app/src/main/cpp/lgbm_evaluator.hpp`
```cpp
struct EvalResult {
    double riskScore;
    double maxConfidence;
};
class LightGbmEvaluator {
public:
    bool loadModel(const uint8_t* buffer, size_t size);
    EvalResult evaluate(const std::vector<float>& features) const;
};
```

### `android/app/src/main/cpp/scanner_jni.cpp`
```cpp
extern "C" {
JNIEXPORT jboolean JNICALL Java_org_privacyguard_android_scan_ScanService_nativeInitEngine(JNIEnv* env, jobject thiz, jbyteArray acBytes, jbyteArray modelBytes);
JNIEXPORT jobject JNICALL Java_org_privacyguard_android_scan_ScanService_nativeScanApkFd(JNIEnv* env, jobject thiz, jint fd);
}
```

### `android/app/src/main/java/org/privacyguard/android/PrivacyGuardApp.kt`
```kotlin
class PrivacyGuardApp : Application() {
    override fun onCreate()
}
```

### `android/app/src/main/java/org/privacyguard/android/crypto/Ed25519Verifier.kt`
```kotlin
object Ed25519Verifier {
    fun verify(data: ByteArray, signature: ByteArray): Boolean
}
```

### `android/app/src/main/java/org/privacyguard/android/data/AppDatabase.kt`
```kotlin
abstract class AppDatabase : RoomDatabase() {
    abstract fun appScanResultDao(): AppScanResultDao
    abstract fun policyOpDao(): PolicyOpDao
    abstract fun capabilityDao(): CapabilityDao
    abstract fun reconcileRequestDao(): ReconcileRequestDao
    abstract fun scanFailedDao(): ScanFailedDao
    abstract fun syncStateDao(): SyncStateDao
    companion object { fun getInstance(context: Context): AppDatabase }
}
```

### `android/app/src/main/java/org/privacyguard/android/osint/OllamaClient.kt`
```kotlin
class OllamaClient(private val client: OkHttpClient = OkHttpClient()) {
    suspend fun classify(packageName: String, certSha256: String, dangerousPermissions: List<String>, detectedTrackers: List<String>): ClassificationResult
}
```

### `android/app/src/main/java/org/privacyguard/android/osint/OsintClassifierWorker.kt`
```kotlin
class OsintClassifierWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result
}
```

### `android/app/src/main/java/org/privacyguard/android/scan/ScanOrchestrator.kt`
```kotlin
class ScanOrchestrator(private val context: Context, private val scanFailedDao: ScanFailedDao) {
    suspend fun scanPackageWithSplits(pkg: String, versionCode: Long, baseApkPath: String, splitApkPaths: List<String>): NativeScanResult
}
```

### `android/app/src/main/java/org/privacyguard/android/scan/ScanService.kt`
```kotlin
class ScanService : Service() {
    private external fun nativeInitEngine(trackerAcBytes: ByteArray, modelBytes: ByteArray): Boolean
    private external fun nativeScanApkFd(fd: Int): NativeScanResult?
    override fun onBind(intent: Intent?): IBinder
}
```

### `android/app/src/main/java/org/privacyguard/android/scan/tier1a/Tier1AScanner.kt`
```kotlin
class Tier1AScanner(private val pm: PackageManager) {
    fun scanInstalledPackage(pkg: String): Tier1Result?
}
```

### `android/app/src/main/java/org/privacyguard/android/shield/ShieldProbe.kt`
```kotlin
class ShieldProbe(private val context: Context) {
    suspend fun probe(): ShieldReport
}
```

### `android/app/src/main/java/org/privacyguard/android/shizuku/PrivilegeBackend.kt`
```kotlin
interface PrivilegeBackend {
    val id: String
    fun exec(cmd: Array<String>): ShellResult
}
class ShizukuBackend(private val shellService: IShellService) : PrivilegeBackend {
    override val id: String
    override fun exec(cmd: Array<String>): ShellResult
}
```

### `android/app/src/main/java/org/privacyguard/android/shizuku/ShellOps.kt`
```kotlin
object ShellOps {
    suspend fun probeCapabilities(backend: PrivilegeBackend): Map<String, Boolean>
    suspend fun isolatePackageNetworking(backend: PrivilegeBackend, pkg: String, uid: Int, desired: Boolean): IsolationOutcome
    suspend fun rollbackNetworkingIsolation(backend: PrivilegeBackend, pkg: String, uid: Int, snapshotJson: String?): RollbackOutcome
    suspend fun setStandbyBucket(backend: PrivilegeBackend, pkg: String, bucket: Int): ShellResult
    suspend fun setAppOp(backend: PrivilegeBackend, uid: Int, op: String, mode: String): ShellResult
}
```

### `android/app/src/main/java/org/privacyguard/android/shizuku/ShizukuManager.kt`
```kotlin
class ShizukuManager private constructor(private val context: Context) {
    fun init()
    fun isAuthorized(): Boolean
    suspend fun <T> withService(block: suspend (IShellService) -> T): T
    suspend fun exec(cmd: Array<String>): ShellResult
    companion object { fun getInstance(context: Context): ShizukuManager }
}
```

### `android/app/src/main/java/org/privacyguard/android/shizuku/ShizukuShellService.kt`
```kotlin
class ShizukuShellService : IShellService.Stub() {
    override fun exec(cmd: Array<out String>?): ShellResult
    override fun destroy()
    companion object {
        fun validateCommand(cmd: Array<out String>?): String?
    }
}
```

### `android/app/src/main/java/org/privacyguard/android/trigger/PackageChangeReceiver.kt`
```kotlin
class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?)
}
```

### `android/app/src/main/java/org/privacyguard/android/trigger/PowerSaveReceiver.kt`
```kotlin
class PowerSaveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?)
}
```

### `android/app/src/main/java/org/privacyguard/android/trigger/ReconciliationCoordinator.kt`
```kotlin
class ReconciliationCoordinator(private val context: Context, ...) {
    suspend fun processPendingReconciliations()
    suspend fun reconcilePackage(pkg: String)
}
```

### `android/app/src/main/java/org/privacyguard/android/ui/PrivacyGuardViewModel.kt`
```kotlin
class PrivacyGuardViewModel(application: Application) : AndroidViewModel(application) {
    val uiState: StateFlow<UiState>
    fun refreshApps()
    fun applyPolicy(pkg: String, op: PolicyAction)
}
```

### `android/app/src/main/java/org/privacyguard/android/work/CrashWatchdogWorker.kt`
```kotlin
class CrashWatchdogWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result
}
```

### `android/app/src/main/java/org/privacyguard/android/work/ReconcileDrainWorker.kt`
```kotlin
class ReconcileDrainWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result
}
```
