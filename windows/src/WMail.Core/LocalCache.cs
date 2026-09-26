using Microsoft.Data.Sqlite;

namespace WMail.Core;

/// <summary>
/// 本地 SQLite 缓存(结构见 docs/data-model.md)。单连接 + 锁串行化,开 WAL。
/// </summary>
public sealed class LocalCache : IDisposable
{
    private readonly SqliteConnection _conn;
    private readonly SemaphoreSlim _lock = new(1, 1);

    public LocalCache()
    {
        Paths.EnsureDirs();
        var dbPath = Path.Combine(Paths.AppDataDir, "cache.db");
        _conn = new SqliteConnection($"Data Source={dbPath}");
        _conn.Open();
        using var cmd = _conn.CreateCommand();
        cmd.CommandText = "PRAGMA journal_mode=WAL;";
        cmd.ExecuteNonQuery();
        EnsureTables();
    }

    private void EnsureTables()
    {
        Exec("""
            CREATE TABLE IF NOT EXISTS folders(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              account_id TEXT NOT NULL,
              full_name TEXT NOT NULL,
              name TEXT NOT NULL,
              delimiter TEXT,
              flags TEXT,
              uid_validity INTEGER NOT NULL DEFAULT 1,
              uid_next INTEGER NOT NULL DEFAULT 1,
              unread_count INTEGER NOT NULL DEFAULT 0,
              total_count INTEGER NOT NULL DEFAULT 0,
              backfilled_count INTEGER NOT NULL DEFAULT 0,
              UNIQUE(account_id, full_name)
            );

            CREATE TABLE IF NOT EXISTS messages(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              account_id TEXT NOT NULL,
              folder_id INTEGER NOT NULL,
              uid INTEGER NOT NULL,
              message_id TEXT,
              subject TEXT,
              from_addr TEXT,
              to_addrs TEXT,
              date_unix INTEGER NOT NULL,
              flags INTEGER NOT NULL,
              size_bytes INTEGER,
              has_attach INTEGER NOT NULL DEFAULT 0,
              snippet TEXT,
              body_html_path TEXT,
              fetched_body INTEGER NOT NULL DEFAULT 0,
              UNIQUE(folder_id, uid)
            );
            CREATE INDEX IF NOT EXISTS idx_messages_folder_date ON messages(folder_id, date_unix DESC, uid DESC);

            CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
              subject, from_addr, snippet, content='messages', content_rowid='id'
            );
            CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
              INSERT INTO messages_fts(rowid, subject, from_addr, snippet)
              VALUES (new.id, IFNULL(new.subject,''), IFNULL(new.from_addr,''), IFNULL(new.snippet,''));
            END;
            CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
              INSERT INTO messages_fts(messages_fts, rowid, subject, from_addr, snippet)
              VALUES('delete', old.id, IFNULL(old.subject,''), IFNULL(old.from_addr,''), IFNULL(old.snippet,''));
            END;
            CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE ON messages BEGIN
              INSERT INTO messages_fts(messages_fts, rowid, subject, from_addr, snippet)
              VALUES('delete', old.id, IFNULL(old.subject,''), IFNULL(old.from_addr,''), IFNULL(old.snippet,''));
              INSERT INTO messages_fts(rowid, subject, from_addr, snippet)
              VALUES (new.id, IFNULL(new.subject,''), IFNULL(new.from_addr,''), IFNULL(new.snippet,''));
            END;

            CREATE TABLE IF NOT EXISTS outbox_ops(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              account_id TEXT NOT NULL,
              folder_name TEXT,
              uid INTEGER,
              op_type TEXT NOT NULL,
              payload_json TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              retry_count INTEGER NOT NULL DEFAULT 0
            );
            """);
    }

    private void Exec(string sql)
    {
        using var cmd = _conn.CreateCommand();
        cmd.CommandText = sql;
        cmd.ExecuteNonQuery();
    }

    private static SqliteCommand Cmd(SqliteConnection c, string sql) => new(sql, c);

    // ---------- folders ----------

