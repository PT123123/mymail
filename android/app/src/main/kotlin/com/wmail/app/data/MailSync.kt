package com.wmail.app.data

import com.wmail.app.data.db.AccountEntity
import com.wmail.app.data.db.AppDatabase
import com.wmail.app.data.db.FolderEntity
import com.wmail.app.data.db.MessageEntity
import com.wmail.core.ImapClient
import com.wmail.core.MailAccountConfig
import com.wmail.core.MailAttachment
import com.wmail.core.MailEndpoint
import com.wmail.core.MailSecurity
import com.wmail.core.MailSanitizer
import com.wmail.core.MailMessageSummary
import com.wmail.core.SmtpClient
import com.wmail.core.ComposeDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 打开邮件得到的正文视图(HTML 已净化)。 */
data class BodyView(
    val html: String?,
    val text: String?,
    val attachments: List<MailAttachment>,
)

/**
 * 应用侧同步编排:服务器 ↔ Room 缓存(对应 Windows 端的 SyncEngine,规则见 docs/sync.md)。
 */
class MailSync(
    private val db: AppDatabase,
    private val secrets: SecretStore,
    private val bodiesDir: File,
    val backfillScope: CoroutineScope,
) {
    fun config(account: AccountEntity): MailAccountConfig = MailAccountConfig(
        id = account.id,
        displayName = account.displayName,
        email = account.email,
        imap = MailEndpoint(account.imapHost, account.imapPort, MailSecurity.valueOf(account.imapSecurity)),
        smtp = MailEndpoint(account.smtpHost, account.smtpPort, MailSecurity.valueOf(account.smtpSecurity)),
        password = secrets.getPassword(account.id) ?: "",
    )

    /**
     * 添加账户:先验证 IMAP 可连再落库。
     * 同一邮箱(同服务商或不同服务商)只能添加一次,避免重复同步混乱;同一服务商的不同邮箱可添加多个。
     */
    suspend fun addAccount(
        displayName: String,
        email: String,
        password: String,
        imap: MailEndpoint,
        smtp: MailEndpoint,
    ): AccountEntity = withContext(Dispatchers.IO) {
        db.accountDao().findByEmail(email.trim())?.let {
            throw IllegalStateException("该邮箱账户已存在")
        }
        val id = UUID.randomUUID().toString()
        val probe = MailAccountConfig(id, displayName, email, imap, smtp, password)
        ImapClient(probe).listFolders() // 连不上会抛,账户不入库
        val account = AccountEntity(
            id = id,
            displayName = displayName.ifBlank { email },
            email = email,
            imapHost = imap.host, imapPort = imap.port, imapSecurity = imap.security.name,
            smtpHost = smtp.host, smtpPort = smtp.port, smtpSecurity = smtp.security.name,
        )
        secrets.setPassword(id, password)
        db.accountDao().insert(account)
        account
    }

    /** 删除账户:清本地缓存、正文文件与密钥;服务器上的邮件不动。 */
    suspend fun deleteAccount(account: AccountEntity) = withContext(Dispatchers.IO) {
        val folders = db.folderDao().listNow(account.id)
        db.messageDao().deleteForAccount(account.id)
        db.folderDao().deleteForAccount(account.id)
        db.accountDao().delete(account.id)
        secrets.remove(account.id)
        for (f in folders) {
            bodiesDir.listFiles()
                ?.filter { it.name.startsWith("${f.id}_") }
                ?.forEach { it.delete() }
        }
    }

    suspend fun refreshFolders(account: AccountEntity) = withContext(Dispatchers.IO) {
        val folders = ImapClient(config(account)).listFolders()
        val dao = db.folderDao()
        for (f in folders) {
            val existing = dao.find(account.id, f.fullName)
            if (existing == null) {
                dao.insert(
                    FolderEntity(
                        accountId = account.id,
                        fullName = f.fullName,
                        name = f.name,
                        delimiter = f.delimiter?.toString(),
                        semantics = f.semantic.joinToString(","),
                    ),
                )
            } else if (existing.name != f.name || existing.semantics != f.semantic.joinToString(",")) {
                dao.update(existing.copy(name = f.name, semantics = f.semantic.joinToString(",")))
            }
        }
    }

    /** 打开文件夹:UIDVALIDITY 校验 → 同步最新 200 封。返回新邮件数。 */
    suspend fun openFolder(account: AccountEntity, folder: FolderEntity): Int = withContext(Dispatchers.IO) {
        val client = ImapClient(config(account))
        val state = client.select(folder.fullName)
        if (state.uidValidity != folder.uidValidity) {
            db.messageDao().deleteForFolder(folder.id)
        }
        db.folderDao().updateState(folder.id, state.uidValidity, state.uidNext, state.unread, state.exists)
        val oldMax = db.messageDao().maxUid(folder.id) ?: 0L
        val page = client.fetchPage(folder.fullName, 0, minOf(200, state.exists))
        if (page.isNotEmpty()) {
            db.messageDao().upsert(page.map { it.toEntity(account.id, folder.id) })
        }
        startBackfill(account, folder)
        page.count { it.uid > oldMax }
    }

    /** 后台按 500 封一页向历史回填(断点 = 本地已缓存数,见 docs/sync.md §全量回填)。 */
    fun startBackfill(account: AccountEntity, folder: FolderEntity) {
        backfillScope.launch {
            var cached = db.messageDao().count(folder.id)
            var failures = 0
            while (cached < folder.totalCount && isActive) {
                try {
                    val page = ImapClient(config(account)).fetchPage(folder.fullName, cached, 500)
                    if (page.isEmpty()) break
                    db.messageDao().upsert(page.map { it.toEntity(account.id, folder.id) })
                    cached += page.size
                    failures = 0
                } catch (e: Exception) {
                    if (++failures >= 3) break
                    delay(10_000)
                }
            }
        }
    }

    private fun MailMessageSummary.toEntity(accountId: String, folderId: Long) = MessageEntity(
        accountId = accountId,
        folderId = folderId,
        uid = uid,
        messageId = messageId,
        subject = subject,
        fromAddr = from,
        toAddrs = to,
        dateUnix = dateUnix,
        flags = flags,
        sizeBytes = sizeBytes,
        hasAttachments = hasAttachments,
    )

    suspend fun loadBody(account: AccountEntity, folder: FolderEntity, msg: MessageEntity): BodyView =
        withContext(Dispatchers.IO) {
            val cachedOk = msg.fetchedBody &&
                msg.bodyHtmlPath?.let { File(it).exists() } == true &&
                !msg.hasAttachments
            if (cachedOk) {
                return@withContext BodyView(
                    html = File(msg.bodyHtmlPath!!).readText(),
                    text = msg.snippet,
                    attachments = emptyList(),
                )
            }

            val client = ImapClient(config(account))
            val body = client.fetchBody(folder.fullName, msg.uid)
            val html = MailSanitizer.clean(body.html)
            var path: String? = null
            if (html != null) {
                bodiesDir.mkdirs()
                val file = File(bodiesDir, "${folder.id}_${msg.uid}.html")
                file.writeText(html)
                path = file.absolutePath
            }
            val snippet = (body.text ?: "(HTML 邮件)").replace("\n", " ").trim().take(200)
            db.messageDao().updateBody(folder.id, msg.uid, snippet, path)
            if (body.attachments.isNotEmpty()) db.messageDao().markHasAttachments(folder.id, msg.uid)

            if (!msg.isSeen) {
                msg.flags = msg.flags or MessageFlagsSeen
                db.messageDao().updateFlags(folder.id, msg.uid, msg.flags)
                runCatching { client.setFlag(folder.fullName, msg.uid, "Seen", true) }
            }
            BodyView(html = html, text = body.text, attachments = body.attachments)
        }

    suspend fun setSeen(account: AccountEntity, folder: FolderEntity, msg: MessageEntity, value: Boolean) =
        withContext(Dispatchers.IO) {
            msg.flags = if (value) msg.flags or 1 else msg.flags and 1.inv()
            db.messageDao().updateFlags(folder.id, msg.uid, msg.flags)
            runCatching { ImapClient(config(account)).setFlag(folder.fullName, msg.uid, "Seen", value) }
        }

    suspend fun toggleFlagged(account: AccountEntity, folder: FolderEntity, msg: MessageEntity) =
        withContext(Dispatchers.IO) {
            val value = !msg.isFlagged
            msg.flags = if (value) msg.flags or 2 else msg.flags and 2.inv()
            db.messageDao().updateFlags(folder.id, msg.uid, msg.flags)
            runCatching { ImapClient(config(account)).setFlag(folder.fullName, msg.uid, "Flagged", value) }
        }

    suspend fun delete(account: AccountEntity, folder: FolderEntity, msg: MessageEntity) =
        withContext(Dispatchers.IO) {
            val folders = db.folderDao().listNow(account.id)
            val trash = folders.firstOrNull { it.isTrash && it.fullName != folder.fullName }
            if (trash != null) {
                move(account, folder, msg, trash)
            } else {
                val client = ImapClient(config(account))
                client.setFlag(folder.fullName, msg.uid, "Deleted", true)
                client.expunge(folder.fullName)
                db.messageDao().deleteOne(folder.id, msg.uid)
            }
        }

    suspend fun move(account: AccountEntity, folder: FolderEntity, msg: MessageEntity, dest: FolderEntity) =
        withContext(Dispatchers.IO) {
            ImapClient(config(account)).move(folder.fullName, msg.uid, dest.fullName)
            db.messageDao().deleteOne(folder.id, msg.uid)
        }

    suspend fun send(account: AccountEntity, draft: ComposeDraft) = withContext(Dispatchers.IO) {
        val cfg = config(account)
        val message = SmtpClient.buildMessage(cfg, draft)
        SmtpClient(cfg).send(message)
        // 归档到服务器 Sent(服务器自动归档时失败,忽略)
        runCatching {
            val sent = db.folderDao().listNow(account.id).firstOrNull { it.isSent }
            if (sent != null) ImapClient(cfg).append(sent.fullName, message, "Seen")
        }
    }

    suspend fun saveDraft(account: AccountEntity, draft: ComposeDraft) = withContext(Dispatchers.IO) {
        val cfg = config(account)
        val message = SmtpClient.buildMessage(cfg, draft)
        val drafts = db.folderDao().listNow(account.id).firstOrNull { it.isDrafts }
            ?: throw IllegalStateException("服务器上没有草稿箱文件夹")
        ImapClient(cfg).append(drafts.fullName, message, "Draft")
    }

    suspend fun saveAttachment(account: AccountEntity, folder: FolderEntity, msg: MessageEntity, fileName: String, dest: File) =
        withContext(Dispatchers.IO) {
            ImapClient(config(account)).saveAttachment(folder.fullName, msg.uid, fileName, dest.absolutePath)
        }

    companion object {
        const val MessageFlagsSeen = 1
    }
}
