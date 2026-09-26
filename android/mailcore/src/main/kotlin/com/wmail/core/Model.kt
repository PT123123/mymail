package com.wmail.core

enum class MailSecurity { SSL, STARTTLS, NONE }

data class MailEndpoint(val host: String, val port: Int, val security: MailSecurity)

data class MailAccountConfig(
    val id: String,
    val displayName: String,
    val email: String,
    val imap: MailEndpoint,
    val smtp: MailEndpoint,
    /** 只在内存中使用;磁盘上由上层用 Android Keystore 加密。 */
    val password: String,
)

data class MailFolderSummary(
    val fullName: String,
    val name: String,
    val delimiter: Char?,
    /** 名称启发式推断的语义标签:sent/drafts/trash/junk(v0 用名字推断,见 docs/sync.md)。 */
    val semantic: List<String>,
)

data class MailFolderState(
    val fullName: String,
    val uidValidity: Long,
    val uidNext: Long,
    val exists: Int,
    val unread: Int,
)

object MessageFlags {
    const val SEEN = 1
    const val FLAGGED = 2
    const val ANSWERED = 4
    const val DRAFT = 8
    const val DELETED = 16
}

data class MailMessageSummary(
    val uid: Long,
    val messageId: String?,
    val subject: String,
    val from: String,
    val to: String,
    val dateUnix: Long,
    val flags: Int,
    val sizeBytes: Long,
    val hasAttachments: Boolean,
)

data class MailAttachment(val fileName: String, val contentType: String, val sizeBytes: Long)

data class MailBody(
    val html: String?,
    val text: String?,
    val attachments: List<MailAttachment>,
)

data class ComposeDraft(
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val attachmentPaths: List<String> = emptyList(),
    val inReplyToMessageId: String? = null,
)
