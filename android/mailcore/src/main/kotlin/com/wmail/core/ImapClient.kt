package com.wmail.core

import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.BodyPart
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.Flags as JFlags
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import com.sun.mail.imap.IMAPFolder
import java.util.Date
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JavaMail 封装:每类操作各开一条连接、用完即关,简单可靠。
 * IDLE 用独立长连接(idleLoop),不与操作连接互相阻塞(docs/sync.md §实时性)。
 */
class ImapClient(private val cfg: MailAccountConfig) {

    private fun session(idleTimeout: Boolean): Session {
        val ssl = cfg.imap.security == MailSecurity.SSL
        val protocol = if (ssl) "imaps" else "imap"
        val p = Properties().apply {
            this["mail.store.protocol"] = protocol
            this["mail.$protocol.host"] = cfg.imap.host
            this["mail.$protocol.connectiontimeout"] = "20000"
            // IDLE 连接要长读超时,操作连接短超时快速失败
            this["mail.$protocol.timeout"] = if (idleTimeout) "2100000" else "60000"
            if (cfg.imap.security == MailSecurity.STARTTLS) {
                this["mail.$protocol.starttls.enable"] = "true"
                this["mail.$protocol.starttls.required"] = "true"
            }
        }
        return Session.getInstance(p)
    }

    private fun connect(idleTimeout: Boolean = false): Store {
        val ssl = cfg.imap.security == MailSecurity.SSL
        val protocol = if (ssl) "imaps" else "imap"
        val store = session(idleTimeout).getStore(protocol)
        store.connect(cfg.imap.host, cfg.imap.port, cfg.email, cfg.password)
        return store
    }

    private fun openFolder(store: Store, fullName: String, readWrite: Boolean): Folder {
        val folder = store.getFolder(fullName)
            ?: throw IllegalStateException("服务器上没有文件夹:$fullName")
        folder.open(if (readWrite) Folder.READ_WRITE else Folder.READ_ONLY)
        return folder
    }

    fun listFolders(): List<MailFolderSummary> {
        val store = connect()
        try {
            val root = store.defaultFolder
            return root.list("*").map { f ->
                val full = f.fullName
                MailFolderSummary(
                    fullName = full,
                    name = full.substringAfterLast(f.separator),
                    delimiter = f.separator.takeIf { it.code != 0 },
                    semantic = inferSemantic(full),
                )
            }
        } finally {
            runCatching { store.close() }
        }
    }

    fun select(fullName: String): MailFolderState {
        val store = connect()
        try {
            val f = openFolder(store, fullName, readWrite = false) as IMAPFolder
            return MailFolderState(
                fullName = fullName,
                uidValidity = f.uidValidity,
                uidNext = f.uidNext,
                exists = f.messageCount,
                unread = f.unreadMessageCount,
            )
        } finally {
            runCatching { store.close() }
        }
    }

