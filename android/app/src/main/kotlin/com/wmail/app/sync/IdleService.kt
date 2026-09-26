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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台服务:应用在前台时对每个账户的 INBOX 跑 IMAP IDLE 实时收信。
 * 通知点击后由 SyncWorker 做一次完整同步。
 * 每个账户一条线程,按 accountId 登记;服务重启时补齐缺失账户,删除账户时单独停掉。
 */
class IdleService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannel(this)
        startForeground(SERVICE_NOTIFICATION_ID, Notifier.serviceNotification(this))

        val container = (application as WMailApp).container
        container.backfillScope.launch(Dispatchers.IO) {
            val accounts = runCatching { container.db.accountDao().listNow() }.getOrDefault(emptyList())
            for (account in accounts) {
                if (runners.containsKey(account.id)) continue
                val stop = AtomicBoolean(false)
                runners[account.id] = stop
                Thread {
                    runCatching {
                        ImapClient(container.sync.config(account)).idleLoop(
                            "INBOX",
                            stop,
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
                    // 正常退出(停止标志或服务销毁)后移除登记,便于下次启动重新拉起
                    runners.remove(account.id)
                }.start()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runners.values.forEach { it.set(true) }
        runners.clear()
        super.onDestroy()
    }

    companion object {
        private const val SERVICE_NOTIFICATION_ID = 1001

        /** accountId → 停止标志 */
        private val runners = ConcurrentHashMap<String, AtomicBoolean>()

        /** 账户删除后停掉对应的 IDLE 线程。 */
        fun onAccountRemoved(accountId: String) {
            runners.remove(accountId)?.set(true)
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, IdleService::class.java))
        }
    }
}
