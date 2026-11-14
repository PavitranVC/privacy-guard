package org.privacyguard.android.scan

import android.app.Service
import android.content.Intent
import android.os.IBinder

class ScanService : Service() {

    companion object {
        init {
            System.loadLibrary("privacyguard_scan")
        }
    }

    private external fun nativeInitEngine(trackerAcBytes: ByteArray, modelBytes: ByteArray): Boolean
    private external fun nativeScanApk(apkPath: String): NativeScanResult?

    private val binder = object : IScanService.Stub() {
        override fun initEngine(trackerAcBytes: ByteArray, modelBytes: ByteArray): Boolean {
            return nativeInitEngine(trackerAcBytes, modelBytes)
        }

        override fun scanApk(apkPath: String): NativeScanResult {
            val result = nativeScanApk(apkPath)
            return result ?: NativeScanResult(
                features = FloatArray(0),
                riskScore = 0.0,
                matchedTrackers = emptyList(),
                entropy = 0.0,
                hasDynamicLoading = false,
                frameworkType = "UNKNOWN"
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}
