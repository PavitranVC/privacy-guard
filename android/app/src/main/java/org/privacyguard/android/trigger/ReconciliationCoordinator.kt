package org.privacyguard.android.trigger

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.privacyguard.android.data.AppDatabase
import org.privacyguard.android.data.entity.ReconcileRequest
import org.privacyguard.android.shizuku.ShizukuManager
import java.util.concurrent.atomic.AtomicInteger

object ReconciliationCoordinator {

    private const val TAG = "ReconcileCoordinator"
    private val lastSeq = AtomicInteger(0)

    /**
     * Polls PackageManager.getChangedPackages(lastSeq) and inserts any newly changed
     * package names into reconcile_request table with INSERT OR IGNORE.
     */
    suspend fun triggerChangedPackagesCheck(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val pm = context.packageManager
        val changed = try {
            pm.getChangedPackages(lastSeq.get())
        } catch (e: Throwable) {
            Log.e(TAG, "Failed reading changed packages", e)
            null
        }

        if (changed != null) {
            lastSeq.set(changed.sequenceNumber)
            val pkgNames = changed.packageNames
            if (pkgNames.isNotEmpty()) {
                Log.i(TAG, "Detected ${pkgNames.size} changed packages from sequence ${changed.sequenceNumber}")
                val db = AppDatabase.getInstance(context)
                val now = System.currentTimeMillis()
                val requests = pkgNames.map { ReconcileRequest(pkg = it, requestedAt = now) }
                db.reconcileRequestDao().insertIgnoreAll(requests)
            }
        }
    }

    /**
     * Drains pending reconcile requests and runnable policy_op items.
     */
    suspend fun processPending(context: Context) {
        val db = AppDatabase.getInstance(context)
        val shizuku = ShizukuManager.getInstance(context)

        // 1. Process runnable policy operations
        if (shizuku.isReady()) {
            val now = System.currentTimeMillis()
            val runnableOps = db.policyOpDao().getRunnableOps(now, limit = 10)
            for (op in runnableOps) {
                shizuku.executePolicyOp(op.id)
            }
        }

        // 2. Pop next batch of reconcile requests
        val batch = db.reconcileRequestDao().popNextBatch(limit = 20)
        if (batch.isNotEmpty()) {
            Log.i(TAG, "Popped ${batch.size} reconcile requests for triage")
        }
    }
}
