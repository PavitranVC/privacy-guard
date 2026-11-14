package org.privacyguard.android.scan

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.privacyguard.android.crypto.Ed25519Verifier
import org.privacyguard.android.data.AppDatabase
import java.io.InputStream

class ScanOrchestrator(
    private val context: Context,
    private val database: AppDatabase
) {

    private val mutex = Mutex()
    private var scanService: IScanService? = null
    private var isEngineInitialized = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            scanService = IScanService.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            scanService = null
            isEngineInitialized = false
        }
    }

    /**
     * Scans an installed or uninstalled APK path with poison-pill quarantine and crash safety.
     * If the APK caused >= 2 crashes in the :scan process, it is quarantined immediately.
     */
    suspend fun scanPackage(
        pkg: String,
        versionCode: Long,
        apkPath: String
    ): NativeScanResult = withContext(Dispatchers.IO) {
        val scanFailedDao = database.scanFailedDao()

        // 1. Poison-Pill Quarantine Check
        val crashCount = scanFailedDao.getCrashCount(pkg, versionCode) ?: 0
        if (crashCount >= 2) {
            return@withContext NativeScanResult(
                features = FloatArray(0),
                riskScore = 1.0,
                matchedTrackers = listOf("QUARANTINED_POISON_PILL"),
                entropy = 0.0,
                hasDynamicLoading = false,
                frameworkType = "QUARANTINED"
            )
        }

        // 2. Pre-log crash atomically before entering IPC
        scanFailedDao.recordCrashAtomic(pkg, versionCode, System.currentTimeMillis())

        val service = getOrInitService()

        // 3. Execute IPC scan inside isolated :scan process
        try {
            val result = service.scanApk(apkPath)

            // 4. Clean IPC return: clear crash counter
            scanFailedDao.clear(pkg, versionCode)
            result
        } catch (e: DeadObjectException) {
            // :scan process crashed or was killed by signal
            throw e
        } catch (e: RemoteException) {
            throw e
        }
    }

    /**
     * Binds to the isolated :scan service and performs TOCTOU-free Ed25519 cryptographic
     * verification of the static rules and LightGBM model assets before passing verified bytes.
     */
    private suspend fun getOrInitService(): IScanService = mutex.withLock {
        var service = scanService
        if (service == null) {
            val intent = Intent(context, ScanService::class.java)
            val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                throw IllegalStateException("Failed to bind to isolated :scan ScanService")
            }

            // Wait for service connection
            var attempts = 0
            while (scanService == null && attempts < 50) {
                kotlinx.coroutines.delay(20)
                attempts++
            }
            service = scanService ?: throw IllegalStateException("Timed out waiting for ScanService connection")
        }

        if (!isEngineInitialized) {
            // Read and cryptographically verify assets in-memory (TOCTOU-free)
            val acBytes = readAsset("trackers_ac.bin")
            val acSig = readAsset("trackers_ac.bin.sig")
            val modelBytes = readAsset("lgbm_model.bin")
            val modelSig = readAsset("lgbm_model.bin.sig")

            if (!Ed25519Verifier.verify(acBytes, acSig)) {
                throw SecurityException("Ed25519 signature verification failed for trackers_ac.bin")
            }
            if (!Ed25519Verifier.verify(modelBytes, modelSig)) {
                throw SecurityException("Ed25519 signature verification failed for lgbm_model.bin")
            }

            val initialized = service.initEngine(acBytes, modelBytes)
            if (!initialized) {
                throw IllegalStateException("ScanService nativeInitEngine returned false")
            }
            isEngineInitialized = true
        }

        service
    }

    private fun readAsset(assetName: String): ByteArray {
        context.assets.open(assetName).use { inputStream: InputStream ->
            return inputStream.readBytes()
        }
    }
}
