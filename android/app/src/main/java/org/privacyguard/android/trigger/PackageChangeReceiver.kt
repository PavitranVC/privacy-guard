package org.privacyguard.android.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.privacyguard.android.data.AppDatabase
import org.privacyguard.android.data.entity.ReconcileRequest

class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pkg = intent.data?.schemeSpecificPart ?: return

        Log.i(TAG, "Package event received: action=$action, pkg=$pkg")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                db.reconcileRequestDao().insertIgnore(
                    ReconcileRequest(pkg = pkg, requestedAt = System.currentTimeMillis())
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed inserting reconcile request for $pkg", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PackageChangeReceiver"
    }
}
