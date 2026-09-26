//! IMAP 封装(imap 2.4 + rustls)。v0 仅 SSL(所有主流服务商 IMAP 均为 993/SSL);
//! 头部/正文解析统一走 mail-parser(自带 RFC2047 解码)。
//! 每类操作独立连接、用完即断;IDLE 用独立长连接。

use crate::model::*;
use anyhow::{anyhow, Context};
use mail_parser::{MessageParser, MimeHeaders};
use rustls_connector::RustlsConnector;
use std::net::TcpStream;
use std::time::Duration;

pub type ImapStream = rustls_connector::rustls::StreamOwned<
    rustls_connector::rustls::ClientConnection,
    TcpStream,
>;
pub type ImapSession = imap::Session<ImapStream>;

use imap::extensions::idle::SetReadTimeout;
use imap::types::{Flag, NameAttribute};
use std::io::{Read, Write};
use std::sync::atomic::{AtomicBool, Ordering};

/// IDLE 用的流包装:把 SetReadTimeout 委托给底层 TcpStream。
pub struct IdleStream(pub ImapStream);

impl Read for IdleStream {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        self.0.read(buf)
    }
}

impl Write for IdleStream {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.0.write(buf)
    }
    fn flush(&mut self) -> std::io::Result<()> {
        self.0.flush()
    }
}

impl SetReadTimeout for IdleStream {
    fn set_read_timeout(&mut self, dur: Option<Duration>) -> imap::Result<()> {
        self.0.sock.set_read_timeout(dur).map_err(imap::Error::Io)
    }
}

pub struct MailClient {
    account: AccountConfig,
    tls: RustlsConnector,
}

fn addr_str(a: &mail_parser::Addr<'_>) -> String {
    let Some(addr) = a.address.as_ref() else {
        return String::new();
    };
    match &a.name {
        Some(n) if !n.is_empty() => format!("{n} <{addr}>"),
        _ => addr.to_string(),
    }
}

fn addr_list(a: &mail_parser::Address<'_>) -> String {
    match a {
        mail_parser::Address::List(list) => list
            .iter()
            .map(addr_str)
            .collect::<Vec<_>>()
            .join("; "),
        mail_parser::Address::Group(groups) => groups
            .iter()
            .flat_map(|g| g.addresses.iter())
            .map(addr_str)
            .collect::<Vec<_>>()
            .join("; "),
    }
}

impl MailClient {
    pub fn new(account: AccountConfig) -> Res<Self> {
        Ok(Self {
            account,
            tls: RustlsConnector::new_with_platform_verifier()?,
        })
    }

    fn connect(&self) -> Res<ImapSession> {
        let password = self.account.password()?;
        if self.account.imap_is_starttls() {
            anyhow::bail!("Windows 端暂不支持 IMAP STARTTLS,请使用 SSL(通常端口 993)");
        }
        let addr = format!("{}:{}", self.account.imap_host, self.account.imap_port);
        let tcp = TcpStream::connect(addr.as_str())
            .with_context(|| format!("连接 {} 失败", addr))?;
        let stream = self
            .tls
            .connect(&self.account.imap_host, tcp)
            .map_err(|e| anyhow!("TLS 握手失败:{e}"))?;
        let client = imap::Client::new(stream);
        let session = client
            .login(&self.account.email, &password)
            .map_err(|e| e.0)
            .context("IMAP 登录失败(检查邮箱/授权码)")?;
        Ok(session)
    }

    /// IDLE 专用连接:imap 的 Handle 要求流实现 SetReadTimeout,
    /// rustls 的 StreamOwned 没有实现,包一层委托给底层 TcpStream。
    fn connect_idle(&self) -> Res<imap::Session<IdleStream>> {
        let password = self.account.password()?;
        if self.account.imap_is_starttls() {
            anyhow::bail!("Windows 端暂不支持 IMAP STARTTLS,请使用 SSL(通常端口 993)");
        }
        let addr = format!("{}:{}", self.account.imap_host, self.account.imap_port);
        let tcp = TcpStream::connect(addr.as_str())
            .with_context(|| format!("连接 {} 失败", addr))?;
        let stream = self
            .tls
            .connect(&self.account.imap_host, tcp)
            .map_err(|e| anyhow!("TLS 握手失败:{e}"))?;
        let client = imap::Client::new(IdleStream(stream));
        let session = client
            .login(&self.account.email, &password)
            .map_err(|e| e.0)
            .context("IMAP 登录失败(检查邮箱/授权码)")?;
        Ok(session)
    }

