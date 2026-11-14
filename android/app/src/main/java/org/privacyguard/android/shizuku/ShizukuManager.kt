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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.privacyguard.android.BuildConfig
import org.privacyguard.android.data.AppDatabase
import org.privacyguard.android.data.entity.PolicyOp
import org.privacyguard.android.service.IShellService
import org.privacyguard.android.service.ShellResult
import org.privacyguard.android.work.CrashWatchdogWorker
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ShizukuManager private constructor(private val appContext: Context) {

    // Single-thread serialized executor for all Shizuku Binder mutations
    private val singleThreadExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PrivacyGuard-Shizuku-Executor")
    }
    val ioSingle: CoroutineDispatcher = singleThreadExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(
        SupervisorJob() + ioSingle + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "Uncaught in ShizukuManager scope", t)
        }
    )

    @Volatile
    private var shellService: IShellService? = null

    @Volatile
    private var isBinding = false

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShizukuShellService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shizuku")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Shizuku UserService connected")
            shellService = IShellService.Stub.asInterface(service)
            isBinding = false

            scope.launch {
                // Probe capabilities on connect
                val serviceRef = shellService
                if (serviceRef != null) {
                    val caps = ShellOps.probeCapabilities(serviceRef)
                    val db = AppDatabase.getInstance(appContext)
                    db.capabilityDao().upsertAll(caps)
                }

                // Verify live state against applied ops to detect reboot resets or drift
                verifyDriftOnConnect()
                drainRunnableOps()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Shizuku UserService disconnected")
            shellService = null
            isBinding = false
            scope.launch {
                delay(2000L)
                checkAndBindUserService()
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkAndBindUserService()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shellService = null
        isBinding = false
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            checkAndBindUserService()
        }
    }

    fun init() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        checkAndBindUserService()
    }

    fun isReady(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
                    shellService != null &&
                    shellService?.asBinder()?.isBinderAlive == true
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission(requestCode: Int) {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(requestCode)
        }
    }

    private fun checkAndBindUserService() {
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return

        if (shellService == null && !isBinding) {
            isBinding = true
            scope.launch {
                delay(10_000L)
                if (isBinding && shellService == null) {
                    Log.w(TAG, "UserService binding timed out after 10s; resetting isBinding flag")
                    isBinding = false
                }
            }
            try {
                Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            } catch (t: Throwable) {
                isBinding = false
                Log.e(TAG, "Failed binding Shizuku UserService", t)
            }
        }
    }

    suspend fun exec(cmd: Array<out String>): ShellResult = withContext(ioSingle) {
        val service = shellService ?: return@withContext ShellResult(-1, "", "Service not connected")
        service.exec(cmd)
    }

    suspend fun drainRunnableOps() = withContext(ioSingle) {
        if (!isReady()) return@withContext
        val db = AppDatabase.getInstance(appContext)
        val now = System.currentTimeMillis()
        val runnable = db.policyOpDao().getRunnableOps(now, limit = 20)
        for (op in runnable) {
            executePolicyOp(op.id)
        }
    }

    private suspend fun verifyDriftOnConnect() = withContext(ioSingle) {
        val db = AppDatabase.getInstance(appContext)
        val appliedOps = db.policyOpDao().getByStatus(PolicyOp.STATUS_APPLIED)
        val now = System.currentTimeMillis()
        for (op in appliedOps) {
            db.reconcileRequestDao().upsertRequestWithSeq(op.pkg, now, 0L)
        }
    }

    suspend fun executePolicyOp(opId: Long): Boolean = withContext(ioSingle) {
        if (!isReady()) return@withContext false
        val service = shellService ?: return@withContext false
        val db = AppDatabase.getInstance(appContext)
        val op = db.policyOpDao().getById(opId) ?: return@withContext false

        val leaseUntilMs = System.currentTimeMillis() + 60_000L
        val claimed = db.policyOpDao().claimOp(op.id, op.gen, leaseUntilMs, System.currentTimeMillis())
        if (claimed != 1) return@withContext false

        val activeGen = op.gen + 1
        val pm = appContext.packageManager
        val livePkgInfo = try {
            pm.getPackageInfo(op.pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            withContext(NonCancellable) {
                db.policyOpDao().markFailedPermanent(op.id, activeGen, "PACKAGE_NOT_FOUND")
            }
            return@withContext false
        }

        val liveVersionCode = livePkgInfo.longVersionCode
        val liveLastUpdate = livePkgInfo.lastUpdateTime

        if (liveVersionCode != op.versionCode || liveLastUpdate != op.lastUpdateTime) {
            withContext(NonCancellable) {
                db.policyOpDao().resetForNewVersion(op.id, liveVersionCode, liveLastUpdate)
                db.reconcileRequestDao().upsertRequestWithSeq(op.pkg, System.currentTimeMillis(), 0L)
            }
            return@withContext false
        }

        var opSucceeded = false
        var rowsUpdated = 0

        try {
            when (op.op) {
                "isolate_wifi" -> {
                    val result = ShellOps.isolatePackageNetworking(appContext, service, op.pkg, db.capabilityDao())
                    withContext(NonCancellable) {
                        rowsUpdated = when (result) {
                            ShellOps.IsolationResult.UNISOLATABLE_ON_WIFI ->
                                db.policyOpDao().markFailedPermanent(op.id, activeGen, result.statusString)
                            ShellOps.IsolationResult.APPOPS_PARTIAL -> {
                                opSucceeded = true
                                db.policyOpDao().markAppliedPartial(op.id, activeGen, result.statusString)
                            }
                            else -> {
                                opSucceeded = true
                                db.policyOpDao().markApplied(op.id, activeGen, result.statusString)
                            }
                        }
                        opSucceeded = opSucceeded && (rowsUpdated == 1)
                    }
                }

                "disable_components" -> {
                    val targetComponents = op.target.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val appUid = livePkgInfo.applicationInfo?.uid ?: 0
                    val userId = appUid / 100000

                    val snapshotJson = ShellOps.createPreMutationSnapshot(appContext, op.pkg, targetComponents)
                    if (snapshotJson.isBlank() || snapshotJson == "{}") {
                        withContext(NonCancellable) {
                            handleTransientFailure(db, op.id, activeGen, op.attempts, "SNAPSHOT_EMPTY_OR_INVALID")
                        }
                        return@withContext false
                    }

                    val savedRows = withContext(NonCancellable) {
                        db.policyOpDao().saveSnapshot(op.id, activeGen, snapshotJson)
                    }
                    if (savedRows != 1) {
                        withContext(NonCancellable) {
                            handleTransientFailure(db, op.id, activeGen, op.attempts, "SNAPSHOT_NOT_PERSISTED")
                        }
                        return@withContext false
                    }

                    val appliedAt = System.currentTimeMillis()
                    scheduleCrashWatchdog(op.id, appliedAt)

                    ShellOps.disableManifestComponents(appContext, service, op.pkg, targetComponents)
                    ShellOps.killProcessSafely(service, op.pkg, userId)

                    withContext(NonCancellable) {
                        rowsUpdated = db.policyOpDao().markApplied(op.id, activeGen, "Disabled ${targetComponents.size} components")
                        opSucceeded = (rowsUpdated == 1)
                    }
                }

                else -> {
                    withContext(NonCancellable) {
                        rowsUpdated = db.policyOpDao().markFailedPermanent(op.id, activeGen, "UNSUPPORTED_OP")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            withContext(NonCancellable) {
                rowsUpdated = db.policyOpDao().markFailedPermanent(op.id, activeGen, "SECURITY_EXCEPTION: ${e.message}")
            }
        } catch (e: IllegalArgumentException) {
            withContext(NonCancellable) {
                rowsUpdated = db.policyOpDao().markFailedPermanent(op.id, activeGen, "ILLEGAL_ARGUMENT: ${e.message}")
            }
        } catch (e: DeadObjectException) {
            withContext(NonCancellable) {
                handleTransientFailure(db, op.id, activeGen, op.attempts, "DEAD_OBJECT")
            }
        } catch (e: RemoteException) {
            withContext(NonCancellable) {
                handleTransientFailure(db, op.id, activeGen, op.attempts, "REMOTE_EXCEPTION: ${e.message}")
            }
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                handleTransientFailure(db, op.id, activeGen, op.attempts, "${t.javaClass.simpleName}: ${t.message}")
            }
        }

        return@withContext opSucceeded
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
            db.policyOpDao().markFailedPermanent(opId, activeGen, errClass)
        } else {
            val backoffMs = minOf(30_000L shl currentAttempts, 3_600_000L)
            val nextAt = System.currentTimeMillis() + backoffMs
            db.policyOpDao().markFailedTransient(opId, activeGen, errClass, nextAt)
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
            ExistingWorkPolicy.REPLACE,
            watchdogRequest
        )
    }

    suspend fun rollbackPackage(pkg: String): Boolean = withContext(ioSingle) {
        if (!isReady()) return@withContext false
        val db = AppDatabase.getInstance(appContext)
        val ops = db.policyOpDao().getByPackage(pkg)
        val pm = appContext.packageManager
        val appInfo = try {
            pm.getApplicationInfo(pkg, 0)
        } catch (_: Throwable) {
            return@withContext false
        }
        val userId = appInfo.uid / 100000

        val eligibleStatuses = setOf(
            PolicyOp.STATUS_APPLIED,
            PolicyOp.STATUS_APPLIED_PARTIAL,
            PolicyOp.STATUS_DRIFT,
            PolicyOp.STATUS_APPLYING,
            PolicyOp.STATUS_FAILED_TRANSIENT,
            PolicyOp.STATUS_PENDING
        )
        val opsToRollback = ops.filter { it.status in eligibleStatuses }.sortedByDescending { it.id }

        var overallSuccess = true
        for (op in opsToRollback) {
            if (op.snapshot.isNullOrBlank() || op.snapshot == "{}") {
                if (op.status in setOf(
                        PolicyOp.STATUS_PENDING,
                        PolicyOp.STATUS_FAILED_TRANSIENT,
                        PolicyOp.STATUS_APPLYING
                    )
                ) {
                    val rows = db.policyOpDao().markSuperseded(op.id, op.gen)
                    if (rows != 1) {
                        overallSuccess = false
                    }
                    continue
                }

                if (op.op == ShellOps.OP_ISOLATE_WIFI) {
                    val observed = op.observed ?: ""
                    var reverted = false
                    try {
                        when {
                            observed.contains("CHAIN3") -> {
                                val r1 = exec(arrayOf("cmd", "connectivity", "set-chain3-enabled", "false"))
                                val r2 = exec(arrayOf("cmd", "connectivity", "set-package-networking-enabled", "true", pkg))
                                reverted = (r1.exitCode == 0 && r2.exitCode == 0)
                            }
                            observed.contains("CHAIN9") -> {
                                val r1 = exec(arrayOf("cmd", "connectivity", "oem_deny_3", "remove", appInfo.uid.toString()))
                                val r2 = exec(arrayOf("cmd", "connectivity", "set-package-networking-enabled", "true", pkg))
                                reverted = (r1.exitCode == 0 && r2.exitCode == 0)
                            }
                            observed.contains("APPOPS") -> {
                                val r1 = exec(arrayOf("cmd", "appops", "set", "--uid", appInfo.uid.toString(), "RUN_ANY_IN_BACKGROUND", "allow"))
                                val r2 = exec(arrayOf("am", "set-standby-bucket", "--user", userId.toString(), pkg, "active"))
                                reverted = (r1.exitCode == 0 && r2.exitCode == 0)
                            }
                            else -> {
                                // Fallback: attempt reversal of all three methods
                                val rNet = exec(arrayOf("cmd", "connectivity", "set-package-networking-enabled", "true", pkg))
                                val rAppOps = exec(arrayOf("cmd", "appops", "set", "--uid", appInfo.uid.toString(), "RUN_ANY_IN_BACKGROUND", "allow"))
                                val rBucket = exec(arrayOf("am", "set-standby-bucket", "--user", userId.toString(), pkg, "active"))
                                reverted = (rNet.exitCode == 0 && rAppOps.exitCode == 0 && rBucket.exitCode == 0)
                            }
                        }
                    } catch (_: Throwable) {
                        reverted = false
                    }

                    if (reverted) {
                        val rows = db.policyOpDao().markSuperseded(op.id, op.gen)
                        if (rows != 1) {
                            overallSuccess = false
                        }
                    } else {
                        overallSuccess = false
                    }
                } else {
                    overallSuccess = false
                }
                continue
            }

            try {
                val jsonMap = Json.parseToJsonElement(op.snapshot).jsonObject
                var opRestored = true
                for ((compNameStr, stateElem) in jsonMap) {
                    val state = stateElem.jsonPrimitive.intOrNull ?: 0
                    val componentName = ComponentName(pkg, compNameStr)
                    val flatName = componentName.flattenToString()

                    val cmd = when (state) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED ->
                            arrayOf("pm", "enable", "--user", userId.toString(), flatName)
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED ->
                            arrayOf("pm", "disable", "--user", userId.toString(), flatName)
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ->
                            arrayOf("pm", "disable-user", "--user", userId.toString(), flatName)
                        else ->
                            arrayOf("pm", "default-state", "--user", userId.toString(), flatName)
                    }

                    val res = exec(cmd)
                    val observed = pm.getComponentEnabledSetting(componentName)
                    val expectedState = if (state in 1..3) state else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    if (res.exitCode != 0 || observed != expectedState) {
                        opRestored = false
                    }
                }

                if (opRestored) {
                    val rows = db.policyOpDao().markSuperseded(op.id, op.gen)
                    if (rows != 1) {
                        overallSuccess = false
                    }
                } else {
                    overallSuccess = false
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed rolling back op ${op.id} for $pkg", t)
                overallSuccess = false
            }
        }

        overallSuccess
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
