package com.wmail.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts")
    fun list(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts")
    suspend fun listNow(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE email = :email COLLATE NOCASE LIMIT 1")
    suspend fun findByEmail(email: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE accountId = :accountId ORDER BY fullName = 'INBOX' DESC, fullName")
    fun listFor(accountId: String): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE accountId = :accountId")
    suspend fun listNow(accountId: String): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun byId(id: Long): FolderEntity?

    @Query("SELECT * FROM folders WHERE accountId = :accountId AND fullName = :fullName")
    suspend fun find(accountId: String, fullName: String): FolderEntity?

    @Query("DELETE FROM folders WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("UPDATE folders SET uidValidity = :uidValidity, uidNext = :uidNext, unreadCount = :unread, totalCount = :total WHERE id = :id")
    suspend fun updateState(id: Long, uidValidity: Long, uidNext: Long, unread: Int, total: Int)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE folderId = :folderId ORDER BY dateUnix DESC, uid DESC LIMIT :limit")
    fun listFor(folderId: Long, limit: Int = 200): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE folderId = :folderId ORDER BY dateUnix DESC, uid DESC LIMIT :limit")
    suspend fun listNow(folderId: Long, limit: Int = 200): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE folderId = :folderId AND uid = :uid")
    suspend fun find(folderId: Long, uid: Long): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(messages: List<MessageEntity>)

    @Query("UPDATE messages SET flags = :flags WHERE folderId = :folderId AND uid = :uid")
    suspend fun updateFlags(folderId: Long, uid: Long, flags: Int)

    @Query("UPDATE messages SET snippet = :snippet, bodyHtmlPath = :htmlPath, fetchedBody = 1 WHERE folderId = :folderId AND uid = :uid")
    suspend fun updateBody(folderId: Long, uid: Long, snippet: String, htmlPath: String?)

    @Query("UPDATE messages SET hasAttachments = 1 WHERE folderId = :folderId AND uid = :uid")
    suspend fun markHasAttachments(folderId: Long, uid: Long)

    @Query("DELETE FROM messages WHERE folderId = :folderId")
    suspend fun deleteForFolder(folderId: Long)

    @Query("DELETE FROM messages WHERE folderId = :folderId AND uid = :uid")
    suspend fun deleteOne(folderId: Long, uid: Long)

    @Query("DELETE FROM messages WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE folderId = :folderId")
    suspend fun count(folderId: Long): Int

    @Query("SELECT MAX(uid) FROM messages WHERE folderId = :folderId")
    suspend fun maxUid(folderId: Long): Long?
}