    /// (full_name, semantics)
    pub fn list_folders(&self) -> Res<Vec<(String, String)>> {
        let mut s = self.connect()?;
        let names = s.list(None, Some("*")).context("列出文件夹失败")?;
        let mut out = Vec::new();
        for n in names.iter() {
            let full = n.name().to_string();
            if full.is_empty() {
                continue;
            }
            let mut sem = String::new();
            let mut noselect = false;
            for a in n.attributes() {
                match a {
                    NameAttribute::NoSelect => noselect = true,
                    NameAttribute::Custom(attr) => {
                        let l = attr.to_lowercase();
                        if l.contains("sent") {
                            sem = push_sem(&sem, "sent");
                        }
                        if l.contains("draft") {
                            sem = push_sem(&sem, "drafts");
                        }
                        if l.contains("trash") || l.contains("deleted") {
                            sem = push_sem(&sem, "trash");
                        }
                        if l.contains("junk") || l.contains("spam") {
                            sem = push_sem(&sem, "junk");
                        }
                    }
                    _ => {}
                }
            }
            if sem.is_empty() {
                sem = infer_semantic(&full);
            }
            if noselect {
                sem = push_sem(&sem, "noselect");
            }
            out.push((full, sem));
        }
        let _ = s.logout();
        Ok(out)
    }

    /// (uid_validity, uid_next, exists, unread)
    pub fn select(&self, full_name: &str) -> Res<(u32, u32, u32, i64)> {
        let mut s = self.connect()?;
        let mb = s
            .select(full_name)
            .with_context(|| format!("打开文件夹 {full_name} 失败"))?;
        let _ = s.logout();
        Ok((
            mb.uid_validity.unwrap_or(1),
            mb.uid_next.unwrap_or(1),
            mb.exists,
            mb.unseen.unwrap_or(0) as i64,
        ))
    }

    /// 从新到旧取一页摘要(offset=0 为最新)。exists 由调用方 select 得到。
    #[allow(clippy::too_many_arguments)]
    pub fn fetch_page(
        &self,
        account_id: &str,
        folder_id: i64,
        full_name: &str,
        offset: usize,
        count: usize,
        exists: u32,
    ) -> Res<Vec<MessageHeader>> {
        let mut s = self.connect()?;
        s.select(full_name)
            .with_context(|| format!("打开文件夹 {full_name} 失败"))?;
        let out = fetch_page_on(&mut s, account_id, folder_id, offset, count, exists)?;
        let _ = s.logout();
        Ok(out)
    }

    pub fn fetch_body(&self, full_name: &str, uid: u32) -> Res<MailBody> {
        let mut s = self.connect()?;
        s.select(full_name)
            .with_context(|| format!("打开文件夹 {full_name} 失败"))?;
        let fetches = s
            .uid_fetch(uid.to_string(), "(BODY.PEEK[])")
            .context("拉取邮件正文失败")?;
        let raw = fetches
            .iter()
            .next()
            .and_then(|f| f.body())
            .ok_or_else(|| anyhow!("UID {uid} 不存在"))?
            .to_vec();
        let _ = s.logout();
        parse_body(&raw)
    }

    pub fn save_attachment(
        &self,
        full_name: &str,
        uid: u32,
        file_name: &str,
        dest: &std::path::Path,
    ) -> Res<()> {
        let mut s = self.connect()?;
        s.select(full_name)
            .with_context(|| format!("打开文件夹 {full_name} 失败"))?;
        let fetches = s.uid_fetch(uid.to_string(), "(BODY.PEEK[])")?;
        let raw = fetches
            .iter()
            .next()
            .and_then(|f| f.body())
            .ok_or_else(|| anyhow!("UID {uid} 不存在"))?
            .to_vec();
        let _ = s.logout();
        let parsed = MessageParser::default()
            .parse(&raw)
            .ok_or_else(|| anyhow!("解析邮件失败"))?;
        for part in parsed.attachments() {
            if part.attachment_name() == Some(file_name) {
                std::fs::write(dest, part.contents())?;
                return Ok(());
            }
        }
        Err(anyhow!("附件不存在:{file_name}"))
    }

    pub fn set_flag(&self, full_name: &str, uid: u32, flag: &str, value: bool) -> Res<()> {
        let mut s = self.connect()?;
        s.select(full_name)?;
        let op = if value { "+FLAGS" } else { "-FLAGS" };
        s.uid_store(uid.to_string(), format!("{op} (\\{flag})"))?;
        let _ = s.logout();
        Ok(())
    }

    pub fn move_to(&self, full_name: &str, uid: u32, dest: &str) -> Res<()> {
        let mut s = self.connect()?;
        s.select(full_name)?;
        s.uid_copy(uid.to_string(), dest)
            .with_context(|| format!("复制到 {dest} 失败"))?;
        s.uid_store(uid.to_string(), "+FLAGS (\\Deleted)")?;
        s.expunge()?;
        let _ = s.logout();
        Ok(())
    }

    pub fn append(&self, full_name: &str, bytes: &[u8]) -> Res<()> {
        let mut s = self.connect()?;
        s.append(full_name, bytes)?;
        let _ = s.logout();
        Ok(())
    }

    pub fn expunge(&self, full_name: &str) -> Res<()> {
        let mut s = self.connect()?;
        s.select(full_name)?;
        s.expunge()?;
        let _ = s.logout();
        Ok(())
    }

