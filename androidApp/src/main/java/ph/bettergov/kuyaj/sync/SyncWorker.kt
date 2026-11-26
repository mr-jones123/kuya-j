package ph.bettergov.kuyaj.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ph.bettergov.kuyaj.AppConfig
import ph.bettergov.kuyaj.KuyaJApp
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as KuyaJApp
        app.container.store.awaitReady() // let startup finish loading records first, so they don't replace a fresh bundle
        return when (val r = app.container.bundleSync.sync()) {
            is BundleSync.Result.Updated, BundleSync.Result.UpToDate -> Result.success()
            // A bad signature will not fix itself on retry; a network error might.
            is BundleSync.Result.Failed -> if (r.reason.startsWith("network")) Result.retry() else Result.failure()
        }.also { app.container.refreshSyncStatus() }
    }

    companion object {
        // UNMETERED = Wi-Fi (or Ethernet). WorkManager waits for it, which also covers "sync on reconnect".
        private val wifiOnly = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

        fun schedule(context: Context) {
            if (AppConfig.syncBaseUrl == null) return
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                "kb-sync",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(AppConfig.SYNC_INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(wifiOnly).build(),
            )
            syncNow(context, ExistingWorkPolicy.KEEP)
        }

        fun syncNow(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
            if (AppConfig.syncBaseUrl == null) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                "kb-sync-now",
                policy,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(wifiOnly).build(),
            )
        }
    }
}
