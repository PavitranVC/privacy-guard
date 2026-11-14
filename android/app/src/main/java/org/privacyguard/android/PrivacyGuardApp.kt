package org.privacyguard.android

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.privacyguard.android.data.AppDatabase
import org.privacyguard.android.shizuku.ShizukuManager
import org.privacyguard.android.trigger.PackageChangeReceiver
import org.privacyguard.android.trigger.PowerSaveReceiver
import org.privacyguard.android.trigger.ReconciliationCoordinator

class PrivacyGuardApp : Application(), Configuration.Provider {

    private val packageChangeReceiver = PackageChangeReceiver()
    private val powerSaveReceiver = PowerSaveReceiver()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()

        // Strict process isolation guard: skip initialization for :scan or any secondary processes
        val currentProcess = Application.getProcessName()
        if (currentProcess != packageName) {
            return
        }

        // Initialize HiddenApiBypass scoped exemptions in the main process
        try {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/net/IConnectivityManager",
                "Landroid/content/pm/"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed applying HiddenApiBypass exemptions", t)
        }

        // Initialize Room Database
        AppDatabase.getInstance(this)

        // Initialize Shizuku Manager
        ShizukuManager.getInstance(this).init()

        // Register dynamic receivers with Context.RECEIVER_NOT_EXPORTED
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        registerReceiver(packageChangeReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED)

        val powerFilter = IntentFilter(PowerSaveReceiver.ACTION_POWER_SAVE_WHITELIST_CHANGED)
        registerReceiver(powerSaveReceiver, powerFilter, Context.RECEIVER_NOT_EXPORTED)

        // Initial reconciliation check
        CoroutineScope(Dispatchers.IO).launch {
            ReconciliationCoordinator.triggerChangedPackagesCheck(this@PrivacyGuardApp)
        }
    }

    companion object {
        private const val TAG = "PrivacyGuardApp"
    }
}
