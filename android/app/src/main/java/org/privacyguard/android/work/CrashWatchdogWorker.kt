package org.privacyguard.android.work

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.privacyguard.android.data.AppDatabase
import org.privacyguard.android.data.entity.PolicyOp
import org.privacyguard.android.shizuku.ShizukuManager
import java.text.SimpleDateFormat
import java.util.Locale

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
        if (op.status != PolicyOp.STATUS_APPLIED && op.status != PolicyOp.STATUS_APPLIED_PARTIAL) {
            return Result.success()
        }

        if (runAttemptCount >= 5) {
            val freshOp = db.policyOpDao().getById(policyOpId)
            if (freshOp != null && (freshOp.status == PolicyOp.STATUS_APPLIED || freshOp.status == PolicyOp.STATUS_APPLIED_PARTIAL)) {
                Log.w(TAG, "Watchdog retries exhausted for ${freshOp.pkg}. Transitioning to DRIFT.")
                db.policyOpDao().markDriftPending(freshOp.id, freshOp.gen)
            }
            return Result.failure()
        }

        val shizuku = ShizukuManager.getInstance(applicationContext)
        if (!shizuku.isReady()) {
            if (runAttemptCount >= 4) {
                val freshOp = db.policyOpDao().getById(policyOpId)
                if (freshOp != null && (freshOp.status == PolicyOp.STATUS_APPLIED || freshOp.status == PolicyOp.STATUS_APPLIED_PARTIAL)) {
                    Log.w(TAG, "Watchdog retries exhausted while Shizuku unready for ${freshOp.pkg}. Transitioning to DRIFT.")
                    db.policyOpDao().markDriftPending(freshOp.id, freshOp.gen)
                }
                return Result.failure()
            }
            return Result.retry()
        }

        // Query privileged exit reasons via dumpsys activity exit-info
        val dumpsysResult = withContext(shizuku.ioSingle) {
            shizuku.exec(arrayOf("dumpsys", "activity", "exit-info", op.pkg))
        }

        if (dumpsysResult.exitCode != 0) {
            if (runAttemptCount >= 4) {
                val freshOp = db.policyOpDao().getById(policyOpId)
                if (freshOp != null && (freshOp.status == PolicyOp.STATUS_APPLIED || freshOp.status == PolicyOp.STATUS_APPLIED_PARTIAL)) {
                    Log.w(TAG, "Watchdog retries exhausted on dumpsys error for ${freshOp.pkg}. Transitioning to DRIFT.")
                    db.policyOpDao().markDriftPending(freshOp.id, freshOp.gen)
                }
                return Result.failure()
            }
            return Result.retry()
        }

        val crashDetected = hasCrashInWindow(dumpsysResult.stdout, appliedAt, 60_000L)

        if (crashDetected) {
            Log.w(TAG, "Process crash detected within 60s window for ${op.pkg}. Initiating rollback.")
            val restored = withContext(shizuku.ioSingle) {
                restoreSnapshotViaShell(shizuku, op.pkg, op.snapshot)
            }
            if (restored) {
                val freshOp = db.policyOpDao().getById(op.id)
                if (freshOp != null) {
                    db.policyOpDao().markRolledBackUnsafe(freshOp.id, freshOp.gen)
                }
            } else {
                return Result.retry()
            }
        }

        return Result.success()
    }

    private suspend fun restoreSnapshotViaShell(
        shizuku: ShizukuManager,
        pkg: String,
        snapshotJson: String?
    ): Boolean {
        if (snapshotJson.isNullOrBlank() || snapshotJson == "{}") return false
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            val userId = appInfo.uid / 100000

            val jsonMap = Json.parseToJsonElement(snapshotJson).jsonObject
            var allSucceeded = true

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

                val res = shizuku.exec(cmd)
                val observed = pm.getComponentEnabledSetting(componentName)
                val expectedState = if (state in 1..3) state else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                if (res.exitCode != 0 || observed != expectedState) {
                    allSucceeded = false
                }
            }
            allSucceeded
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private const val TAG = "CrashWatchdogWorker"
        const val KEY_POLICY_OP_ID = "policy_op_id"
        const val KEY_APPLIED_AT = "applied_at"

        internal fun hasCrashInWindow(
            dumpsysOutput: String,
            appliedAtMs: Long,
            windowMs: Long = 60_000L
        ): Boolean {
            if (dumpsysOutput.isBlank()) return false
            val blocks = dumpsysOutput.split("ApplicationExitInfo #")
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            val reasonRegex = Regex("""(?<![A-Za-z])reason=([4-7])\b""") // 4=CRASH, 5=CRASH_NATIVE, 6=ANR, 7=INITIALIZATION_FAILURE

            for (block in blocks) {
                if (!reasonRegex.containsMatchIn(block)) continue

                val tsMatch = Regex("""timestamp=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})""").find(block)
                val timestamp = if (tsMatch != null) {
                    try {
                        sdf.parse(tsMatch.groupValues[1])?.time
                    } catch (_: Throwable) {
                        null
                    }
                } else {
                    Regex("""timestamp=(\d{10,13})\b""").find(block)?.groupValues?.get(1)?.toLongOrNull()
                }

                if (timestamp != null && timestamp in appliedAtMs..(appliedAtMs + windowMs)) {
                    return true
                }
            }
            return false
        }
    }
}
