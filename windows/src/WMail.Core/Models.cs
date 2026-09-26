using System.Text.Json.Serialization;

namespace WMail.Core;

public enum MailSecurity
{
    SslOnConnect,
    StartTls,
    None,
}

/// <summary>账户配置。密码经 DPAPI 加密后存储,绝不落明文。</summary>
public sealed class AccountConfig
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string DisplayName { get; set; } = "";
    public string Email { get; set; } = "";

    public string ImapHost { get; set; } = "";
    public int ImapPort { get; set; } = 993;
    public MailSecurity ImapSecurity { get; set; } = MailSecurity.SslOnConnect;

    public string SmtpHost { get; set; } = "";
    public int SmtpPort { get; set; } = 465;
    public MailSecurity SmtpSecurity { get; set; } = MailSecurity.SslOnConnect;

    public string User { get; set; } = "";
    public string ProtectedPassword { get; set; } = "";

    [JsonIgnore]
    public string Password
    {
        get => string.IsNullOrEmpty(ProtectedPassword) ? "" : AccountStore.Unprotect(ProtectedPassword);
    }

    public AccountConfig WithPassword(string plain)
    {
        ProtectedPassword = AccountStore.Protect(plain);
        return this;
    }
}

public sealed class FolderInfo
{
    public int Id { get; set; }
    public Guid AccountId { get; set; }
    public string FullName { get; set; } = "";
    public string Name { get; set; } = "";
    public string? Delimiter { get; set; }
    /// <summary>空格分隔的 IMAP 属性,如 "\Sent \Drafts"。</summary>
    public string Flags { get; set; } = "";
    public uint UidValidity { get; set; }
    public int UidNext { get; set; } = 1;
    public int UnreadCount { get; set; }
    public int TotalCount { get; set; }
    public int BackfilledCount { get; set; }

    public bool IsSent => Flags.Contains("\\Sent", StringComparison.OrdinalIgnoreCase);
    public bool IsDrafts => Flags.Contains("\\Drafts", StringComparison.OrdinalIgnoreCase);
    public bool IsTrash => Flags.Contains("\\Trash", StringComparison.OrdinalIgnoreCase);
    public bool IsJunk => Flags.Contains("\\Junk", StringComparison.OrdinalIgnoreCase);
    public bool IsSelectable => !Flags.Contains("\\NoSelect", StringComparison.OrdinalIgnoreCase);
}

[Flags]
public enum MessageFlagBits
{
    None = 0,
    Seen = 1,
    Flagged = 2,
    Answered = 4,
    Draft = 8,
    Deleted = 16,
}

/// <summary>列表页使用的邮件头部(不含正文)。</summary>
public sealed class MessageHeader
{
    public int Id { get; set; }
    public Guid AccountId { get; set; }
    public int FolderId { get; set; }
    public uint Uid { get; set; }
    public string? MessageId { get; set; }
    public string Subject { get; set; } = "";
    public string From { get; set; } = "";
    public string To { get; set; } = "";
    public long DateUnix { get; set; }
    public MessageFlagBits Flags { get; set; }
    public long SizeBytes { get; set; }
    public bool HasAttachments { get; set; }
    public string Snippet { get; set; } = "";
    public bool FetchedBody { get; set; }
    public string? BodyHtmlPath { get; set; }

    public bool Seen { get => Flags.HasFlag(MessageFlagBits.Seen); set => Set(MessageFlagBits.Seen, value); }
    public bool Flagged { get => Flags.HasFlag(MessageFlagBits.Flagged); set => Set(MessageFlagBits.Flagged, value); }

    private void Set(MessageFlagBits bit, bool on)
        => Flags = on ? (Flags | bit) : (Flags & ~bit);
}

public sealed class AttachmentInfo
{
    public string FileName { get; set; } = "attachment";
    public string ContentType { get; set; } = "application/octet-stream";
    public long SizeBytes { get; set; }
}

public sealed class MailBodyResult
{
    /// <summary>净化后 HTML 的缓存文件路径;null 表示无 HTML(用纯文本)。</summary>
    public string? HtmlFilePath { get; set; }
    public string? PlainText { get; set; }
    public List<AttachmentInfo> Attachments { get; set; } = new();
}

/// <summary>写信/回信输入。</summary>
public sealed class ComposeInput
{
    public string To { get; set; } = "";
    public string Cc { get; set; } = "";
    public string Bcc { get; set; } = "";
    public string Subject { get; set; } = "";
    public string Body { get; set; } = "";
    public List<string> AttachmentPaths { get; } = new();
    /// <summary>回信时的 In-Reply-To 原始 Message-Id。</summary>
    public string? InReplyToMessageId { get; set; }
}

public static class Paths
{
    public static string AppDataDir { get; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "WMail");
    public static string BodiesDir { get; } = Path.Combine(AppDataDir, "bodies");

    public static void EnsureDirs()
    {
        Directory.CreateDirectory(AppDataDir);
        Directory.CreateDirectory(BodiesDir);
    }
}

public static class MailSecurityMap
{
    public static MailKit.Security.SecureSocketOptions ToMailKit(MailSecurity s) => s switch
    {
        MailSecurity.SslOnConnect => MailKit.Security.SecureSocketOptions.SslOnConnect,
        MailSecurity.StartTls => MailKit.Security.SecureSocketOptions.StartTls,
        _ => MailKit.Security.SecureSocketOptions.None,
    };
}

public static class HtmlCleaner
{
    public static string Clean(string? html)
    {
        if (string.IsNullOrWhiteSpace(html)) return "";
        // 邮件 HTML 是不可信输入:去掉脚本/事件/危险协议后才能渲染
        var sanitizer = new Ganss.Xss.HtmlSanitizer();
        sanitizer.AllowedAttributes.Add("style");
        return sanitizer.Sanitize(html);
    }
}
