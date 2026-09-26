package com.wmail.app

import android.app.Application
import com.wmail.app.data.MailSync
import com.wmail.app.data.SecretStore
import com.wmail.app.data.db.AppDatabase
import com.wmail.core.ComposeDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class AppContainer(context: android.content.Context) {
    val db: AppDatabase = AppDatabase.build(context)
    val secrets = SecretStore(context)
    val backfillScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val sync = MailSync(db, secrets, File(context.cacheDir, "bodies"), backfillScope)

    /** 写信页的预填草稿(回复/转发跳转用),ComposeScreen 读走后置空。 */
    var pendingDraft: ComposeDraft? = null
}

class WMailApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
