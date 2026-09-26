package com.wmail.app.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.wmail.app.WMailApp
import com.wmail.app.notify.Notifier
import com.wmail.core.ImapClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台服务:应用在前台时对每个账户的 INBOX 跑 IMAP IDLE 实时收信。
 * 通知点击后由 SyncWorker 做一次完整同步。
 */
class IdleService : Service() {

    private val stopFlag = AtomicBoolean(true)
    private val threads = mutableListOf<Thread>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannel(this)
        startForeground(SERVICE_NOTIFICATION_ID, Notifier.serviceNotification(this))
        stopFlag.set(false)

        val app = application as WMailApp
        val container = app.container
        app.container.backfillScope.launch(Dispatchers.IO) {
            val accounts = runCatching { container.db.accountDao().listNow() }.getOrDefault(emptyList())
            for (account in accounts) {
                val thread = Thread {
                    runCatching {
                        ImapClient(container.sync.config(account)).idleLoop(
                            "INBOX",
                            stopFlag,
                            onMail = {
                                Notifier.notifyNewMail(
                                    applicationContext,
                                    account.displayName,
                                    "收到新邮件",
                                    account.id.hashCode(),
                                )
                                SyncWorker.runNow(applicationContext)
                            },
                            onError = { },
                        )
                    }
                }
                synchronized(threads) { threads += thread }
                thread.start()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopFlag.set(true)
        synchronized(threads) { threads.clear() }
        super.onDestroy()
    }

    companion object {
        private const val SERVICE_NOTIFICATION_ID = 1001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, IdleService::class.java))
        }
    }
}
