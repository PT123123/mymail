//! SMTP(lettre)+ MIME 构建。SSL(465 系隐式 TLS)与 STARTTLS(587 系)均支持。

use crate::model::*;
use anyhow::{anyhow, Context};
use lettre::message::header::ContentType;
use lettre::message::{Attachment, Mailbox, MultiPart, SinglePart};
use lettre::transport::smtp::authentication::Credentials;
use lettre::{Message, SmtpTransport, Transport};

fn mailbox_of(display: &str, email: &str) -> Res<Mailbox> {
    let addr: lettre::Address = email
        .parse()
        .with_context(|| format!("邮箱地址不合法:{email}"))?;
    let name = if display.trim().is_empty() {
        None
    } else {
        Some(display.trim().to_string())
    };
    Ok(Mailbox::new(name, addr))
}

fn parse_mailboxes(list: &[String]) -> Res<Vec<Mailbox>> {
    list.iter()
        .filter(|s| s.contains('@'))
        .map(|s| {
            let s = s.trim();
            // 支持 "Name <a@b.c>" 与裸地址
            if let Some(start) = s.find('<') {
                if let Some(end) = s.find('>') {
                    if end > start {
                        let name = s[..start].trim().trim_matches('"');
                        let addr: lettre::Address = s[start + 1..end].trim().parse()?;
                        return Ok(Mailbox::new(if name.is_empty() { None } else { Some(name.to_string()) }, addr));
                    }
                }
            }
            mailbox_of("", s)
        })
        .collect()
}

pub fn build(account: &AccountConfig, input: &ComposeInput) -> Res<Message> {
    let mut builder = Message::builder()
        .from(mailbox_of(&account.display_name, &account.email)?)
        .subject(&input.subject);

    for mb in parse_mailboxes(&input.to)? {
        builder = builder.to(mb);
    }
    for mb in parse_mailboxes(&input.cc)? {
        builder = builder.cc(mb);
    }
    for mb in parse_mailboxes(&input.bcc)? {
        builder = builder.bcc(mb);
    }
    if let Some(id) = &input.in_reply_to {
        let id = id.trim().trim_start_matches('<').trim_end_matches('>');
        if !id.is_empty() {
            builder = builder.in_reply_to(format!("<{id}>"));
        }
    }

    let message = if input.attachments.is_empty() {
        builder.body(input.body.clone()).context("构建邮件失败")?
    } else {
        let mut mixed = MultiPart::mixed().singlepart(SinglePart::plain(input.body.clone()));
        for path in &input.attachments {
            let p = std::path::Path::new(path);
            let name = p
                .file_name()
                .map(|s| s.to_string_lossy().into_owned())
                .unwrap_or_else(|| "attachment".into());
            let bytes = std::fs::read(p).with_context(|| format!("读取附件失败:{path}"))?;
            let mime = mime_guess::from_path(p).first_or_octet_stream();
            mixed = mixed.singlepart(Attachment::new(name).body(bytes, ContentType::from(mime)));
        }
        builder.multipart(mixed).context("构建邮件失败")?
    };
    Ok(message)
}

pub fn send(account: &AccountConfig, message: &Message) -> Res<()> {
    let password = account.password()?;
    let builder = if account.smtp_is_starttls() {
        SmtpTransport::starttls_relay(&account.smtp_host)
    } else {
        SmtpTransport::relay(&account.smtp_host)
    }
    .with_context(|| format!("SMTP 配置失败:{}", account.smtp_host))?;
    let transport = builder
        .port(account.smtp_port)
        .credentials(Credentials::new(account.email.clone(), password))
        .build();
    transport
        .send(message)
        .map(|_| ())
        .map_err(|e| anyhow!("发送失败:{e}"))
}
