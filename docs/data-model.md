# 数据模型

两端 UI/存储各自实现,但领域模型与表结构保持字段级对齐,便于对照排错和未来做配置导出/导入。

## 1. 领域对象

```
Account      账户:id, displayName, email, auth(user, secret),
             imap(host, port, security), smtp(host, port, security)
MailFolder   文件夹:id, account_id, fullName, name, delimiter,
             flags(\Sent \Drafts \Trash \Junk …), uidValidity, uidNext,
             unreadCount, totalCount
MailMessage  邮件:accountId, folderId, uid, messageId,
             subject, from, to/cc/bcc, date, flags{seen,flagged,answered,draft,deleted},
             sizeBytes, hasAttachments, snippet, bodyHtml?, bodyText?
Attachment   附件:filename, contentType, sizeBytes, partId, contentId(cid)
OutboxOp     待重放操作(见 sync.md §4)
```

## 2. 本地缓存表(两端同构)

```sql
folders(
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id    INTEGER NOT NULL,
  full_name     TEXT NOT NULL,
  name          TEXT NOT NULL,
  delimiter     TEXT,
  flags         TEXT,              -- 空格分隔的 IMAP 属性
  uid_validity  INTEGER NOT NULL,
  uid_next      INTEGER NOT NULL DEFAULT 1,
  unread_count  INTEGER NOT NULL DEFAULT 0,
  total_count   INTEGER NOT NULL DEFAULT 0,
  backfilled_uid_low INTEGER NOT NULL DEFAULT 0,   -- 全量回填断点
  UNIQUE(account_id, full_name)
)

messages(
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id    INTEGER NOT NULL,
  folder_id     INTEGER NOT NULL,
  uid           INTEGER NOT NULL,
  message_id    TEXT,
  subject       TEXT,
  from_addr     TEXT,   -- "Name <a@b.c>"
  to_addrs      TEXT,
  date_unix     INTEGER NOT NULL,
  flags         INTEGER NOT NULL,  -- bit0 Seen bit1 Flagged bit2 Answered bit3 Draft bit4 Deleted
  size_bytes    INTEGER,
  has_attach    INTEGER NOT NULL DEFAULT 0,
  snippet       TEXT,              -- 正文纯文本前 200 字(拉正文后回填)
  body_html_path TEXT,             -- 正文缓存文件路径,按需写
  fetched_body  INTEGER NOT NULL DEFAULT 0,
  UNIQUE(folder_id, uid)
)
CREATE INDEX idx_messages_folder_date ON messages(folder_id, date_unix DESC);
CREATE VIRTUAL TABLE messages_fts USING fts5(subject, from_addr, snippet, content='messages', content_rowid='id');

outbox_ops(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id INTEGER NOT NULL,
  folder_name TEXT, uid INTEGER,
  op_type TEXT NOT NULL,            -- set_flags / move / delete / send / append_draft
  payload_json TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  retry_count INTEGER NOT NULL DEFAULT 0
)
```

## 3. 账户与凭据(两端有意不同)

| 端 | 存哪 | 密码保护 |
|---|---|---|
| Windows | `%LOCALAPPDATA%\w-mail\accounts.json`(账户/服务器配置) | 密码字段经 **DPAPI**(`CryptProtectData`,CurrentUser)加密后 base64 存 JSON |
| Android | Room `accounts` 表(配置) | 密码存 **EncryptedSharedPreferences**(Android Keystore),DB 里只放引用标志 |

配置不跨端同步(不把账户配置写进 IMAP),避免"一台设备删账户、另一端也丢"的意外;导出/导入功能放 M2 再做。

## 4. 正文缓存策略

- 拉取正文时:净化后的 HTML 落到缓存目录(`%LOCALAPPDATA%\w-mail\bodies` / `context.cacheDir`),DB 记路径与 `fetched_body=1`;
- 列表页永不拉正文,只读 DB;
- 缓存上限(默认 512MB,LRU 清理最久未读正文)放 M1。
