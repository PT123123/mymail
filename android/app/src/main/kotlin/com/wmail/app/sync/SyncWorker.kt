package com.wmail.app.sync

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
import com.wmail.app.WMailApp
import com.wmail.app.notify.Notifier
import java.util.concurrent.TimeUnit

/** 周期兜底同步(IDLE 在前台服务里做实时,见 docs/sync.md §实时性)。 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? WMailApp)?.container ?: return Result.failure()
        val accounts = runCatching { container.db.accountDao().listNow() }.getOrDefault(emptyList())
        for (account in accounts) {
            runCatching {
                container.sync.refreshFolders(account)
                val inbox = container.db.folderDao().find(account.id, "INBOX") ?: return@runCatching
                val newCount = container.sync.openFolder(account, inbox)
                if (newCount > 0) {
                    Notifier.notifyNewMail(
                        applicationContext,
                        account.displayName,
                        "有 $newCount 封新邮件",
                        account.id.hashCode(),
                    )
                }
            }
        }
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "wmail_sync_periodic"
        private const val ONESHOT = "wmail_sync_now"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONESHOT, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