    public async Task<int> UpsertFolderAsync(FolderInfo f)
    {
        await _lock.WaitAsync();
        try
        {
            using var cmd = Cmd(_conn, """
                INSERT INTO folders(account_id, full_name, name, delimiter, flags, uid_validity, uid_next)
                VALUES(@acc, @full, @name, @delim, @flags, @uv, @un)
                ON CONFLICT(account_id, full_name) DO UPDATE SET
                  name=@name, delimiter=@delim, flags=@flags
                RETURNING id;
                """);
            cmd.Parameters.AddWithValue("@acc", f.AccountId.ToString());
            cmd.Parameters.AddWithValue("@full", f.FullName);
            cmd.Parameters.AddWithValue("@name", f.Name);
            cmd.Parameters.AddWithValue("@delim", (object?)f.Delimiter ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@flags", f.Flags);
            cmd.Parameters.AddWithValue("@uv", f.UidValidity);
            cmd.Parameters.AddWithValue("@un", f.UidNext);
            var result = await cmd.ExecuteScalarAsync();
            return Convert.ToInt32(result);
        }
        finally { _lock.Release(); }
    }

    public async Task<List<FolderInfo>> ListFoldersAsync(Guid accountId)
    {
        await _lock.WaitAsync();
        try
        {
            var list = new List<FolderInfo>();
            using var cmd = Cmd(_conn, "SELECT id, full_name, name, delimiter, flags, uid_validity, uid_next, unread_count, total_count, backfilled_count FROM folders WHERE account_id=@acc ORDER BY full_name;");
            cmd.Parameters.AddWithValue("@acc", accountId.ToString());
            using var r = await cmd.ExecuteReaderAsync();
            while (await r.ReadAsync())
            {
                list.Add(new FolderInfo
                {
                    Id = r.GetInt32(0),
                    FullName = r.GetString(1),
                    Name = r.GetString(2),
                    Delimiter = r.IsDBNull(3) ? null : r.GetString(3),
                    Flags = r.IsDBNull(4) ? "" : r.GetString(4),
                    UidValidity = (uint)r.GetInt64(5),
                    UidNext = r.GetInt32(6),
                    UnreadCount = r.GetInt32(7),
                    TotalCount = r.GetInt32(8),
                    BackfilledCount = r.GetInt32(9),
                });
            }
            return list;
        }
        finally { _lock.Release(); }
    }

    public async Task<FolderInfo?> GetFolderAsync(int folderId)
    {
        await _lock.WaitAsync();
        try
        {
            using var cmd = Cmd(_conn, "SELECT id, full_name, name, delimiter, flags, uid_validity, uid_next, unread_count, total_count, backfilled_count FROM folders WHERE id=@id;");
            cmd.Parameters.AddWithValue("@id", folderId);
            using var r = await cmd.ExecuteReaderAsync();
            if (!await r.ReadAsync()) return null;
            return new FolderInfo
            {
                Id = r.GetInt32(0), FullName = r.GetString(1), Name = r.GetString(2),
                Delimiter = r.IsDBNull(3) ? null : r.GetString(3),
                Flags = r.IsDBNull(4) ? "" : r.GetString(4),
                UidValidity = (uint)r.GetInt64(5), UidNext = r.GetInt32(6),
                UnreadCount = r.GetInt32(7), TotalCount = r.GetInt32(8), BackfilledCount = r.GetInt32(9),
            };
        }
        finally { _lock.Release(); }
    }

    public async Task UpdateFolderStateAsync(int folderId, uint uidValidity, int uidNext, int unread, int total)
    {
        await _lock.WaitAsync();
        try
        {
            using var cmd = Cmd(_conn, "UPDATE folders SET uid_validity=@uv, uid_next=@un, unread_count=@ur, total_count=@tc WHERE id=@id;");
            cmd.Parameters.AddWithValue("@uv", (long)uidValidity);
            cmd.Parameters.AddWithValue("@un", uidNext);
            cmd.Parameters.AddWithValue("@ur", unread);
            cmd.Parameters.AddWithValue("@tc", total);
            cmd.Parameters.AddWithValue("@id", folderId);
            await cmd.ExecuteNonQueryAsync();
        }
        finally { _lock.Release(); }
    }

    public async Task SetFolderFlagsAsync(int folderId, string flags)
    {
        await _lock.WaitAsync();
        try
        {
            using var cmd = Cmd(_conn, "UPDATE folders SET flags=@f WHERE id=@id;");
            cmd.Parameters.AddWithValue("@f", flags);
            cmd.Parameters.AddWithValue("@id", folderId);
            await cmd.ExecuteNonQueryAsync();
        }
        finally { _lock.Release(); }
    }

    public async Task DeleteMessagesByFolderAsync(int folderId)
    {
        await _lock.WaitAsync();
        try
        {
            using var cmd = Cmd(_conn, "DELETE FROM messages WHERE folder_id=@id;");
            cmd.Parameters.AddWithValue("@id", folderId);
            await cmd.ExecuteNonQueryAsync();
        }
        finally { _lock.Release(); }
    }

    // ---------- messages ----------

    public async Task UpsertMessagesAsync(IReadOnlyList<MessageHeader> messages)
    {
        if (messages.Count == 0) return;
        await _lock.WaitAsync();
        try
        {
            using var tx = _conn.BeginTransaction();
            foreach (var m in messages)
            {
                using var cmd = Cmd(_conn, """
                    INSERT INTO messages(account_id, folder_id, uid, message_id, subject, from_addr, to_addrs,
                                         date_unix, flags, size_bytes, has_attach, snippet, body_html_path, fetched_body)
                    VALUES(@acc, @fid, @uid, @mid, @subj, @from, @to, @date, @flags, @size, @att, @snip, @path, @fetched)
                    ON CONFLICT(folder_id, uid) DO UPDATE SET
                      message_id=@mid, subject=@subj, from_addr=@from, to_addrs=@to, date_unix=@date,
                      flags=@flags, size_bytes=@size, has_attach=@att;
                    """);
                cmd.Parameters.AddWithValue("@acc", m.AccountId.ToString());
                cmd.Parameters.AddWithValue("@fid", m.FolderId);
                cmd.Parameters.AddWithValue("@uid", (long)m.Uid);
                cmd.Parameters.AddWithValue("@mid", (object?)m.MessageId ?? DBNull.Value);
                cmd.Parameters.AddWithValue("@subj", m.Subject);
                cmd.Parameters.AddWithValue("@from", m.From);
                cmd.Parameters.AddWithValue("@to", m.To);
                cmd.Parameters.AddWithValue("@date", m.DateUnix);
                cmd.Parameters.AddWithValue("@flags", (int)m.Flags);
                cmd.Parameters.AddWithValue("@size", m.SizeBytes);
                cmd.Parameters.AddWithValue("@att", m.HasAttachments ? 1 : 0);
                cmd.Parameters.AddWithValue("@snip", m.Snippet);
                cmd.Parameters.AddWithValue("@path", (object?)m.BodyHtmlPath ?? DBNull.Value);
                cmd.Parameters.AddWithValue("@fetched", m.FetchedBody ? 1 : 0);
                await cmd.ExecuteNonQueryAsync();
            }
            tx.Commit();
        }
        finally { _lock.Release(); }
    }

    public async Task<List<MessageHeader>> ListMessagesAsync(int folderId, int offset, int count)
    {
        await _lock.WaitAsync();
        try
        {
            var list = new List<MessageHeader>();
            using var cmd = Cmd(_conn, """
                SELECT id, account_id, folder_id, uid, message_id, subject, from_addr, to_addrs,
                       date_unix, flags, size_bytes, has_attach, snippet, body_html_path, fetched_body
                FROM messages WHERE folder_id=@fid
                ORDER BY date_unix DESC, uid DESC LIMIT @limit OFFSET @offset;
                """);
            cmd.Parameters.AddWithValue("@fid", folderId);
            cmd.Parameters.AddWithValue("@limit", count);
            cmd.Parameters.AddWithValue("@offset", offset);
            using var r = await cmd.ExecuteReaderAsync();
            while (await r.ReadAsync())
            {
                list.Add(ReadHeader(r));
            }
            return list;
        }
        finally { _lock.Release(); }
    }

    private static MessageHeader ReadHeader(SqliteDataReader r) => new()
    {
        Id = r.GetInt32(0),
        AccountId = Guid.Parse(r.GetString(1)),
        FolderId = r.GetInt32(2),
        Uid = (uint)r.GetInt64(3),
        MessageId = r.IsDBNull(4) ? null : r.GetString(4),
        Subject = r.IsDBNull(5) ? "" : r.GetString(5),
        From = r.IsDBNull(6) ? "" : r.GetString(6),
        To = r.IsDBNull(7) ? "" : r.GetString(7),
        DateUnix = r.GetInt64(8),
        Flags = (MessageFlagBits)r.GetInt64(9),
        SizeBytes = r.IsDBNull(10) ? 0 : r.GetInt64(10),
        HasAttachments = r.GetInt64(11) != 0,
        Snippet = r.IsDBNull(12) ? "" : r.GetString(12),
        BodyHtmlPath = r.IsDBNull(13) ? null : r.GetString(13),
        FetchedBody = r.GetInt64(14) != 0,
    };

    private const string HeaderColumns = """
        id, account_id, folder_id, uid, message_id, subject, from_addr, to_addrs,
        date_unix, flags, size_bytes, has_attach, snippet, body_html_path, fetched_body
        """;

    public async Task<MessageHeader?> GetMessageAsync(int folderId, uint uid)
    {
        await _lock.WaitAsync();
        try
        {
            using var cmd = Cmd(_conn, $"SELECT {HeaderColumns} FROM messages WHERE folder_id=@fid AND uid=@uid;");
            cmd.Parameters.AddWithValue("@fid", folderId);
            cmd.Parameters.AddWithValue("@uid", (long)uid);
            using var r = await cmd.ExecuteReaderAsync();
            return await r.ReadAsync() ? ReadHeader(r) : null;
        }
        finally { _lock.Release(); }
    }

    public async Task UpdateMessageFlagsAsync(int folderId, uint uid, MessageFlagBits flags)
    {
        await _lock.WaitAsync();
        try
        {
            using var cmd = Cmd(_conn, "UPDATE messages SET flags=@fl WHERE folder_id=@fid AND uid=@uid;");
            cmd.Parameters.AddWithValue("@fl", (int)flags);
            cmd.Parameters.AddWithValue("@fid", folderId);
            cmd.Parameters.AddWithValue("@uid", (long)uid);
            await cmd.ExecuteNonQueryAsync();
        }
        finally { _lock.Release(); }
    }

    public async Task UpdateBodyAsync(int folderId, uint uid, string snippet, string? htmlPath)
    {
        await _lock.WaitAsync();
        try
        {
            using var cmd = Cmd(_conn, "UPDATE messages SET snippet=@snip, body_html_path=@path, fetched_body=1 WHERE folder_id=@fid AND uid=@uid;");
            cmd.Parameters.AddWithValue("@snip", snippet);
            cmd.Parameters.AddWithValue("@path", (object?)htmlPath ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@fid", folderId);
            cmd.Parameters.AddWithValue("@uid", (long)uid);
            await cmd.ExecuteNonQueryAsync();
        }
        finally { _lock.Release(); }
    }

    public async Task DeleteMessageAsync(int folderId, uint uid)
    {
        await _lock.WaitAsync();
        try
        {
            using var cmd = Cmd(_conn, "DELETE FROM messages WHERE folder_id=@fid AND uid=@uid;");
            cmd.Parameters.AddWithValue("@fid", folderId);
            cmd.Parameters.AddWithValue("@uid", (long)uid);
            await cmd.ExecuteNonQueryAsync();
        }
        finally { _lock.Release(); }
    }

    public async Task<int> CountMessagesAsync(int folderId)
    {
        await _lock.WaitAsync();
        try
        {
            using var cmd = Cmd(_conn, "SELECT COUNT(*) FROM messages WHERE folder_id=@fid;");
            cmd.Parameters.AddWithValue("@fid", folderId);
            var o = await cmd.ExecuteScalarAsync();
            return Convert.ToInt32(o);
        }
        finally { _lock.Release(); }
    }

    public async Task<List<MessageHeader>> SearchAsync(string query, int limit = 100)
    {
        await _lock.WaitAsync();
        try
        {
            var list = new List<MessageHeader>();
            using var cmd = Cmd(_conn, $"""
                SELECT {HeaderColumns} FROM messages
                WHERE id IN (SELECT rowid FROM messages_fts WHERE messages_fts MATCH @q LIMIT @limit)
                ORDER BY date_unix DESC;
                """);
            cmd.Parameters.AddWithValue("@q", query.Replace("\"", ""));
            cmd.Parameters.AddWithValue("@limit", limit);
            using var r = await cmd.ExecuteReaderAsync();
            while (await r.ReadAsync()) list.Add(ReadHeader(r));
            return list;
        }
        finally { _lock.Release(); }
    }

    // ---------- outbox ops(离线队列,重放见 sync.md)----------

    public async Task EnqueueOpAsync(Guid accountId, string? folderName, uint uid, string opType, string payloadJson)
    {
        await _lock.WaitAsync();
        try
        {
            using var cmd = Cmd(_conn, """
                INSERT INTO outbox_ops(account_id, folder_name, uid, op_type, payload_json, created_at)
                VALUES(@acc, @fn, @uid, @op, @payload, @ts);
                """);
            cmd.Parameters.AddWithValue("@acc", accountId.ToString());
            cmd.Parameters.AddWithValue("@fn", (object?)folderName ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@uid", (long)uid);
            cmd.Parameters.AddWithValue("@op", opType);
            cmd.Parameters.AddWithValue("@payload", payloadJson);
            cmd.Parameters.AddWithValue("@ts", DateTimeOffset.Now.ToUnixTimeSeconds());
            await cmd.ExecuteNonQueryAsync();
        }
        finally { _lock.Release(); }
    }

    public async Task RemoveOpAsync(long opId)
    {
        await _lock.WaitAsync();
        try
        {
            using var cmd = Cmd(_conn, "DELETE FROM outbox_ops WHERE id=@id;");
            cmd.Parameters.AddWithValue("@id", opId);
            await cmd.ExecuteNonQueryAsync();
        }
        finally { _lock.Release(); }
    }

    public void Dispose()
    {
        _conn.Dispose();
        _lock.Dispose();
    }
}
