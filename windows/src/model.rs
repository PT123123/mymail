use serde::{Deserialize, Serialize};
use std::path::PathBuf;

pub type Res<T> = anyhow::Result<T>;

#[derive(Clone, Serialize, Deserialize)]
pub struct AccountConfig {
    pub id: String,
    pub display_name: String,
    pub email: String,
    pub imap_host: String,
    pub imap_port: u16,
    pub smtp_host: String,
    pub smtp_port: u16,
    /// DPAPI 加密后 base64 存盘,绝不落明文
    pub protected_password: String,
}

impl AccountConfig {
    pub fn password(&self) -> Res<String> {
        crate::dpapi::unprotect(&self.protected_password)
    }
}

pub const FLAG_SEEN: i64 = 1;
pub const FLAG_FLAGGED: i64 = 2;
pub const FLAG_ANSWERED: i64 = 4;
pub const FLAG_DRAFT: i64 = 8;
pub const FLAG_DELETED: i64 = 16;

#[derive(Clone, Default, Debug)]
pub struct FolderInfo {
    pub id: i64,
    pub account_id: String,
    pub full_name: String,
    pub name: String,
    /// sent,drafts,trash,junk(名称/属性启发式,见 docs/sync.md)
    pub semantics: String,
    pub uid_validity: u32,
    pub uid_next: u32,
    pub unread: i64,
    pub total: i64,
}

impl FolderInfo {
    pub fn has_semantic(&self, s: &str) -> bool {
        self.semantics.split(',').any(|x| x == s)
    }
}

#[derive(Clone, Debug)]
pub struct MessageHeader {
    pub id: i64,
    pub account_id: String,
    pub folder_id: i64,
    pub uid: u32,
    pub message_id: Option<String>,
    pub subject: String,
    pub from: String,
    pub to: String,
    pub date_unix: i64,
    pub flags: i64,
    pub size: i64,
    pub has_attach: bool,
    pub snippet: String,
    pub body_path: Option<String>,
    pub fetched_body: bool,
}

impl MessageHeader {
    pub fn is_seen(&self) -> bool {
        self.flags & FLAG_SEEN != 0
    }
    pub fn is_flagged(&self) -> bool {
        self.flags & FLAG_FLAGGED != 0
    }
}

#[derive(Clone, Debug)]
pub struct MailAttachment {
    pub file_name: String,
    pub content_type: String,
    pub size: i64,
}

#[derive(Clone, Debug)]
pub struct MailBody {
    pub html: Option<String>,
    pub text: Option<String>,
    pub attachments: Vec<MailAttachment>,
}

#[derive(Clone, Default, Debug)]
pub struct ComposeInput {
    pub to: Vec<String>,
    pub cc: Vec<String>,
    pub bcc: Vec<String>,
    pub subject: String,
    pub body: String,
    pub attachments: Vec<String>,
    pub in_reply_to: Option<String>,
}

pub fn app_data_dir() -> PathBuf {
    let base = std::env::var("LOCALAPPDATA").unwrap_or_else(|_| ".".into());
    PathBuf::from(base).join("w-mail")
}

pub fn bodies_dir() -> PathBuf {
    let d = app_data_dir().join("bodies");
    let _ = std::fs::create_dir_all(&d);
    d
}

/// 净化邮件 HTML(不可信输入)
pub fn sanitize_html(html: &str) -> String {
    ammonia::clean(html)
}

/// HTML → 纯文本(去标签留文字),用于无纯文本部件的邮件
pub fn html_to_text(html: &str) -> String {
    ammonia::Builder::default()
        .tags(std::collections::HashSet::new())
        .clean(html)
        .to_string()
}

pub fn quote_for_reply(from: &str, date_unix: i64, body: &str) -> String {
    let date = chrono::DateTime::from_timestamp(date_unix, 0)
        .map(|d| d.with_timezone(&chrono::Local).format("%Y-%m-%d %H:%M").to_string())
        .unwrap_or_default();
    let quoted: String = body
        .lines()
        .map(|l| format!("> {l}\n"))
        .collect();
    format!("\n\n于 {date},{from} 写道:\n{quoted}\n")
}
