package org.privacyguard.android.scan

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.privacyguard.android.data.AppDatabase
import java.io.File
import kotlin.math.max

class ScanOrchestrator(
    private val context: Context,
    private val database: AppDatabase
) {

    private val mutex = Mutex()
    private var scanService: IScanService? = null
    private var connectionDeferred: CompletableDeferred<IScanService>? = null
    private var idleJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val iService = IScanService.Stub.asInterface(service)
            scanService = iService
            connectionDeferred?.complete(iService)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            scanService = null
            connectionDeferred = null
        }
    }

    /**
     * Scans an installed package across its base APK and code splits (excluding config.* splits).
     * Enforces poison-pill quarantine (quarantines package if it caused >= 2 crashes in :scan).
     */
    suspend fun scanPackageWithSplits(
        pkg: String,
        versionCode: Long,
        baseApkPath: String,
        splitApkPaths: List<String>
    ): NativeScanResult = withContext(Dispatchers.IO) {
        val scanFailedDao = database.scanFailedDao()

        // 1. Poison-Pill Quarantine Check
        val crashCount = scanFailedDao.getCrashCount(pkg, versionCode) ?: 0
        if (crashCount >= 2) {
            return@withContext NativeScanResult(
                scanStatus = NativeScanResult.STATUS_QUARANTINED,
                features = FloatArray(16),
                riskScore = 1.0,
                maxConfidence = 1.0,
                matchedTrackers = listOf("QUARANTINED_POISON_PILL"),
                entropy = 0.0,
                hasDynamicLoading = false,
                frameworkType = "QUARANTINED"
            )
        }

        // Filter out resource/config splits (e.g. config.xxhdpi, config.en)
        val codeSplits = splitApkPaths.filterNot { it.contains("config.", ignoreCase = true) }
        val allApks = listOf(baseApkPath) + codeSplits

        val baseFile = File(baseApkPath)
        if (!baseFile.exists() || !baseFile.canRead()) {
            return@withContext NativeScanResult(
                scanStatus = NativeScanResult.STATUS_PARSE_FAILED,
                features = FloatArray(16),
                riskScore = 0.0,
                maxConfidence = 0.0,
                matchedTrackers = emptyList(),
                entropy = 0.0,
                hasDynamicLoading = false,
                frameworkType = "UNKNOWN"
            )
        }

        // 2. Ensure service is ready BEFORE incrementing crash count
        val service = getOrInitService()

        // 3. Atomically increment crash counter prior to IPC execution
        scanFailedDao.recordCrashAtomic(pkg, versionCode, System.currentTimeMillis())

        try {
            val allResults = mutableListOf<NativeScanResult>()

            // 4. Execute scan with strict 30s timeout
            withTimeout(30_000L) {
                for (apkPath in allApks) {
                    val file = File(apkPath)
                    if (file.exists() && file.canRead()) {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                            val res = service.scanApkFd(pfd)
                            allResults.add(res)
                        }
                    }
                }
            }

            // 5. Clean return: clear crash record
            scanFailedDao.clear(pkg, versionCode)
            scheduleIdleUnbind()

            mergeResults(allResults)
        } catch (t: Throwable) {
            // Unbind immediately on crash or timeout so next attempt starts clean
            unbindServiceInternal()
            throw t
        }
    }

    private fun mergeResults(results: List<NativeScanResult>): NativeScanResult {
        if (results.isEmpty()) {
            return NativeScanResult(
                scanStatus = NativeScanResult.STATUS_PARSE_FAILED,
                features = FloatArray(16),
                riskScore = 0.0,
                maxConfidence = 0.0,
                matchedTrackers = emptyList(),
                entropy = 0.0,
                hasDynamicLoading = false,
                frameworkType = "UNKNOWN"
            )
        }

        val primary = results[0]
        val allTrackers = mutableSetOf<String>()
        var maxRisk = primary.riskScore
        var maxConf = primary.maxConfidence
        var maxEntropy = primary.entropy
        var hasDynLoad = primary.hasDynamicLoading
        var finalFramework = primary.frameworkType

        for (r in results) {
            allTrackers.addAll(r.matchedTrackers)
            maxRisk = max(maxRisk, r.riskScore)
            maxConf = max(maxConf, r.maxConfidence)
            maxEntropy = max(maxEntropy, r.entropy)
            if (r.hasDynamicLoading) hasDynLoad = true
            if (r.frameworkType != "Native" && r.frameworkType != "UNKNOWN") {
                finalFramework = r.frameworkType
            }
        }

        return NativeScanResult(
            scanStatus = primary.scanStatus,
            features = primary.features,
            riskScore = maxRisk,
            maxConfidence = maxConf,
            matchedTrackers = allTrackers.toList(),
            entropy = maxEntropy,
            hasDynamicLoading = hasDynLoad,
            frameworkType = finalFramework
        )
    }

    private suspend fun getOrInitService(): IScanService = mutex.withLock {
        idleJob?.cancel()
        val existing = scanService
        if (existing != null && existing.asBinder().isBinderAlive) {
            return existing
        }

        val deferred = CompletableDeferred<IScanService>()
        connectionDeferred = deferred

        val intent = Intent(context, ScanService::class.java)
        val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            throw IllegalStateException("Failed to bind to ScanService")
        }

        return withTimeout(5_000L) {
            deferred.await()
        }
    }

    private fun scheduleIdleUnbind() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(30_000L)
            mutex.withLock {
                unbindServiceInternal()
            }
        }
    }

    private fun unbindServiceInternal() {
        if (scanService != null) {
            try {
                context.unbindService(serviceConnection)
            } catch (_: Throwable) {}
            scanService = null
            connectionDeferred = null
        }
    }
}
