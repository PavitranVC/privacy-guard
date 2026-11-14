package org.privacyguard.android.scan

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import org.privacyguard.android.crypto.Ed25519Verifier

class ScanService : Service() {

    companion object {
        private const val TAG = "ScanService"

        init {
            System.loadLibrary("privacyguard_scan")
        }
    }

    private external fun nativeInitEngine(trackerAcBytes: ByteArray, modelBytes: ByteArray): Boolean
    private external fun nativeScanApkFd(fd: Int): NativeScanResult?

    private var engineInitialized = false

    override fun onCreate() {
        super.onCreate()
        initEngineInternal()
    }

    private fun initEngineInternal(): Boolean {
        if (engineInitialized) return true
        try {
            val acBytes = assets.open("trackers_ac.bin").use { it.readBytes() }
            val acSig = assets.open("trackers_ac.bin.sig").use { it.readBytes() }
            val modelBytes = assets.open("lgbm_model.bin").use { it.readBytes() }
            val modelSig = assets.open("lgbm_model.bin.sig").use { it.readBytes() }

            if (!Ed25519Verifier.verify(acBytes, acSig)) {
                Log.e(TAG, "Ed25519 verification failed for trackers_ac.bin")
                return false
            }

            if (!Ed25519Verifier.verify(modelBytes, modelSig)) {
                Log.e(TAG, "Ed25519 verification failed for lgbm_model.bin")
                return false
            }

            engineInitialized = nativeInitEngine(acBytes, modelBytes)
            return engineInitialized
        } catch (t: Throwable) {
            Log.e(TAG, "Failed initializing native engine in :scan process", t)
            return false
        }
    }

    private val binder = object : IScanService.Stub() {
        override fun scanApkFd(pfd: ParcelFileDescriptor?): NativeScanResult {
            if (pfd == null) {
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

            if (!engineInitialized && !initEngineInternal()) {
                return NativeScanResult(
                    scanStatus = NativeScanResult.STATUS_INIT_FAILED,
                    features = FloatArray(16),
                    riskScore = 0.0,
                    maxConfidence = 0.0,
                    matchedTrackers = emptyList(),
                    entropy = 0.0,
                    hasDynamicLoading = false,
                    frameworkType = "UNKNOWN"
                )
            }

            // Android 14+ fdsan safety: pfd.use strictly owns the FD lifecycle
            return pfd.use { descriptor ->
                val result = nativeScanApkFd(descriptor.fd)
                result ?: NativeScanResult(
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
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}
