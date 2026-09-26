package com.wmail.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 账户配置(密码不在库里,见 [com.wmail.app.data.SecretStore])。 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey var id: String,
    var displayName: String,
    var email: String,
    var imapHost: String,
    var imapPort: Int,
    var imapSecurity: String, // SSL / STARTTLS / NONE
    var smtpHost: String,
    var smtpPort: Int,
    var smtpSecurity: String,
)

@Entity(
    tableName = "folders",
    indices = [Index(value = ["accountId", "fullName"], unique = true)],
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    var accountId: String,
    var fullName: String,
    var name: String,
    var delimiter: String? = null,
    var semantics: String = "", // 逗号分隔:sent,drafts,trash,junk
    var uidValidity: Long = 1,
    var uidNext: Long = 1,
    var unreadCount: Int = 0,
    var totalCount: Int = 0,
) {
    val isSent: Boolean get() = semantics.contains("sent")
    val isDrafts: Boolean get() = semantics.contains("drafts")
    val isTrash: Boolean get() = semantics.contains("trash")
    val isJunk: Boolean get() = semantics.contains("junk")
    val displayName: String get() = if (fullName == "INBOX") "收件箱" else name
}

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["folderId", "uid"], unique = true),
        Index(value = ["folderId", "dateUnix"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    var accountId: String,
    var folderId: Long,
    var uid: Long,
    var messageId: String? = null,
    var subject: String = "",
    var fromAddr: String = "",
    var toAddrs: String = "",
    var dateUnix: Long = 0,
    var flags: Int = 0, // MessageFlags 位掩码
    var sizeBytes: Long = 0,
    var hasAttachments: Boolean = false,
    var snippet: String = "",
    var bodyHtmlPath: String? = null,
    var fetchedBody: Boolean = false,
) {
    val isSeen: Boolean get() = flags and 1 != 0
    val isFlagged: Boolean get() = flags and 2 != 0
}