    /** 从新到旧取一页:offset=0 表示最新一页。 */
    fun fetchPage(fullName: String, offset: Int, count: Int): List<MailMessageSummary> {
        val store = connect()
        try {
            val f = openFolder(store, fullName, readWrite = false) as IMAPFolder
            val total = f.messageCount
            if (total == 0 || offset >= total) return emptyList()
            val realCount = minOf(count, total - offset)
            val start = total - offset - realCount + 1 // 1-based
            val msgs = f.getMessages(start, total - offset)

            val fp = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
                add(FetchProfile.Item.CONTENT_INFO)
                add(UID_PROFILE_ITEM)
                add("Content-Type")
            }
            f.fetch(msgs, fp)

            return msgs.map { m -> toSummary(f, m) }
        } finally {
            runCatching { store.close() }
        }
    }

    fun fetchBody(fullName: String, uid: Long): MailBody {
        val store = connect()
        try {
            val f = openFolder(store, fullName, readWrite = false) as IMAPFolder
            val msg = f.getMessageByUID(uid) ?: throw IllegalStateException("UID $uid 不存在")
            var html: String? = null
            var text: String? = null
            val atts = mutableListOf<MailAttachment>()

            fun walk(part: Part) {
                val disposition = try { part.disposition } catch (_: Exception) { null }
                val fileName = try { part.fileName } catch (_: Exception) { null }
                val isAttachment = disposition.equals(Part.ATTACHMENT, ignoreCase = true) || !fileName.isNullOrBlank()
                when {
                    isAttachment -> atts += MailAttachment(
                        fileName = fileName?.ifBlank { "attachment" } ?: "attachment",
                        contentType = part.contentType ?: "application/octet-stream",
                        sizeBytes = try { part.size.toLong() } catch (_: Exception) { 0L },
                    )
                    part.isMimeType("text/plain") && text == null ->
                        text = part.content as? String ?: text
                    part.isMimeType("text/html") && html == null ->
                        html = part.content as? String ?: html
                    part.isMimeType("multipart/*") ->
                        (part.content as? Multipart)?.let { mp ->
                            for (i in 0 until mp.count) walk(mp.getBodyPart(i))
                        }
                }
            }
            walk(msg)
            return MailBody(html = html, text = text, attachments = atts)
        } finally {
            runCatching { store.close() }
        }
    }

    fun saveAttachment(fullName: String, uid: Long, fileName: String, destPath: String) {
        val store = connect()
        try {
            val f = openFolder(store, fullName, readWrite = false) as IMAPFolder
            val msg = f.getMessageByUID(uid) ?: throw IllegalStateException("UID $uid 不存在")
            val target = findPart(msg, fileName) ?: throw IllegalStateException("附件不存在:$fileName")
            target.getInputStream().use { input ->
                java.io.File(destPath).outputStream().use { input.copyTo(it) }
            }
        } finally {
            runCatching { store.close() }
        }
    }

    private fun findPart(part: Part, fileName: String): Part? {
        val fn = try { part.fileName } catch (_: Exception) { null }
        if (fn == fileName) return part
        if (part.isMimeType("multipart/*")) {
            (part.content as? Multipart)?.let { mp ->
                for (i in 0 until mp.count) {
                    findPart(mp.getBodyPart(i), fileName)?.let { return it }
                }
            }
        }
        return null
    }

    fun setFlag(fullName: String, uid: Long, flag: String, value: Boolean) {
        val store = connect()
        try {
            val f = openFolder(store, fullName, readWrite = true) as IMAPFolder
            val msg = f.getMessageByUID(uid) ?: return
            val jf = when (flag) {
                "Seen" -> JFlags.Flag.SEEN
                "Flagged" -> JFlags.Flag.FLAGGED
                "Answered" -> JFlags.Flag.ANSWERED
                "Deleted" -> JFlags.Flag.DELETED
                "Draft" -> JFlags.Flag.DRAFT
                else -> return
            }
            msg.setFlag(jf, value)
        } finally {
            runCatching { store.close() }
        }
    }

    fun move(fullName: String, uid: Long, destFullName: String) {
        val store = connect()
        try {
            val src = openFolder(store, fullName, readWrite = true) as IMAPFolder
            val dest = openFolder(store, destFullName, readWrite = true)
            val msgs = src.getMessagesByUID(uid, uid)
            if (msgs.isNotEmpty()) src.moveMessages(msgs, dest)
        } finally {
            runCatching { store.close() }
        }
    }

    /** flag: null = 不设旗标;"Seen" / "Draft"。 */
    fun append(fullName: String, message: MimeMessage, flag: String?) {
        val store = connect()
        try {
            val f = openFolder(store, fullName, readWrite = true)
            if (flag != null) {
                val jf = when (flag) {
                    "Seen" -> JFlags.Flag.SEEN
                    "Draft" -> JFlags.Flag.DRAFT
                    else -> null
                }
                if (jf != null) message.setFlags(JFlags(jf), false)
            }
            f.appendMessages(arrayOf(message))
        } finally {
            runCatching { store.close() }
        }
    }

    fun expunge(fullName: String) {
        val store = connect()
        try {
            val f = openFolder(store, fullName, readWrite = true)
            f.expunge()
        } finally {
            runCatching { store.close() }
        }
    }

    fun search(fullName: String, query: String): List<Long> {
        val store = connect()
        try {
            val f = openFolder(store, fullName, readWrite = false) as IMAPFolder
            val term = javax.mail.search.OrTerm(
                javax.mail.search.SubjectTerm(query),
                javax.mail.search.FromStringTerm(query),
            )
            return f.search(term).map { f.getUID(it) }
        } finally {
            runCatching { store.close() }
        }
    }

    /** IDLE 长循环:独立连接、断线指数退避重连。新邮件回调 onMail。 */
    fun idleLoop(fullName: String, stop: AtomicBoolean, onMail: () -> Unit, onError: (String) -> Unit) {
        var backoff = 2_000L
        while (!stop.get()) {
            var store: Store? = null
            try {
                val s = connect(idleTimeout = true)
                store = s
                val f = openFolder(store, fullName, readWrite = true) as IMAPFolder
                backoff = 2_000L
                f.addMessageCountListener(object : javax.mail.event.MessageCountAdapter() {
                    override fun messagesAdded(e: javax.mail.event.MessageCountEvent) {
                        try { onMail() } catch (_: Exception) { }
                    }
                })
                while (!stop.get()) {
                    f.idle() // 阻塞;收到服务器事件后返回,循环重进
                }
            } catch (e: Exception) {
                if (!stop.get()) {
                    onError("IDLE 中断,自动重连:${e.message}")
                    try { Thread.sleep(backoff) } catch (_: InterruptedException) { return }
                    backoff = (backoff * 2).coerceAtMost(120_000L)
                }
            } finally {
                runCatching { store?.close() }
            }
        }
    }

    private fun toSummary(f: IMAPFolder, m: Message): MailMessageSummary {
        val mm = m as MimeMessage
        val from = (mm.from?.firstOrNull() as? InternetAddress)?.let { a ->
            a.personal?.let { "$it <${a.address}>" } ?: a.address
        } ?: ""
        val to = mm.getRecipients(Message.RecipientType.TO)
            ?.joinToString("; ") { (it as? InternetAddress)?.address ?: it.toString() }
            ?: ""
        val flags = buildList {
            if (mm.isSet(JFlags.Flag.SEEN)) add(MessageFlags.SEEN)
            if (mm.isSet(JFlags.Flag.FLAGGED)) add(MessageFlags.FLAGGED)
            if (mm.isSet(JFlags.Flag.ANSWERED)) add(MessageFlags.ANSWERED)
            if (mm.isSet(JFlags.Flag.DRAFT)) add(MessageFlags.DRAFT)
            if (mm.isSet(JFlags.Flag.DELETED)) add(MessageFlags.DELETED)
        }.fold(0) { acc, bit -> acc or bit }
        val ct = (mm.contentType ?: "").lowercase()
        // v0 附件启发式:顶层是 multipart/mixed / multipart/signed 基本都带附件
        val hasAtt = ct.contains("multipart/mixed") || ct.contains("multipart/signed")
        val date = mm.sentDate ?: mm.receivedDate ?: Date()
        return MailMessageSummary(
            uid = f.getUID(mm),
            messageId = runCatching { mm.messageID }.getOrNull(),
            subject = mm.subject ?: "",
            from = from,
            to = to,
            dateUnix = date.time / 1000,
            flags = flags,
            sizeBytes = mm.size.toLong(),
            hasAttachments = hasAtt,
        )
    }

    companion object {
        private val UID_PROFILE_ITEM = object : FetchProfile.Item("UID") {}

        fun inferSemantic(fullName: String): List<String> {
            val n = fullName.lowercase()
            return buildList {
                if (n.contains("sent") || n.contains("已发送")) add("sent")
                if (n.contains("draft") || n.contains("草稿")) add("drafts")
                if (n.contains("trash") || n.contains("deleted") || n.contains("已删除")) add("trash")
                if (n.contains("junk") || n.contains("spam") || n.contains("垃圾")) add("junk")
            }
        }
    }
}

