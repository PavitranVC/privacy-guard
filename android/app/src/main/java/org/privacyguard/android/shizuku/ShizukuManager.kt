package org.privacyguard.android.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.privacyguard.android.BuildConfig
import org.privacyguard.android.data.AppDatabase
import org.privacyguard.android.data.entity.PolicyOp
import org.privacyguard.android.service.IShellService
import org.privacyguard.android.work.CrashWatchdogWorker
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

class ShizukuManager private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val mutex = Mutex()

    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    private var shellService: IShellService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Shizuku UserService connected: $name")
            shellService = IShellService.Stub.asInterface(service)
            _isServiceBound.value = true

            // Trigger capability probing on connect
            scope.launch {
                probeAndCacheCapabilities()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Shizuku UserService disconnected")
            shellService = null
            _isServiceBound.value = false
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        checkAndBindUserService()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        _isServiceBound.value = false
        shellService = null
    }

    fun init() {
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        if (Shizuku.pingBinder()) {
            checkAndBindUserService()
        }
    }

    fun isReady(): Boolean {
        return Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
                shellService != null
    }

    private fun checkAndBindUserService() {
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Shizuku permission not granted")
            return
        }

        val componentName = ComponentName(appContext, ShizukuShellService::class.java)
        val args = Shizuku.UserServiceArgs(componentName)
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(1)

        try {
            Shizuku.bindUserService(args, serviceConnection)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed binding Shizuku UserService", e)
        }
    }

    private suspend fun probeAndCacheCapabilities() = mutex.withLock {
        val service = shellService ?: return@withLock
        try {
            val capabilities = ShellOps.probeCapabilities(service)
            val db = AppDatabase.getInstance(appContext)
            db.capabilityDao().insertOrUpdateAll(capabilities)
            Log.i(TAG, "Probed and cached ${capabilities.size} capabilities")
        } catch (e: Throwable) {
            Log.e(TAG, "Capability probe failed", e)
        }
    }

    /**
     * Executes a policy operation strictly on the single-thread serialized executor
     * with live version/update time verification and optimistic locking.
     */
    suspend fun executePolicyOp(opId: Long): Boolean = mutex.withLock {
        if (!isReady()) {
            Log.w(TAG, "Cannot execute policy op $opId: Shizuku not ready")
            return@withLock false
        }
        val service = shellService ?: return@withLock false
        val db = AppDatabase.getInstance(appContext)
        val op = db.policyOpDao().getById(opId) ?: return@withLock false

        // Optimistic locking: mark applying
        val locked = db.policyOpDao().markApplyingIfGenMatches(op.id, op.gen)
        if (!locked) {
            Log.w(TAG, "Optimistic lock failed for policy op $opId; aborting mutation")
            return@withLock false
        }

        // Live check: re-verify live (versionCode, lastUpdateTime) before mutating
        val pm = appContext.packageManager
        val livePkgInfo = try {
            pm.getPackageInfo(op.pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Target package ${op.pkg} no longer installed")
            db.policyOpDao().updateOptimistic(
                id = op.id,
                expectedGen = op.gen + 1,
                newStatus = PolicyOp.STATUS_FAILED_PERMANENT,
                observed = null,
                errClass = "PACKAGE_NOT_FOUND",
                attempts = op.attempts + 1,
                nextAt = 0,
                snapshot = null
            )
            return@withLock false
        }

        val liveVersionCode = livePkgInfo.longVersionCode
        val liveLastUpdate = livePkgInfo.lastUpdateTime

        if (liveVersionCode != op.versionCode || liveLastUpdate != op.lastUpdateTime) {
            Log.w(TAG, "Package drift detected for ${op.pkg} (version/timestamp mismatch)")
            db.policyOpDao().updateOptimistic(
                id = op.id,
                expectedGen = op.gen + 1,
                newStatus = PolicyOp.STATUS_DRIFT,
                observed = "Live versionCode=$liveVersionCode, lastUpdateTime=$liveLastUpdate",
                errClass = "PACKAGE_DRIFT",
                attempts = op.attempts + 1,
                nextAt = 0,
                snapshot = null
            )
            return@withLock false
        }

        try {
            when (op.op) {
                "isolate_wifi" -> {
                    val result = ShellOps.isolatePackageNetworking(appContext, service, op.pkg)
                    val status = if (result == ShellOps.IsolationResult.UNISOLATABLE_ON_WIFI) {
                        PolicyOp.STATUS_FAILED_PERMANENT
                    } else {
                        PolicyOp.STATUS_APPLIED
                    }
                    db.policyOpDao().updateOptimistic(
                        id = op.id,
                        expectedGen = op.gen + 1,
                        newStatus = status,
                        observed = result.statusString,
                        errClass = null,
                        attempts = op.attempts + 1,
                        nextAt = 0,
                        snapshot = null
                    )
                }

                "disable_components" -> {
                    val targetComponents = op.target.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val snapshotJson = ShellOps.disableManifestComponents(
                        context = appContext,
                        shellService = service,
                        pkg = op.pkg,
                        targetComponents = targetComponents
                    )

                    // Safely kill target package process
                    ShellOps.killProcessSafely(service, op.pkg)

                    db.policyOpDao().updateOptimistic(
                        id = op.id,
                        expectedGen = op.gen + 1,
                        newStatus = PolicyOp.STATUS_APPLIED,
                        observed = "Disabled components: ${targetComponents.size}",
                        errClass = null,
                        attempts = op.attempts + 1,
                        nextAt = 0,
                        snapshot = snapshotJson
                    )

                    // Schedule 60-second durable crash watchdog via WorkManager
                    scheduleCrashWatchdog(op.id, op.pkg, snapshotJson)
                }

                else -> {
                    db.policyOpDao().updateOptimistic(
                        id = op.id,
                        expectedGen = op.gen + 1,
                        newStatus = PolicyOp.STATUS_FAILED_PERMANENT,
                        observed = null,
                        errClass = "UNSUPPORTED_OP",
                        attempts = op.attempts + 1,
                        nextAt = 0,
                        snapshot = null
                    )
                }
            }
            return@withLock true
        } catch (t: Throwable) {
            Log.e(TAG, "Error executing policy op $opId", t)
            db.policyOpDao().updateOptimistic(
                id = op.id,
                expectedGen = op.gen + 1,
                newStatus = PolicyOp.STATUS_FAILED_TRANSIENT,
                observed = null,
                errClass = t.javaClass.simpleName + ": " + t.message,
                attempts = op.attempts + 1,
                nextAt = System.currentTimeMillis() + 30_000L,
                snapshot = null
            )
            return@withLock false
        }
    }

    private fun scheduleCrashWatchdog(policyOpId: Long, pkg: String, snapshotJson: String) {
        val inputData = Data.Builder()
            .putLong(CrashWatchdogWorker.KEY_POLICY_OP_ID, policyOpId)
            .putString(CrashWatchdogWorker.KEY_PKG, pkg)
            .putLong(CrashWatchdogWorker.KEY_APPLIED_AT, System.currentTimeMillis())
            .putString(CrashWatchdogWorker.KEY_SNAPSHOT, snapshotJson)
            .build()

        val watchdogRequest = OneTimeWorkRequestBuilder<CrashWatchdogWorker>()
            .setInitialDelay(60, TimeUnit.SECONDS)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(appContext).enqueue(watchdogRequest)
    }

    companion object {
        private const val TAG = "ShizukuManager"

        @Volatile
        private var INSTANCE: ShizukuManager? = null

        fun getInstance(context: Context): ShizukuManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ShizukuManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
