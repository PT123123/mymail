//! 本地 SQLite 缓存(结构见 docs/data-model.md)。Mutex<Connection> 串行化,开 WAL。

use crate::model::*;
use anyhow::Context;
use rusqlite::{params, Connection};
use std::sync::{Arc, Mutex};

pub type Db = Arc<Mutex<Connection>>;

pub fn open() -> Db {
    let _ = std::fs::create_dir_all(app_data_dir());
    let conn = Connection::open(app_data_dir().join("cache.db")).expect("打开缓存数据库失败");
    let _ = conn.pragma_update(None, "journal_mode", "WAL");
    ensure_tables(&conn);
    Arc::new(Mutex::new(conn))
}

fn ensure_tables(conn: &Connection) {
    conn.execute_batch(
        r#"
        CREATE TABLE IF NOT EXISTS folders(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          account_id TEXT NOT NULL,
          full_name TEXT NOT NULL,
          name TEXT NOT NULL,
          semantics TEXT,
          uid_validity INTEGER NOT NULL DEFAULT 1,
          uid_next INTEGER NOT NULL DEFAULT 1,
          unread_count INTEGER NOT NULL DEFAULT 0,
          total_count INTEGER NOT NULL DEFAULT 0,
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
          payload TEXT NOT NULL,
          created_at INTEGER NOT NULL
        );
        "#,
    )
    .expect("初始化缓存表失败");
}

// ---------- folders ----------

pub fn upsert_folder(conn: &Connection, f: &FolderInfo) -> Res<i64> {
    conn.query_row(
        r#"INSERT INTO folders(account_id, full_name, name, semantics, uid_validity, uid_next)
           VALUES(?1, ?2, ?3, ?4, ?5, ?6)
           ON CONFLICT(account_id, full_name) DO UPDATE SET
             name = ?3, semantics = ?4
           RETURNING id"#,
        params![f.account_id, f.full_name, f.name, f.semantics, f.uid_validity, f.uid_next],
        |r| r.get(0),
    )
    .context("upsert folder")
}

fn folder_from_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<FolderInfo> {
    Ok(FolderInfo {
        id: r.get(0)?,
        account_id: r.get(1)?,
        full_name: r.get(2)?,
        name: r.get(3)?,
        semantics: r.get::<_, Option<String>>(4)?.unwrap_or_default(),
        uid_validity: r.get::<_, i64>(5)? as u32,
        uid_next: r.get::<_, i64>(6)? as u32,
        unread: r.get(7)?,
        total: r.get(8)?,
    })
}

const FOLDER_COLS: &str =
    "id, account_id, full_name, name, semantics, uid_validity, uid_next, unread_count, total_count";

pub fn list_folders(conn: &Connection, account_id: &str) -> Res<Vec<FolderInfo>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT {FOLDER_COLS} FROM folders WHERE account_id = ?1 ORDER BY full_name = 'INBOX' DESC, full_name"
    ))?;
    let rows = stmt.query_map(params![account_id], folder_from_row)?;
    Ok(rows.collect::<Result<Vec<_>, _>>()?)
}

pub fn get_folder(conn: &Connection, id: i64) -> Res<Option<FolderInfo>> {
    let mut stmt = conn.prepare(&format!("SELECT {FOLDER_COLS} FROM folders WHERE id = ?1"))?;
    let mut rows = stmt.query_map(params![id], folder_from_row)?;
    Ok(rows.next().transpose()?)
}

pub fn update_folder_state(conn: &Connection, id: i64, uid_validity: u32, uid_next: u32, unread: i64, total: i64) -> Res<()> {
    conn.execute(
        "UPDATE folders SET uid_validity = ?2, uid_next = ?3, unread_count = ?4, total_count = ?5 WHERE id = ?1",
        params![id, uid_validity as i64, uid_next as i64, unread, total],
    )?;
    Ok(())
}

// ---------- messages ----------

pub fn delete_messages_by_folder(conn: &Connection, folder_id: i64) -> Res<()> {
    conn.execute("DELETE FROM messages WHERE folder_id = ?1", params![folder_id])?;
    Ok(())
}

pub fn upsert_messages(conn: &Connection, msgs: &[MessageHeader]) -> Res<()> {
    let mut stmt = conn.prepare(
        r#"INSERT INTO messages(account_id, folder_id, uid, message_id, subject, from_addr, to_addrs,
                                date_unix, flags, size_bytes, has_attach, snippet, body_html_path, fetched_body)
           VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14)
           ON CONFLICT(folder_id, uid) DO UPDATE SET
             message_id = ?4, subject = ?5, from_addr = ?6, to_addrs = ?7, date_unix = ?8,
             flags = ?9, size_bytes = ?10, has_attach = ?11"#,
    )?;
    for m in msgs {
        stmt.execute(params![
            m.account_id,
            m.folder_id,
            m.uid as i64,
            m.message_id,
            m.subject,
            m.from,
            m.to,
            m.date_unix,
            m.flags,
            m.size,
            m.has_attach as i64,
            m.snippet,
            m.body_path,
            m.fetched_body as i64,
        ])?;
    }
    Ok(())
}