    /// 长驻 IDLE 会话:每轮最多等 9 分钟;EXISTS 变化时回调 on_wake(exists)。
    /// 回调在本线程执行,应使用独立连接做操作;出错返回 Err 由外层重连。
    /// stop 置位后会在下一轮(≤9 分钟)正常退出并返回 Ok(())。
    pub fn idle_session_loop<F: FnMut(u32) -> Res<()>>(
        &self,
        full_name: &str,
        stop: &AtomicBool,
        mut on_wake: F,
    ) -> Res<()> {
        let mut s = self.connect_idle()?;
        let mb = s.select(full_name)?;
        let mut last = mb.exists;
        loop {
            if stop.load(Ordering::SeqCst) {
                let _ = s.logout();
                return Ok(());
            }
            let handle = s.idle()?;
            // 最多等 9 分钟;超时或收到服务器通知都会返回,Handle 随之 drop 并完成 DONE 握手
            handle.wait_with_timeout(Duration::from_secs(9 * 60))?;
            // 重新 SELECT 读取最新 EXISTS
            let mb2 = s.select(full_name)?;
            if mb2.exists != last {
                last = mb2.exists;
                on_wake(last)?;
            }
        }
    }
}

fn fetch_page_on(
    s: &mut ImapSession,
    account_id: &str,
    folder_id: i64,
    offset: usize,
    count: usize,
    exists: u32,
) -> Res<Vec<MessageHeader>> {
    if exists == 0 || offset as u32 >= exists {
        return Ok(Vec::new());
    }
    let c = (count as u32).min(exists - offset as u32);
    let from = exists - offset as u32 - c + 1;
    let set = format!("{from}:{exists}");
    let fetches = s.fetch(&set, "(UID FLAGS RFC822.SIZE BODY.PEEK[HEADER])")?;
    let mut out = Vec::new();
    for f in fetches.iter() {
        let uid = match f.uid {
            Some(u) => u,
            None => continue,
        };
        let raw = f.body().unwrap_or(&[]);
        let parsed = match MessageParser::default().parse(raw) {
            Some(p) => p,
            None => continue,
        };
        let mut flags: i64 = 0;
        for fl in f.flags() {
            match fl {
                Flag::Seen => flags |= FLAG_SEEN,
                Flag::Flagged => flags |= FLAG_FLAGGED,
                Flag::Answered => flags |= FLAG_ANSWERED,
                Flag::Draft => flags |= FLAG_DRAFT,
                Flag::Deleted => flags |= FLAG_DELETED,
                _ => {}
            }
        }
        out.push(MessageHeader {
            id: 0,
            account_id: account_id.to_string(),
            folder_id,
            uid,
            message_id: parsed.message_id().map(str::to_string),
            subject: parsed.subject().unwrap_or("").to_string(),
            from: parsed.from().and_then(|a| a.first()).map(addr_str).unwrap_or_default(),
            to: parsed.to().map(addr_list).unwrap_or_default(),
            date_unix: parsed
                .date()
                .map(|d| d.to_timestamp())
                .unwrap_or_else(|| chrono::Utc::now().timestamp()),
            flags,
            size: f.size.unwrap_or(0) as i64,
            has_attach: false,
            snippet: String::new(),
            body_path: None,
            fetched_body: false,
        });
    }
    Ok(out)
}

fn parse_body(raw: &[u8]) -> Res<MailBody> {
    let parsed = MessageParser::default()
        .parse(raw)
        .ok_or_else(|| anyhow!("解析邮件失败"))?;
    let html = parsed.body_html(0).map(|c| c.into_owned());
    let text = parsed.body_text(0).map(|c| c.into_owned());
    let mut attachments = Vec::new();
    for part in parsed.attachments() {
        attachments.push(MailAttachment {
            file_name: part
                .attachment_name()
                .unwrap_or("attachment")
                .to_string(),
            content_type: part
                .content_type()
                .map(|ct| match &ct.c_subtype {
                    Some(sub) => format!("{}/{}", ct.c_type, sub),
                    None => ct.c_type.to_string(),
                })
                .unwrap_or_else(|| "application/octet-stream".into()),
            size: part.contents().len() as i64,
        });
    }
    Ok(MailBody {
        html,
        text,
        attachments,
    })
}

fn push_sem(cur: &str, s: &str) -> String {
    if cur.split(',').any(|x| x == s) {
        cur.to_string()
    } else if cur.is_empty() {
        s.to_string()
    } else {
        format!("{cur},{s}")
    }
}

fn infer_semantic(full_name: &str) -> String {
    let n = full_name.to_lowercase();
    let mut sem = String::new();
    if n.contains("sent") || n.contains("已发送") {
        sem = push_sem(&sem, "sent");
    }
    if n.contains("draft") || n.contains("草稿") {
        sem = push_sem(&sem, "drafts");
    }
    if n.contains("trash") || n.contains("deleted") || n.contains("已删除") {
        sem = push_sem(&sem, "trash");
    }
    if n.contains("junk") || n.contains("spam") || n.contains("垃圾") {
        sem = push_sem(&sem, "junk");
    }
    sem
}
