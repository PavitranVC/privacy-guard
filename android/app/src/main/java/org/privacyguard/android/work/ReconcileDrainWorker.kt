package org.privacyguard.android.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.privacyguard.android.trigger.ReconciliationCoordinator

class ReconcileDrainWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            ReconciliationCoordinator.processPending(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "reconcile_drain"
    }
}
