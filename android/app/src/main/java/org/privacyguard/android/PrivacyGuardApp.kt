package org.privacyguard.android

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.privacyguard.android.data.AppDatabase
import org.privacyguard.android.shizuku.ShizukuManager
import org.privacyguard.android.trigger.PackageChangeReceiver
import org.privacyguard.android.trigger.PowerSaveReceiver
import org.privacyguard.android.trigger.ReconciliationCoordinator

class PrivacyGuardApp : Application() {

    private val packageChangeReceiver = PackageChangeReceiver()
    private val powerSaveReceiver = PowerSaveReceiver()

    override fun onCreate() {
        super.onCreate()

        // Strict process isolation guard: skip initialization for :scan or other secondary processes
        val currentProcess = getProcessNameCompat()
        if (currentProcess != packageName) {
            Log.i(TAG, "Process $currentProcess is secondary; bypassing main-process initialization")
            return
        }

        // Initialize HiddenApiBypass exemptions in the main process
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
                Log.i(TAG, "HiddenApiBypass exemptions applied successfully")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed applying HiddenApiBypass exemptions", t)
            }
        }

        // Initialize Room Database
        AppDatabase.getInstance(this)

        // Initialize Shizuku Manager (listeners & binder registration)
        ShizukuManager.getInstance(this).init()

        // Register dynamic PackageChangeReceiver with RECEIVER_EXPORTED (system package events from UID 1000)
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageChangeReceiver, packageFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(packageChangeReceiver, packageFilter)
        }

        // Register dynamic PowerSaveReceiver with RECEIVER_NOT_EXPORTED
        val powerFilter = IntentFilter(PowerSaveReceiver.ACTION_POWER_SAVE_WHITELIST_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerSaveReceiver, powerFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(powerSaveReceiver, powerFilter)
        }

        // Seed initial changed packages delta check
        CoroutineScope(Dispatchers.IO).launch {
            ReconciliationCoordinator.triggerChangedPackagesCheck(this@PrivacyGuardApp)
        }
    }

    private fun getProcessNameCompat(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val myPid = Process.myPid()
            am?.runningAppProcesses?.firstOrNull { it.pid == myPid }?.processName ?: packageName
        } catch (e: Throwable) {
            packageName
        }
    }

    companion object {
        private const val TAG = "PrivacyGuardApp"
    }
}
