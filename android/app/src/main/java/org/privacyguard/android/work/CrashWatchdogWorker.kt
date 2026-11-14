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

        val shizuku = ShizukuManager.getInstance(applicationContext)
        if (!shizuku.isReady()) {
            return Result.retry()
        }

        // Query privileged exit reasons via dumpsys activity exit-info
        val dumpsysResult = withContext(shizuku.ioSingle) {
            shizuku.exec(arrayOf("dumpsys", "activity", "exit-info", op.pkg))
        }

        var crashDetected = false
        val dumpsysOut = dumpsysResult.stdout

        if (dumpsysResult.exitCode == 0 && dumpsysOut.isNotBlank()) {
            // Split into individual ApplicationExitInfo blocks
            val blocks = dumpsysOut.split("ApplicationExitInfo #")
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            val reasonRegex = Regex("reason=(4|5|6|7)\\b") // 4=CRASH, 5=CRASH_NATIVE, 6=ANR, 7=INITIALIZATION_FAILURE

            for (block in blocks) {
                if (!reasonRegex.containsMatchIn(block)) continue

                val tsLine = block.lines().firstOrNull { it.trim().startsWith("timestamp=") }
                if (tsLine != null) {
                    val rawTs = tsLine.substringAfter("timestamp=").trim().substringBefore(" ")
                    val timestamp = try {
                        sdf.parse(rawTs)?.time
                    } catch (_: Throwable) {
                        rawTs.toLongOrNull()
                    }

                    if (timestamp != null && timestamp in appliedAt..(appliedAt + 60_000L)) {
                        crashDetected = true
                        break
                    }
                }
            }
        }

        if (crashDetected) {
            Log.w(TAG, "Process crash detected within 60s window for ${op.pkg}. Initiating rollback.")
            val restored = withContext(shizuku.ioSingle) {
                restoreSnapshotViaShell(shizuku, op.pkg, op.snapshot)
            }
            if (restored) {
                db.policyOpDao().markRolledBackUnsafe(op.id, op.gen)
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
        if (snapshotJson.isNullOrBlank()) return true
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
                if (res.exitCode != 0 || (state != 0 && observed != state)) {
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
    }
}
