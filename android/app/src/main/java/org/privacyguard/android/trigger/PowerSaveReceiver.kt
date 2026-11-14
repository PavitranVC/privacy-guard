package org.privacyguard.android.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PowerSaveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_POWER_SAVE_WHITELIST_CHANGED) return

        Log.i(TAG, "Power save whitelist changed; scheduling reconciliation check")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReconciliationCoordinator.triggerChangedPackagesCheck(context)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed processing power save change", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PowerSaveReceiver"
        const val ACTION_POWER_SAVE_WHITELIST_CHANGED = "android.os.action.POWER_SAVE_WHITELIST_CHANGED"
    }
}
