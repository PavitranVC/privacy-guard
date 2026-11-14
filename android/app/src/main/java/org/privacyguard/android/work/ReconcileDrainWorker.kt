package org.privacyguard.android.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.privacyguard.android.trigger.ReconciliationCoordinator

class ReconcileDrainWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (runAttemptCount >= 5) return Result.failure()
        return try {
            ReconciliationCoordinator.processPending(applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "reconcile_drain"
    }
}
