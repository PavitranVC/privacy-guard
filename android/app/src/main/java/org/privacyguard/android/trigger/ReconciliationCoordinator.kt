package org.privacyguard.android.trigger

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.privacyguard.android.data.AppDatabase
import org.privacyguard.android.data.entity.AppScanResult
import org.privacyguard.android.data.entity.PolicyOp
import org.privacyguard.android.data.entity.SyncState
import org.privacyguard.android.scan.ScanOrchestrator
import org.privacyguard.android.scan.tier1a.Tier1AScanner
import org.privacyguard.android.shizuku.ShellOps
import org.privacyguard.android.shizuku.ShizukuManager
import org.privacyguard.android.work.ReconcileDrainWorker
import java.io.File
import java.security.MessageDigest

object ReconciliationCoordinator {

    private const val TAG = "ReconcileCoordinator"
    private val checkMutex = Mutex()

    /**
     * Polls package changes safely:
     * If device boot count changed since last recorded sync state, initiates a full
     * installed package inventory to detect reboot drift.
     * Fallback when BOOT_COUNT is inaccessible (-1): checks if elapsed real time indicates reboot.
     * Otherwise queries PackageManager.getChangedPackages(lastSeq) delta tracker.
     */
    suspend fun triggerChangedPackagesCheck(context: Context) = checkMutex.withLock {
        val db = AppDatabase.getInstance(context)
        val pm = context.packageManager
        val cr = context.contentResolver

        val currentBootCount = try {
            Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1)
        } catch (_: Throwable) {
            -1
        }

        val syncState = db.syncStateDao().get()
        val now = System.currentTimeMillis()
        val uptime = SystemClock.elapsedRealtime()

        val rebootDetected = if (currentBootCount != -1) {
            syncState == null || syncState.bootCount != currentBootCount
        } else {
            // Fallback when BOOT_COUNT is inaccessible (-1):
            // If device uptime is less than elapsed time since last sync, a reboot occurred.
            syncState == null || (now - syncState.updatedAt > uptime)
        }