const MSG_COLS: &str =
    "id, account_id, folder_id, uid, message_id, subject, from_addr, to_addrs, date_unix, flags, size_bytes, has_attach, snippet, body_html_path, fetched_body";

fn msg_from_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<MessageHeader> {
    Ok(MessageHeader {
        id: r.get(0)?,
        account_id: r.get(1)?,
        folder_id: r.get(2)?,
        uid: r.get::<_, i64>(3)? as u32,
        message_id: r.get(4)?,
        subject: r.get::<_, Option<String>>(5)?.unwrap_or_default(),
        from: r.get::<_, Option<String>>(6)?.unwrap_or_default(),
        to: r.get::<_, Option<String>>(7)?.unwrap_or_default(),
        date_unix: r.get(8)?,
        flags: r.get(9)?,
        size: r.get::<_, Option<i64>>(10)?.unwrap_or_default(),
        has_attach: r.get::<_, i64>(11)? != 0,
        snippet: r.get::<_, Option<String>>(12)?.unwrap_or_default(),
        body_path: r.get(13)?,
        fetched_body: r.get::<_, i64>(14)? != 0,
    })
}

pub fn list_messages(conn: &Connection, folder_id: i64, limit: i64) -> Res<Vec<MessageHeader>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT {MSG_COLS} FROM messages WHERE folder_id = ?1 ORDER BY date_unix DESC, uid DESC LIMIT ?2"
    ))?;
    let rows = stmt.query_map(params![folder_id, limit], msg_from_row)?;
    Ok(rows.collect::<Result<Vec<_>, _>>()?)
}

pub fn max_uid(conn: &Connection, folder_id: i64) -> Res<i64> {
    let v: Option<i64> = conn.query_row(
        "SELECT MAX(uid) FROM messages WHERE folder_id = ?1",
        params![folder_id],
        |r| r.get(0),
    )?;
    Ok(v.unwrap_or(0))
}

pub fn update_flags(conn: &Connection, folder_id: i64, uid: u32, flags: i64) -> Res<()> {
    conn.execute(
        "UPDATE messages SET flags = ?3 WHERE folder_id = ?1 AND uid = ?2",
        params![folder_id, uid as i64, flags],
    )?;
    Ok(())
}

pub fn update_body(conn: &Connection, folder_id: i64, uid: u32, snippet: &str, html_path: Option<&str>) -> Res<()> {
    conn.execute(
        "UPDATE messages SET snippet = ?3, body_html_path = ?4, fetched_body = 1 WHERE folder_id = ?1 AND uid = ?2",
        params![folder_id, uid as i64, snippet, html_path],
    )?;
    Ok(())
}

pub fn delete_message(conn: &Connection, folder_id: i64, uid: u32) -> Res<()> {
    conn.execute(
        "DELETE FROM messages WHERE folder_id = ?1 AND uid = ?2",
        params![folder_id, uid as i64],
    )?;
    Ok(())
}

pub fn set_has_attach(conn: &Connection, folder_id: i64, uid: u32, value: bool) -> Res<()> {
    conn.execute(
        "UPDATE messages SET has_attach = ?3 WHERE folder_id = ?1 AND uid = ?2",
        params![folder_id, uid as i64, value as i64],
    )?;
    Ok(())
}

pub fn find_folder_by_name(conn: &Connection, account_id: &str, full_name: &str) -> Res<Option<FolderInfo>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT {FOLDER_COLS} FROM folders WHERE account_id = ?1 AND full_name = ?2"
    ))?;
    let mut rows = stmt.query_map(params![account_id, full_name], folder_from_row)?;
    Ok(rows.next().transpose()?)
}

pub fn count_messages(conn: &Connection, folder_id: i64) -> Res<i64> {
    let v: i64 = conn.query_row(
        "SELECT COUNT(*) FROM messages WHERE folder_id = ?1",
        params![folder_id],
        |r| r.get(0),
    )?;
    Ok(v)
}

// ---------- 离线操作队列(见 docs/sync.md §离线)----------

pub fn enqueue_flag_op(conn: &Connection, account_id: &str, folder_name: &str, uid: u32, payload: &str) -> Res<()> {
    conn.execute(
        "INSERT INTO outbox_ops(account_id, folder_name, uid, payload, created_at) VALUES(?1, ?2, ?3, ?4, ?5)",
        params![account_id, folder_name, uid as i64, payload, chrono::Utc::now().timestamp()],
    )?;
    Ok(())
}

pub fn list_ops(conn: &Connection, account_id: &str) -> Res<Vec<(i64, String, u32, String)>> {
    let mut stmt = conn.prepare(
        "SELECT id, folder_name, uid, payload FROM outbox_ops WHERE account_id = ?1 ORDER BY id",
    )?;
    let rows = stmt.query_map(params![account_id], |r| {
        Ok((r.get(0)?, r.get::<_, Option<String>>(1)?.unwrap_or_default(), r.get::<_, i64>(2)? as u32, r.get(3)?))
    })?;
    Ok(rows.collect::<Result<Vec<_>, _>>()?)
}

pub fn remove_op(conn: &Connection, op_id: i64) -> Res<()> {
    conn.execute("DELETE FROM outbox_ops WHERE id = ?1", params![op_id])?;
    Ok(())
}