class SmtpClient(private val cfg: MailAccountConfig) {

    private fun session(): Session {
        val ssl = cfg.smtp.security == MailSecurity.SSL
        val protocol = if (ssl) "smtps" else "smtp"
        val p = Properties().apply {
            this["mail.transport.protocol"] = protocol
            this["mail.$protocol.connectiontimeout"] = "20000"
            this["mail.$protocol.timeout"] = "60000"
            if (cfg.smtp.security == MailSecurity.STARTTLS) {
                this["mail.$protocol.starttls.enable"] = "true"
                this["mail.$protocol.starttls.required"] = "true"
            }
        }
        return Session.getInstance(p)
    }

    fun send(message: MimeMessage) {
        val ssl = cfg.smtp.security == MailSecurity.SSL
        val protocol = if (ssl) "smtps" else "smtp"
        val t = session().getTransport(protocol)
        t.connect(cfg.smtp.host, cfg.smtp.port, cfg.email, cfg.password)
        t.sendMessage(message, message.allRecipients)
        runCatching { t.close() }
    }

    companion object {
        fun buildMessage(cfg: MailAccountConfig, draft: ComposeDraft): MimeMessage {
            val msg = MimeMessage(Session.getInstance(Properties()))
            msg.setFrom(
                InternetAddress(
                    cfg.email,
                    cfg.displayName.ifBlank { cfg.email },
                ),
            )
            msg.setRecipients(Message.RecipientType.TO, draft.to.map { InternetAddress(it.trim()) }.toTypedArray())
            if (draft.cc.isNotEmpty()) msg.setRecipients(Message.RecipientType.CC, draft.cc.map { InternetAddress(it.trim()) }.toTypedArray())
            if (draft.bcc.isNotEmpty()) msg.setRecipients(Message.RecipientType.BCC, draft.bcc.map { InternetAddress(it.trim()) }.toTypedArray())
            msg.subject = draft.subject
            msg.sentDate = Date()
            draft.inReplyToMessageId?.let { id ->
                msg.setHeader("In-Reply-To", id)
                msg.setHeader("References", id)
            }

            val mixed = MimeMultipart("mixed")
            val textPart = MimeBodyPart().apply { setText(draft.body, "utf-8") }
            mixed.addBodyPart(textPart)
            for (path in draft.attachmentPaths) {
                val ds = FileDataSource(path)
                val part = MimeBodyPart().apply {
                    dataHandler = DataHandler(ds)
                    fileName = ds.name
                }
                mixed.addBodyPart(part)
            }
            msg.setContent(mixed)
            msg.saveChanges()
            return msg
        }

        fun quoteForReply(from: String, dateUnix: Long, body: String?): String {
            val date = Date(dateUnix * 1000)
            val quoted = (body ?: "").lineSequence().joinToString("\n") { "> $it" }
            return "\n\n于 $date,$from 写道:\n$quoted\n"
        }
    }
}