        if (rebootDetected) {
            Log.i(TAG, "Reboot or initial sync detected; running full package inventory")
            try {
                val installedPackages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                for (p in installedPackages) {
                    val pkgName = p.packageName ?: continue
                    db.reconcileRequestDao().upsertRequestWithSeq(pkgName, now, 0L)
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
            val lastSeq = syncState?.lastSequenceNumber ?: 0
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
                        db.reconcileRequestDao().upsertRequestWithSeq(pkg, now, newSeq.toLong())
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

        scheduleDrainWork(context)
    }

    /**
     * Drains pending reconcile requests without holding checkMutex.
     * Extracts Tier 1A manifest features + Tier 1B native scanner results across split APKs,
     * updates AppScanResult, checks policy drift, and drains runnable Shizuku policy ops.
     */
    suspend fun processPending(context: Context) {
        val db = AppDatabase.getInstance(context)
        val shizuku = ShizukuManager.getInstance(context)
        val scanOrchestrator = ScanOrchestrator(context, db)

        val batch = db.reconcileRequestDao().popBatch(10)
        if (batch.isEmpty()) {
            if (shizuku.isReady()) {
                shizuku.drainRunnableOps()
            }
            return
        }

        val trackerSignatures = try {
            context.assets.open("trackers_ac.bin").bufferedReader().useLines { lines ->
                lines.filter { !it.startsWith("#") && !it.startsWith("TRACKER_AC_") && it.isNotBlank() }.toList()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed loading tracker signatures from assets", t)
            emptyList<String>()
        }

        val now = System.currentTimeMillis()

        for (req in batch) {
            try {
                val t1 = try {
                    Tier1AScanner.scanInstalledApp(context, req.pkg, trackerSignatures)
                } catch (t: Throwable) {
                    Log.w(TAG, "Tier 1A scan failed for ${req.pkg}: ${t.message}")
                    null
                }

                if (t1 != null) {
                    val nativeResult = scanOrchestrator.scanPackageWithSplits(
                        pkg = req.pkg,
                        versionCode = t1.versionCode,
                        baseApkPath = t1.baseApkPath,
                        splitApkPaths = t1.splitApkPaths
                    )

                    val finalRiskScore = maxOf(t1.manifestRiskScore, nativeResult.riskScore)
                    val apkSha256 = computeFileSha256(t1.baseApkPath)

                    val allTrackers = (t1.detectedTrackers + nativeResult.matchedTrackers).distinct()
                    val trackersJson = Json.encodeToString(
                        ListSerializer(String.serializer()),
                        allTrackers
                    )
                    val permsJson = Json.encodeToString(
                        ListSerializer(String.serializer()),
                        t1.dangerousPermissions
                    )

                    val osintVerdict = if (finalRiskScore >= 0.60 && nativeResult.maxConfidence < 0.90) {
                        "PENDING"
                    } else {
                        "SKIPPED_LOW_RISK"
                    }

                    val scanResult = AppScanResult(
                        pkg = req.pkg,
                        versionCode = t1.versionCode,
                        certSha256 = t1.signingCertSha256,
                        apkSha256 = apkSha256,
                        scanStatus = nativeResult.scanStatus,
                        riskScore = finalRiskScore,
                        maxConfidence = nativeResult.maxConfidence,
                        matchedTrackersJson = trackersJson,
                        dangerousPermissionsJson = permsJson,
                        detectedFramework = nativeResult.frameworkType,
                        osintVerdict = osintVerdict,
                        scannedAt = now
                    )
                    db.appScanResultDao().upsert(scanResult)

                    // Drift check on existing applied ops for package
                    val appliedOps = db.policyOpDao().getByPackage(req.pkg)
                    for (op in appliedOps) {
                        if (op.status == PolicyOp.STATUS_APPLIED || op.status == PolicyOp.STATUS_APPLIED_PARTIAL) {
                            if (isOpDrifted(context, op)) {
                                Log.w(TAG, "PolicyOp drifted for pkg=${req.pkg}, op=${op.op}, target=${op.target}")
                                db.policyOpDao().markDriftPending(op.id, op.gen)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error reconciling package ${req.pkg}", t)
            } finally {
                db.reconcileRequestDao().deleteProcessed(req.pkg, req.seq)
            }
        }

        if (shizuku.isReady()) {
            shizuku.drainRunnableOps()
        }

        if (db.appScanResultDao().getPendingOsintQueue().isNotEmpty()) {
            org.privacyguard.android.osint.OsintClassifierWorker.enqueue(context)
        }

        if (db.reconcileRequestDao().countPending() > 0) {
            scheduleDrainWork(context)
        }
    }

    private fun isOpDrifted(context: Context, op: PolicyOp): Boolean {
        if (op.op == ShellOps.OP_DISABLE_COMPONENTS) {
            val cn = ComponentName(op.pkg, op.target)
            val state = try {
                context.packageManager.getComponentEnabledSetting(cn)
            } catch (_: Throwable) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            }
            val isCurrentlyDisabled = (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
            val desiredDisabled = op.desired == ShellOps.DESIRED_DISABLED
            return desiredDisabled != isCurrentlyDisabled
        }
        return false
    }

    private fun computeFileSha256(path: String): String {
        val file = File(path)
        if (!file.exists() || !file.canRead()) return ""
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buf = ByteArray(65536)
                var bytesRead: Int
                while (stream.read(buf).also { bytesRead = it } != -1) {
                    md.update(buf, 0, bytesRead)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            ""
        }
    }

    fun scheduleDrainWork(context: Context) {
        val workRequest = OneTimeWorkRequestBuilder<ReconcileDrainWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ReconcileDrainWorker.WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            workRequest
        )
    }
}
