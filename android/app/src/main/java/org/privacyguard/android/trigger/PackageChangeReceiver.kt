package org.privacyguard.android.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.privacyguard.android.data.AppDatabase

class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pkg = intent.data?.schemeSpecificPart ?: return

        // Anti-fingerprinting: Log action only without logging package name
        Log.i(TAG, "Package event received: action=$action")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val now = System.currentTimeMillis()
                db.reconcileRequestDao().upsertRequestWithSeq(pkg, now, now)
                ReconciliationCoordinator.scheduleDrainWork(context)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed recording reconcile request", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PackageChangeReceiver"
    }
}
