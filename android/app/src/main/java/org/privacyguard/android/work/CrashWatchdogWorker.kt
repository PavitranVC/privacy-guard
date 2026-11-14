package org.privacyguard.android.work

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.privacyguard.android.data.AppDatabase
import org.privacyguard.android.data.entity.PolicyOp

class CrashWatchdogWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val policyOpId = inputData.getLong(KEY_POLICY_OP_ID, -1L)
        val pkg = inputData.getString(KEY_PKG) ?: return Result.failure()
        val appliedAt = inputData.getLong(KEY_APPLIED_AT, System.currentTimeMillis() - 60_000L)
        val snapshotJson = inputData.getString(KEY_SNAPSHOT)

        if (policyOpId == -1L) return Result.failure()

        val db = AppDatabase.getInstance(applicationContext)
        val op = db.policyOpDao().getById(policyOpId) ?: return Result.success()

        // Only inspect if still in an applied/applying state
        if (op.status != PolicyOp.STATUS_APPLIED && op.status != PolicyOp.STATUS_APPLYING) {
            return Result.success()
        }

        val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        var crashDetected = false
        var crashDescription = ""

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activityManager != null) {
            try {
                val exitReasons = activityManager.getHistoricalProcessExitReasons(pkg, 0, 15)
                for (info in exitReasons) {
                    if (info.timestamp >= appliedAt) {
                        if (info.reason == ApplicationExitInfo.REASON_CRASH ||
                            info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                        ) {
                            crashDetected = true
                            crashDescription = "Crash detected: reason=${info.reason}, desc=${info.description}"
                            break
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed reading process exit reasons for $pkg", e)
            }
        }

        if (crashDetected) {
            Log.w(TAG, "Unsafe crash detected post-apply for $pkg: $crashDescription. Auto-rolling back.")
            // Auto-restore snapshot
            restoreSnapshot(pkg, snapshotJson)

            db.policyOpDao().updateOptimistic(
                id = op.id,
                expectedGen = op.gen,
                newStatus = PolicyOp.STATUS_ROLLED_BACK_UNSAFE,
                observed = crashDescription,
                errClass = "CRASH_60S_POST_APPLY",
                attempts = op.attempts,
                nextAt = 0,
                snapshot = op.snapshot
            )
        }

        return Result.success()
    }

    private fun restoreSnapshot(pkg: String, snapshotJson: String?) {
        if (snapshotJson.isNullOrBlank()) return
        try {
            val jsonMap = Json.parseToJsonElement(snapshotJson).jsonObject
            val pm = applicationContext.packageManager
            for ((componentNameStr, stateElem) in jsonMap) {
                val state = stateElem.jsonPrimitive.content.toIntOrNull() ?: continue
                val component = ComponentName(pkg, componentNameStr)
                pm.setComponentEnabledSetting(
                    component,
                    state,
                    PackageManager.DONT_KILL_APP
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed restoring snapshot for $pkg", e)
        }
    }

    companion object {
        const val TAG = "CrashWatchdogWorker"
        const val KEY_POLICY_OP_ID = "key_policy_op_id"
        const val KEY_PKG = "key_pkg"
        const val KEY_APPLIED_AT = "key_applied_at"
        const val KEY_SNAPSHOT = "key_snapshot"
    }
}
