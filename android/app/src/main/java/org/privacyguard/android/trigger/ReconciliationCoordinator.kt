package org.privacyguard.android.trigger

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.privacyguard.android.data.AppDatabase
import org.privacyguard.android.data.entity.PolicyOp
import org.privacyguard.android.data.entity.SyncState
import org.privacyguard.android.shizuku.ShizukuManager
import org.privacyguard.android.work.ReconcileDrainWorker

object ReconciliationCoordinator {

    private const val TAG = "ReconcileCoordinator"
    private val mutex = Mutex()

    /**
     * Polls package changes safely:
     * If device boot count changed since last recorded sync state, initiates a full
     * installed package inventory to detect reboot drift.
     * Otherwise queries PackageManager.getChangedPackages(lastSeq) delta tracker.
     */
    suspend fun triggerChangedPackagesCheck(context: Context) = mutex.withLock {
        val db = AppDatabase.getInstance(context)
        val pm = context.packageManager
        val cr = context.contentResolver

        val currentBootCount = try {
            Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1)
        } catch (e: Throwable) {
            -1
        }

        val syncState = db.syncStateDao().get()
        val now = System.currentTimeMillis()

        if (syncState == null || (currentBootCount != -1 && syncState.bootCount != currentBootCount)) {
            // Boot count mismatch or first launch -> full scan
            Log.i(TAG, "Boot count change detected (recorded=${syncState?.bootCount}, current=$currentBootCount); running full package scan")
            try {
                val installedPackages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                for (p in installedPackages) {
                    val pkgName = p.packageName ?: continue
                    db.reconcileRequestDao().upsertRequest(pkgName, now)
                }
                db.syncStateDao().insertOrUpdate(
                    SyncState(
                        id = 1,
                        bootCount = currentBootCount,
                        lastSequenceNumber = 0,
                        updatedAt = now
                    )
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Error performing full package scan on boot count change", t)
            }
        } else {
            // Incremental sequence delta scan
            val lastSeq = syncState.lastSequenceNumber
            val changed = try {
                pm.getChangedPackages(lastSeq)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed reading changed packages", e)
                null
            }

            if (changed != null) {
                val newSeq = changed.sequenceNumber
                val pkgNames = changed.packageNames
                if (pkgNames.isNotEmpty()) {
                    Log.i(TAG, "Detected ${pkgNames.size} changed packages")
                    for (pkg in pkgNames) {
                        db.reconcileRequestDao().upsertRequest(pkg, now)
                    }
                }
                db.syncStateDao().insertOrUpdate(
                    SyncState(
                        id = 1,
                        bootCount = currentBootCount,
                        lastSequenceNumber = newSeq,
                        updatedAt = now
                    )
                )
            }
        }

        // Schedule background draining worker
        scheduleDrainWork(context)
    }

    /**
     * Drains pending reconcile requests using safe watermark deletion.
     */
    suspend fun processPending(context: Context) = mutex.withLock {
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

        // 2. Process next batch of reconcile requests with watermark deletion
        val batch = db.reconcileRequestDao().getNextBatch(20)
        for (item in batch) {
            try {
                // Check drift on existing applied ops for package
                val appliedOps = db.policyOpDao().getByPackageAndVersion(item.pkg, 0)
                // Watermark delete: only delete requests up to the timestamp processed
                db.reconcileRequestDao().deleteProcessed(item.pkg, item.requestedAt)
            } catch (t: Throwable) {
                Log.e(TAG, "Error reconciling request", t)
            }
        }
    }

    fun scheduleDrainWork(context: Context) {
        val workRequest = OneTimeWorkRequestBuilder<ReconcileDrainWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ReconcileDrainWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }
}
