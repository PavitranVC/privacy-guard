package org.privacyguard.android.work

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.privacyguard.android.data.AppDatabase
import org.privacyguard.android.data.entity.PolicyOp
import org.privacyguard.android.shizuku.ShizukuManager

class CrashWatchdogWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val policyOpId = inputData.getLong(KEY_POLICY_OP_ID, -1L)
        val appliedAt = inputData.getLong(KEY_APPLIED_AT, -1L)

        if (policyOpId == -1L || appliedAt == -1L) return Result.failure()

        val db = AppDatabase.getInstance(applicationContext)
        val op = db.policyOpDao().getById(policyOpId) ?: return Result.success()

        // Only inspect if still in APPLIED state
        if (op.status != PolicyOp.STATUS_APPLIED) {
            return Result.success()
        }

        val shizuku = ShizukuManager.getInstance(applicationContext)
        if (!shizuku.isReady()) {
            return Result.retry()
        }
        val shellService = shizuku.getService() ?: return Result.retry()

        // Query privileged exit reasons via dumpsys activity exit-info
        val dumpsysResult = shellService.exec(arrayOf("dumpsys", "activity", "exit-info", op.pkg))
        val dumpsysOut = dumpsysResult.stdout

        var crashDetected = false
        var crashDescription = ""

        if (dumpsysResult.exitCode == 0 && dumpsysOut.isNotBlank()) {
            val lines = dumpsysOut.lines()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("timestamp=")) {
                    val ts = trimmed.substringAfter("timestamp=").substringBefore(" ").toLongOrNull()
                    if (ts != null && ts >= appliedAt && ts <= appliedAt + 60_000L) {
                        // Check if reason is crash, native crash, ANR, or init failure
                        if (dumpsysOut.contains("reason=CRASH", ignoreCase = true) ||
                            dumpsysOut.contains("reason=USER REQUESTED", ignoreCase = false) ||
                            dumpsysOut.contains("reason=CRASH_NATIVE", ignoreCase = true) ||
                            dumpsysOut.contains("reason=ANR", ignoreCase = true) ||
                            dumpsysOut.contains("reason=INITIALIZATION FAILURE", ignoreCase = true)
                        ) {
                            crashDetected = true
                            crashDescription = "Crash observed post-apply: $trimmed"
                            break
                        }
                    }
                }
            }
        }

        if (crashDetected) {
            Log.w(TAG, "Process crash detected within 60s window. Initiating rollback.")
            val restored = restoreSnapshotViaShell(shellService, op.pkg, op.snapshot)
            if (restored) {
                db.policyOpDao().markRolledBackUnsafe(op.id, op.gen, crashDescription)
            } else {
                return Result.retry()
            }
        }

        return Result.success()
    }

    private fun restoreSnapshotViaShell(
        shellService: org.privacyguard.android.service.IShellService,
        pkg: String,
        snapshotJson: String?
    ): Boolean {
        if (snapshotJson.isNullOrBlank()) return true
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            val userId = appInfo.uid / 100000

            val jsonMap = Json.parseToJsonElement(snapshotJson).jsonObject
            for ((componentNameStr, _) in jsonMap) {
                val componentName = ComponentName(pkg, componentNameStr)
                val res = shellService.exec(arrayOf("pm", "default-state", "--user", userId.toString(), componentName.flattenToString()))
                if (res.exitCode != 0) {
                    shellService.exec(arrayOf("pm", "enable", "--user", userId.toString(), componentName.flattenToString()))
                }
            }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed restoring snapshot via shell", e)
            false
        }
    }

    companion object {
        private const val TAG = "CrashWatchdogWorker"
        const val KEY_POLICY_OP_ID = "key_policy_op_id"
        const val KEY_APPLIED_AT = "key_applied_at"
    }
}
