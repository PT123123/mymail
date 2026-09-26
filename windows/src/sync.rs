//! 每账户一个引擎:服务器 ↔ 本地缓存同步、IDLE、写操作(对应 docs/sync.md)。
//! 标志类方法不修改传入 header,而是返回新的 flags 值,由调用方更新状态。

use crate::db::{self, Db};
use crate::imap::MailClient;
use crate::model::*;
use crate::smtp;
use crate::Ui;
use anyhow::{anyhow, Context};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

pub struct Engine {
    pub account: AccountConfig,
    db: Db,
    client: MailClient,
    idle_started: AtomicBool,
}

impl Engine {
    pub fn new(account: AccountConfig, db: Db) -> Res<Self> {
        Ok(Self {
            client: MailClient::new(account.clone())?,
            account,
            db,
            idle_started: AtomicBool::new(false),
        })
    }

    fn conn(&self) -> std::sync::MutexGuard<'_, rusqlite::Connection> {
        self.db.lock().unwrap()
    }

    // ---------- 文件夹 ----------

    pub fn refresh_folders(&self) -> Res<Vec<FolderInfo>> {
        let folders = self.client.list_folders()?;
        {
            let conn = self.conn();
            for (full, sem) in folders {
                if sem.split(',').any(|s| s == "noselect") {
                    continue;
                }
                let name = full
                    .rsplit(|c| c == '/' || c == '.')
                    .next()
                    .unwrap_or(&full)
                    .to_string();
                let info = FolderInfo {
                    id: 0,
                    account_id: self.account.id.clone(),
                    full_name: full,
                    name,
                    semantics: sem,
                    uid_validity: 1,
                    uid_next: 1,
                    unread: 0,
                    total: 0,
                };
                db::upsert_folder(&conn, &info)?;
            }
        }
        self.list_folders()
    }

    pub fn list_folders(&self) -> Res<Vec<FolderInfo>> {
        let conn = self.conn();
        db::list_folders(&conn, &self.account.id)
    }

    fn folder_of(&self, folder_id: i64) -> Res<FolderInfo> {
        let conn = self.conn();
        db::get_folder(&conn, folder_id)?.ok_or_else(|| anyhow!("文件夹不存在"))
    }

    // ---------- 同步 ----------

    /// 拉最新页并入缓存;返回新邮件数。
    pub fn sync_newest(&self, folder: &FolderInfo, newest: usize) -> Res<usize> {
        let (uv, un, exists, unread) = self.client.select(&folder.full_name)?;
        {
            let conn = self.conn();
            if uv != folder.uid_validity {
                db::delete_messages_by_folder(&conn, folder.id)?;
            }
            db::update_folder_state(&conn, folder.id, uv, un, unread, exists as i64)?;
        }
        let page = self.client.fetch_page(
            &self.account.id,
            folder.id,
            &folder.full_name,
            0,
            newest,
            exists,
        )?;
        let new_count = {
            let conn = self.conn();
            let old_max = db::max_uid(&conn, folder.id)?;
            db::upsert_messages(&conn, &page)?;
            page.iter().filter(|h| h.uid as i64 > old_max).count()
        };
        Ok(new_count)
    }

    /// 打开文件夹:同步最新页 → 从缓存载入列表 → 后台回填 →(INBOX)IDLE →重放离线队列。
    pub fn open_folder(self: &Arc<Self>, folder: FolderInfo, ui: Ui) {
        match self.sync_newest(&folder, 200) {
            Ok(_) => {
                // 从缓存重读一次,拿到 sync_newest 更新过的 total/uid_validity
                let fresh = self.folder_of(folder.id).unwrap_or(folder.clone());
                ui.reload_folders_from_db();
                ui.reload_messages_from_db(fresh.id);
                ui.status(format!("已同步 {}", fresh.name));
                if fresh.full_name == "INBOX" {
                    self.spawn_idle(ui.clone());
                }
                self.spawn_backfill(fresh, ui.clone());
            }
            Err(e) => {
                // 离线:照样显示缓存
                ui.status(format!("同步失败,显示本地缓存:{e}"));
                ui.reload_messages_from_db(folder.id);
            }
        }
        self.drain_ops(&ui);
    }

    fn spawn_backfill(self: &Arc<Self>, folder: FolderInfo, ui: Ui) {
        let eng = self.clone();
        std::thread::spawn(move || {
            let mut failures = 0u32;
            loop {
                let (cached, total) = {
                    let conn = eng.conn();
                    (
                        db::count_messages(&conn, folder.id).unwrap_or(0),
                        folder.total,
                    )
                };
                if cached >= total {
                    break;
                }
                match eng.client.fetch_page(
                    &eng.account.id,
                    folder.id,
                    &folder.full_name,
                    cached as usize,
                    500,
                    total as u32,
                ) {
                    Ok(page) => {
                        if page.is_empty() {
                            break;
                        }
                        let _ = {
                            let conn = eng.conn();
                            db::upsert_messages(&conn, &page)
                        };
                        ui.reload_messages_from_db(folder.id);
                        failures = 0;
                    }
                    Err(e) => {
                        failures += 1;
                        if failures >= 3 {
                            ui.status(format!("后台回填暂停:{e}"));
                            break;
                        }
                        std::thread::sleep(Duration::from_secs(10));
                    }
                }
            }
            ui.status(format!("回填完成:{}", folder.name));
        });
    }

    fn spawn_idle(self: &Arc<Self>, ui: Ui) {
        if self.idle_started.swap(true, Ordering::SeqCst) {
            return;
        }
        let eng = self.clone();
        std::thread::spawn(move || {
            let mut backoff = 2u64;
            loop {
                let result = eng.client.idle_session_loop("INBOX", |_exists| {
                    let inbox = {
                        let conn = eng.conn();
                        db::find_folder_by_name(&conn, &eng.account.id, "INBOX")?
                    };
                    if let Some(inbox) = inbox {
                        let new_count = eng.sync_newest(&inbox, 50).unwrap_or(0);
                        if new_count > 0 {
                            ui.status(format!("收到 {new_count} 封新邮件"));
                            ui.reload_folders_from_db();
                            ui.reload_messages_from_db(inbox.id);
                        }
                    }
                    Ok(())
                });
                match result {
                    Ok(()) => break, // 正常不会返回
                    Err(e) => {
                        ui.status(format!("实时同步中断,自动重连:{e}"));
                        std::thread::sleep(Duration::from_secs(backoff));
                        backoff = (backoff * 2).min(120);
                    }
                }
            }
        });
    }

    pub fn drain_ops(&self, ui: &Ui) {
        let ops = {
            let conn = self.conn();
            db::list_ops(&conn, &self.account.id).unwrap_or_default()
        };
        if ops.is_empty() {
            return;
        }
        for (op_id, folder_name, uid, payload) in ops {
            // payload 格式:"flag:Seen:1"
            let parts: Vec<&str> = payload.split(':').collect();
            if parts.len() == 3 && parts[0] == "flag" {
                match self.client.set_flag(&folder_name, uid, parts[1], parts[2] == "1") {
                    Ok(()) => {
                        let conn = self.conn();
                        let _ = db::remove_op(&conn, op_id);
                    }
                    Err(_) => break, // 仍离线,下次再试
                }
            } else {
                let conn = self.conn();
                let _ = db::remove_op(&conn, op_id);
            }
        }
        let remaining = {
            let conn = self.conn();
            db::list_ops(&conn, &self.account.id).map_or(0, |ops| ops.len())
        };
        if remaining > 0 {
            ui.status(format!("{remaining} 个离线改动待服务器恢复后重试"));
        }
    }

    // ---------- 读信 ----------

    /// 返回 (正文, 净化 HTML 缓存路径, 最新 flags)
    pub fn fetch_body(&self, header: &MessageHeader) -> Res<(MailBody, Option<String>, i64)> {
        let folder = self.folder_of(header.folder_id)?;

        // 无附件且已缓存 → 直接读缓存;带附件的重拉以取附件列表
        if header.fetched_body && !header.has_attach {
            if let Some(p) = &header.body_path {
                if Path::new(p).exists() {
                    let html = std::fs::read_to_string(p).ok();
                    return Ok((
                        MailBody {
                            html,
                            text: Some(header.snippet.clone()),
                            attachments: Vec::new(),
                        },
                        header.body_path.clone(),
                        header.flags,
                    ));
                }
            }
        }

        let body = self
            .client
            .fetch_body(&folder.full_name, header.uid)
            .context("拉取正文失败")?;
        let html = body.html.map(|h| sanitize_html(&h));
        let mut html_path = None;
        if let Some(h) = &html {
            let path = bodies_dir().join(format!("{}_{}.html", folder.id, header.uid));
            std::fs::write(&path, h)?;
            html_path = Some(path.to_string_lossy().into_owned());
        }
        let text = body
            .text
            .clone()
            .or_else(|| html.as_ref().map(|h| html_to_text(h)));
        let snippet: String = text
            .as_deref()
            .unwrap_or("(HTML 邮件)")
            .replace(['\r', '\n'], " ")
            .chars()
            .take(200)
            .collect();

        let new_flags = if header.is_seen() {
            header.flags
        } else {
            header.flags | FLAG_SEEN
        };

        {
            let conn = self.conn();
            db::update_body(&conn, folder.id, header.uid, &snippet, html_path.as_deref())?;
            if !body.attachments.is_empty() {
                db::set_has_attach(&conn, folder.id, header.uid, true)?;
            }
            if new_flags != header.flags {
                db::update_flags(&conn, folder.id, header.uid, new_flags)?;
            }
        }

        if new_flags != header.flags {
            if let Err(e) = self.client.set_flag(&folder.full_name, header.uid, "Seen", true) {
                self.enqueue_flag(&folder.full_name, header.uid, "Seen", true);
                eprintln!("已读标记已入离线队列:{e}");
            }
        }

        Ok((
            MailBody {
                html,
                text,
                attachments: body.attachments,
            },
            html_path,
            new_flags,
        ))
    }

    pub fn save_attachment(&self, header: &MessageHeader, file_name: &str, dest: &Path) -> Res<()> {
        let folder = self.folder_of(header.folder_id)?;
        self.client
            .save_attachment(&folder.full_name, header.uid, file_name, dest)
    }

    // ---------- 标志 / 移动 / 删除 ----------

    pub fn set_seen(&self, header: &MessageHeader, value: bool) -> Res<i64> {
        let folder = self.folder_of(header.folder_id)?;
        let new_flags = if value {
            header.flags | FLAG_SEEN
        } else {
            header.flags & !FLAG_SEEN
        };
        {
            let conn = self.conn();
            db::update_flags(&conn, folder.id, header.uid, new_flags)?;
        }
        self.client
            .set_flag(&folder.full_name, header.uid, "Seen", value)
            .inspect_err(|_| self.enqueue_flag(&folder.full_name, header.uid, "Seen", value))?;
        Ok(new_flags)
    }

    pub fn toggle_flag(&self, header: &MessageHeader) -> Res<i64> {
        let folder = self.folder_of(header.folder_id)?;
        let value = !header.is_flagged();
        let new_flags = if value {
            header.flags | FLAG_FLAGGED
        } else {
            header.flags & !FLAG_FLAGGED
        };
        {
            let conn = self.conn();
            db::update_flags(&conn, folder.id, header.uid, new_flags)?;
        }
        self.client
            .set_flag(&folder.full_name, header.uid, "Flagged", value)
            .inspect_err(|_| self.enqueue_flag(&folder.full_name, header.uid, "Flagged", value))?;
        Ok(new_flags)
    }

    pub fn delete(&self, header: &MessageHeader) -> Res<()> {
        let folder = self.folder_of(header.folder_id)?;
        let trash = self
            .list_folders()?
            .into_iter()
            .find(|f| f.has_semantic("trash") && f.full_name != folder.full_name);
        match trash {
            Some(trash) => self.move_message(header, trash.id),
            None => {
                self.client
                    .set_flag(&folder.full_name, header.uid, "Deleted", true)?;
                self.client.expunge(&folder.full_name)?;
                let conn = self.conn();
                db::delete_message(&conn, folder.id, header.uid)?;
                Ok(())
            }
        }
    }

    pub fn move_message(&self, header: &MessageHeader, dest_id: i64) -> Res<()> {
        let folder = self.folder_of(header.folder_id)?;
        let dest = self.folder_of(dest_id)?;
        self.client
            .move_to(&folder.full_name, header.uid, &dest.full_name)?;
        let conn = self.conn();
        db::delete_message(&conn, folder.id, header.uid)?;
        Ok(())
    }

    // ---------- 写信 / 草稿 ----------

    pub fn send(&self, input: &ComposeInput) -> Res<()> {
        let message = smtp::build(&self.account, input)?;
        smtp::send(&self.account, &message)?;
        // 归档到服务器 Sent(服务器自动归档时失败,忽略)
        if let Ok(sent) = self
            .list_folders()?
            .into_iter()
            .find(|f| f.has_semantic("sent"))
            .ok_or_else(|| anyhow!("无 Sent 文件夹,跳过归档"))
        {
            let _ = self.client.append(&sent.full_name, &message.formatted());
        }
        Ok(())
    }

    pub fn save_draft(&self, input: &ComposeInput) -> Res<()> {
        let message = smtp::build(&self.account, input)?;
        let drafts = self
            .list_folders()?
            .into_iter()
            .find(|f| f.has_semantic("drafts"))
            .ok_or_else(|| anyhow!("服务器上没有草稿箱文件夹"))?;
        self.client.append(&drafts.full_name, &message.formatted())
    }

    fn enqueue_flag(&self, folder_name: &str, uid: u32, flag: &str, value: bool) {
        let conn = self.conn();
        let _ = db::enqueue_flag_op(
            &conn,
            &self.account.id,
            folder_name,
            uid,
            &format!("flag:{flag}:{}", value as i32),
        );
    }
}
