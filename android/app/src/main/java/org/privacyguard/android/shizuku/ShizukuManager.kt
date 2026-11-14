package org.privacyguard.android.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.privacyguard.android.BuildConfig
import org.privacyguard.android.data.AppDatabase
import org.privacyguard.android.data.entity.PolicyOp
import org.privacyguard.android.service.IShellService
import org.privacyguard.android.work.CrashWatchdogWorker
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

class ShizukuManager private constructor(private val appContext: Context) {

    private val ioSingle = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + ioSingle)

    @Volatile
    private var shellService: IShellService? = null

    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    private var rebindAttempts = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Shizuku UserService connected")
            shellService = IShellService.Stub.asInterface(service)
            _isServiceBound.value = true
            rebindAttempts = 0

            scope.launch {
                probeAndCacheCapabilities()
                verifyAppliedOpsOnConnect()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Shizuku UserService disconnected")
            shellService = null
            _isServiceBound.value = false
            scheduleRebind()
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
        scheduleRebind()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Shizuku permission granted")
            checkAndBindUserService()
        }
    }

    fun init() {
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        if (Shizuku.pingBinder()) {
            checkAndBindUserService()
        }
    }

    fun isReady(): Boolean {
        return Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
                shellService != null
    }

    fun getService(): IShellService? = shellService

    private fun checkAndBindUserService() {
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
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
            scheduleRebind()
        }
    }

    private fun scheduleRebind() {
        scope.launch {
            rebindAttempts++
            val backoffMs = minOf(1000L shl minOf(rebindAttempts, 6), 60_000L)
            delay(backoffMs)
            checkAndBindUserService()
        }
    }

    private suspend fun probeAndCacheCapabilities() = withContext(ioSingle) {
        val service = shellService ?: return@withContext
        try {
            val capabilities = ShellOps.probeCapabilities(service)
            val db = AppDatabase.getInstance(appContext)
            db.capabilityDao().insertOrUpdateAll(capabilities)
        } catch (e: Throwable) {
            Log.e(TAG, "Capability probe failed", e)
        }
    }

    /**
     * Re-enqueue all APPLIED packages for drift check upon binder connection,
     * as runtime firewall chains reset across reboots.
     */
    private suspend fun verifyAppliedOpsOnConnect() = withContext(ioSingle) {
        val db = AppDatabase.getInstance(appContext)
        val appliedOps = db.policyOpDao().getByStatus(PolicyOp.STATUS_APPLIED)
        val now = System.currentTimeMillis()
        for (op in appliedOps) {
            db.reconcileRequestDao().upsertRequest(op.pkg, now)
        }
    }

    /**
     * Executes a policy operation strictly on the single-thread serialized executor
     * with live package version verification, accurate gen tracking, and error classification.
     */
    suspend fun executePolicyOp(opId: Long): Boolean = withContext(ioSingle) {
        if (!isReady()) {
            return@withContext false
        }
        val service = shellService ?: return@withContext false
        val db = AppDatabase.getInstance(appContext)
        val op = db.policyOpDao().getById(opId) ?: return@withContext false

        // Atomically claim op: updates status to APPLYING and increments gen
        val leaseUntilMs = System.currentTimeMillis() + 60_000L
        val claimed = db.policyOpDao().claimOp(op.id, op.gen, leaseUntilMs)
        if (claimed != 1) {
            return@withContext false
        }

        // Accurately track active generation after claim
        val activeGen = op.gen + 1

        // Live check: re-verify live (versionCode, lastUpdateTime)
        val pm = appContext.packageManager
        val livePkgInfo = try {
            pm.getPackageInfo(op.pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            val rows = db.policyOpDao().markFailedPermanent(op.id, activeGen, "PACKAGE_NOT_FOUND", null)
            check(rows == 1) { "Failed updating op state" }
            return@withContext false
        }

        val liveVersionCode = livePkgInfo.longVersionCode
        val liveLastUpdate = livePkgInfo.lastUpdateTime

        if (liveVersionCode != op.versionCode || liveLastUpdate != op.lastUpdateTime) {
            // Package updated or drifted -> mark SUPERSEDED and re-enqueue reconcile request
            val rows = db.policyOpDao().markDriftSuperseded(op.id, activeGen, "Live versionCode=$liveVersionCode, lastUpdateTime=$liveLastUpdate")
            check(rows == 1) { "Failed updating op state to SUPERSEDED" }
            db.reconcileRequestDao().upsertRequest(op.pkg, System.currentTimeMillis())
            return@withContext false
        }

        try {
            when (op.op) {
                "isolate_wifi" -> {
                    val result = ShellOps.isolatePackageNetworking(appContext, service, op.pkg)
                    if (result == ShellOps.IsolationResult.UNISOLATABLE_ON_WIFI) {
                        val rows = db.policyOpDao().markFailedPermanent(op.id, activeGen, "UNISOLATABLE_ON_WIFI", result.statusString)
                        check(rows == 1)
                    } else {
                        val rows = db.policyOpDao().markApplied(op.id, activeGen, result.statusString, null)
                        check(rows == 1)
                    }
                }

                "disable_components" -> {
                    val targetComponents = op.target.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val appUid = livePkgInfo.applicationInfo?.uid ?: 0
                    val userId = appUid / 100000

                    // 1. Create pre-mutation snapshot and persist before shell execution
                    val snapshotJson = ShellOps.createPreMutationSnapshot(appContext, op.pkg, targetComponents)

                    // 2. Record appliedAt timestamp and schedule durable 60s crash watchdog
                    val appliedAt = System.currentTimeMillis()
                    scheduleCrashWatchdog(op.id, appliedAt)

                    // 3. Mutate components via shell
                    ShellOps.disableManifestComponents(appContext, service, op.pkg, targetComponents)

                    // 4. Terminate process cleanly via am kill --user $userId
                    ShellOps.killProcessSafely(service, op.pkg, userId)

                    val rows = db.policyOpDao().markApplied(op.id, activeGen, "Disabled ${targetComponents.size} components", snapshotJson)
                    check(rows == 1)
                }

                else -> {
                    val rows = db.policyOpDao().markFailedPermanent(op.id, activeGen, "UNSUPPORTED_OP", null)
                    check(rows == 1)
                }
            }
            return@withContext true
        } catch (e: SecurityException) {
            val rows = db.policyOpDao().markFailedPermanent(op.id, activeGen, "SECURITY_EXCEPTION: ${e.message}", null)
            check(rows == 1)
            return@withContext false
        } catch (e: IllegalArgumentException) {
            val rows = db.policyOpDao().markFailedPermanent(op.id, activeGen, "ILLEGAL_ARGUMENT: ${e.message}", null)
            check(rows == 1)
            return@withContext false
        } catch (e: DeadObjectException) {
            handleTransientFailure(db, op.id, activeGen, op.attempts, "DEAD_OBJECT")
            return@withContext false
        } catch (e: RemoteException) {
            handleTransientFailure(db, op.id, activeGen, op.attempts, "REMOTE_EXCEPTION: ${e.message}")
            return@withContext false
        } catch (t: Throwable) {
            handleTransientFailure(db, op.id, activeGen, op.attempts, "${t.javaClass.simpleName}: ${t.message}")
            return@withContext false
        }
    }

    private suspend fun handleTransientFailure(
        db: AppDatabase,
        opId: Long,
        activeGen: Long,
        currentAttempts: Int,
        errClass: String
    ) {
        val nextAttempt = currentAttempts + 1
        if (nextAttempt >= 5) {
            val rows = db.policyOpDao().markFailedPermanent(opId, activeGen, errClass, null)
            check(rows == 1)
        } else {
            val backoffMs = minOf(30_000L shl currentAttempts, 3_600_000L)
            val nextAt = System.currentTimeMillis() + backoffMs
            val rows = db.policyOpDao().markFailedTransient(opId, activeGen, errClass, nextAt)
            check(rows == 1)
        }
    }

    private fun scheduleCrashWatchdog(policyOpId: Long, appliedAt: Long) {
        val inputData = Data.Builder()
            .putLong(CrashWatchdogWorker.KEY_POLICY_OP_ID, policyOpId)
            .putLong(CrashWatchdogWorker.KEY_APPLIED_AT, appliedAt)
            .build()

        val watchdogRequest = OneTimeWorkRequestBuilder<CrashWatchdogWorker>()
            .setInitialDelay(60, TimeUnit.SECONDS)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "watchdog:$policyOpId",
            ExistingWorkPolicy.KEEP,
            watchdogRequest
        )
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
