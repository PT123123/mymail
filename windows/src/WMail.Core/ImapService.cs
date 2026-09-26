using MailKit;
using MailKit.Net.Imap;
using MailKit.Net.Smtp;
using MailKit.Search;
using MailKit.Security;
using MimeKit;

namespace WMail.Core;

/// <summary>
/// 一个账户的 IMAP 会话封装。同步操作与 IDLE 各用一条独立连接,
/// 避免 IDLE 长挂导致操作通道阻塞(见 docs/sync.md §实时性)。
/// </summary>
public sealed class ImapService : IAsyncDisposable
{
    private readonly AccountConfig _account;
    private ImapClient? _client;
    private readonly SemaphoreSlim _lock = new(1, 1);

    public ImapService(AccountConfig account) => _account = account;

    public async Task ConnectAsync(CancellationToken ct = default)
    {
        if (_client is { IsConnected: true, IsAuthenticated: true }) return;
        _client?.Dispose();
        var client = new ImapClient { Timeout = 30_000 };
        await client.ConnectAsync(_account.ImapHost, _account.ImapPort, MailSecurityMap.ToMailKit(_account.ImapSecurity), ct);
        await client.AuthenticateAsync(_account.User, _account.Password, ct);
        _client = client;
    }

    public async Task DisconnectAsync()
    {
        var client = _client;
        _client = null;
        if (client is null) return;
        try { await client.DisconnectAsync(true, CancellationToken.None); } catch { }
        client.Dispose();
    }

    private async Task<ImapClient> ReadyAsync(CancellationToken ct)
    {
        await ConnectAsync(ct);
        return _client!;
    }

    public async Task<List<FolderInfo>> ListFoldersAsync(CancellationToken ct = default)
    {
        var client = await ReadyAsync(ct);
        await _lock.WaitAsync(ct);
        try
        {
            var result = new List<FolderInfo>();
            foreach (var ns in client.PersonalNamespaces)
            {
                var folders = await client.GetFoldersAsync(ns, StatusItems.None, true, ct);
                foreach (var f in folders)
                {
                    var flags = DescribeAttributes(f.Attributes);
                    var fullName = f.FullName;
                    var name = fullName;
                    var sep = f.DirectorySeparator;
                    if (sep != default)
                    {
                        var idx = fullName.LastIndexOf(sep);
                        if (idx >= 0) name = fullName[(idx + 1)..];
                    }
                    result.Add(new FolderInfo
                    {
                        AccountId = _account.Id,
                        FullName = fullName,
                        Name = string.IsNullOrEmpty(name) ? fullName : name,
                        Delimiter = sep == default ? null : sep.ToString(),
                        Flags = flags,
                    });
                }
            }
            return result;
        }
        finally { _lock.Release(); }
    }

    private static string DescribeAttributes(FolderAttributes attrs)
    {
        var parts = new List<string>();
        void Add(FolderAttributes a, string label) { if (attrs.HasFlag(a)) parts.Add(label); }
        Add(FolderAttributes.Sent, "\\Sent");
        Add(FolderAttributes.Drafts, "\\Drafts");
        Add(FolderAttributes.Trash, "\\Trash");
        Add(FolderAttributes.Junk, "\\Junk");
        Add(FolderAttributes.Archive, "\\Archive");
        Add(FolderAttributes.NoSelect, "\\NoSelect");
        Add(FolderAttributes.NoInferiors, "\\NoInferiors");
        return string.Join(" ", parts);
    }

    public sealed record FolderState(string FullName, uint UidValidity, int UidNext, int Exists, int Unread);

    public async Task<FolderState> SelectAsync(string fullName, CancellationToken ct = default)
    {
        var client = await ReadyAsync(ct);
        await _lock.WaitAsync(ct);
        try
        {
            var folder = (ImapFolder)await client.GetFolderAsync(fullName, ct);
            await folder.OpenAsync(FolderAccess.ReadOnly, ct);
            var uidNext = folder.UidNext?.Id ?? 1;
            return new FolderState(fullName, folder.UidValidity, (int)Math.Min(uidNext, int.MaxValue), folder.Count, folder.Unread);
        }
        finally { _lock.Release(); }
    }

    /// <summary>按“从新到旧”取一页摘要:offset=0 表示最新一页。</summary>
    public async Task<List<MessageHeader>> FetchPageAsync(Guid accountId, int folderId, string fullName, int offset, int count, CancellationToken ct = default)
    {
        var client = await ReadyAsync(ct);
        await _lock.WaitAsync(ct);
        try
        {
            var folder = (ImapFolder)await client.GetFolderAsync(fullName, ct);
            await folder.OpenAsync(FolderAccess.ReadOnly, ct);
            if (folder.Count == 0 || offset >= folder.Count) return new List<MessageHeader>();

            count = Math.Min(count, folder.Count - offset);
            var start = folder.Count - offset - count;

            var items = MessageSummaryItems.Envelope | MessageSummaryItems.Flags
                      | MessageSummaryItems.UniqueId | MessageSummaryItems.Size
                      | MessageSummaryItems.BodyStructure;
            var summaries = await folder.FetchAsync(start, count, items, ct);

            var list = new List<MessageHeader>(summaries.Count);
            foreach (var s in summaries)
            {
                list.Add(ToHeader(accountId, folderId, s));
            }
            return list;
        }
        finally { _lock.Release(); }
    }

    private static MessageHeader ToHeader(Guid accountId, int folderId, IMessageSummary s)
    {
        var from = FirstMailbox(s.Envelope?.From);
        var to = string.Join("; ", s.Envelope?.To?.OfType<MailboxAddress>().Select(a => a.ToString()) ?? Array.Empty<string>());
        var date = s.Envelope?.Date ?? DateTimeOffset.Now;

        var flags = MessageFlagBits.None;
        var f = s.Flags ?? 0;
        if (f.HasFlag(MessageFlags.Seen)) flags |= MessageFlagBits.Seen;
        if (f.HasFlag(MessageFlags.Flagged)) flags |= MessageFlagBits.Flagged;
        if (f.HasFlag(MessageFlags.Answered)) flags |= MessageFlagBits.Answered;
        if (f.HasFlag(MessageFlags.Draft)) flags |= MessageFlagBits.Draft;
        if (f.HasFlag(MessageFlags.Deleted)) flags |= MessageFlagBits.Deleted;

        return new MessageHeader
        {
            AccountId = accountId,
            FolderId = folderId,
            Uid = s.UniqueId.Id,
            MessageId = s.Envelope?.MessageId,
            Subject = s.Envelope?.Subject ?? "",
            From = from,
            To = to,
            DateUnix = date.ToUnixTimeSeconds(),
            Flags = flags,
            SizeBytes = s.Size ?? 0,
            HasAttachments = s.Attachments?.Any() ?? false,
        };
    }

    private static string FirstMailbox(IEnumerable<InternetAddress>? addresses)
    {
        if (addresses is null) return "";
        foreach (var a in addresses.OfType<MailboxAddress>())
        {
            return string.IsNullOrWhiteSpace(a.Name) ? a.Address : $"{a.Name} <{a.Address}>";
        }
        return "";
    }

    public async Task<MailBodyResult> FetchBodyAsync(Guid accountId, int folderId, string fullName, uint uid, CancellationToken ct = default)
    {
        var client = await ReadyAsync(ct);
        await _lock.WaitAsync(ct);
        try
        {
            var folder = (ImapFolder)await client.GetFolderAsync(fullName, ct);
            await folder.OpenAsync(FolderAccess.ReadOnly, ct);
            var mime = await folder.GetMessageAsync(new UniqueId(uid), ct);

            var result = new MailBodyResult
            {
                HtmlFilePath = null,
                PlainText = mime.TextBody,
            };
            foreach (var entity in mime.Attachments)
            {
                if (entity is MimePart part)
                {
                    result.Attachments.Add(new AttachmentInfo
                    {
                        FileName = string.IsNullOrWhiteSpace(part.FileName) ? "attachment" : part.FileName!,
                        ContentType = part.ContentType?.MimeType ?? "application/octet-stream",
                        SizeBytes = part.Content?.Stream?.Length ?? 0,
                    });
                }
            }

            if (!string.IsNullOrWhiteSpace(mime.HtmlBody))
            {
                var clean = HtmlCleaner.Clean(mime.HtmlBody);
                var path = Path.Combine(Paths.BodiesDir, $"{folderId}_{uid}.html");
                await File.WriteAllTextAsync(path, clean, ct);
                result.HtmlFilePath = path;
            }
            return result;
        }
        finally { _lock.Release(); }
    }

    public async Task SaveAttachmentAsync(string fullName, uint uid, string fileName, string destPath, CancellationToken ct = default)
    {
        var client = await ReadyAsync(ct);
        await _lock.WaitAsync(ct);
        try
        {
            var folder = (ImapFolder)await client.GetFolderAsync(fullName, ct);
            await folder.OpenAsync(FolderAccess.ReadOnly, ct);
            var mime = await folder.GetMessageAsync(new UniqueId(uid), ct);
            foreach (var entity in mime.Attachments)
            {
                if (entity is MimePart part &&
                    string.Equals(part.FileName, fileName, StringComparison.OrdinalIgnoreCase))
                {
                    await using var fs = File.Create(destPath);
                    await part.Content.DecodeToAsync(fs, ct);
                    return;
                }
            }
            throw new FileNotFoundException($"附件 {fileName} 不在这封邮件里。");
        }
        finally { _lock.Release(); }
    }

    public async Task SetFlagAsync(string fullName, uint uid, MessageFlags flag, bool value, CancellationToken ct = default)
    {
        var client = await ReadyAsync(ct);
        await _lock.WaitAsync(ct);
        try
        {
            var folder = (ImapFolder)await client.GetFolderAsync(fullName, ct);
            await folder.OpenAsync(FolderAccess.ReadWrite, ct);
            var id = new UniqueId(uid);
            if (value) await folder.AddFlagsAsync(id, flag, silent: true, ct);
            else await folder.RemoveFlagsAsync(id, flag, silent: true, ct);
        }
        finally { _lock.Release(); }
    }

    public async Task MoveAsync(string fullName, uint uid, string destFullName, CancellationToken ct = default)
    {
        var client = await ReadyAsync(ct);
        await _lock.WaitAsync(ct);
        try
        {
            var folder = (ImapFolder)await client.GetFolderAsync(fullName, ct);
            await folder.OpenAsync(FolderAccess.ReadWrite, ct);
            var dest = (ImapFolder)await client.GetFolderAsync(destFullName, ct);
            await dest.OpenAsync(FolderAccess.ReadWrite, ct);
            await folder.MoveToAsync(new UniqueId(uid), dest, ct);
        }
        finally { _lock.Release(); }
    }

    public async Task ExpungeAsync(string fullName, CancellationToken ct = default)
    {
        var client = await ReadyAsync(ct);
        await _lock.WaitAsync(ct);
        try
        {
            var folder = (ImapFolder)await client.GetFolderAsync(fullName, ct);
            await folder.OpenAsync(FolderAccess.ReadWrite, ct);
            await folder.ExpungeAsync(ct);
        }
        finally { _lock.Release(); }
    }

    public async Task AppendAsync(string fullName, MimeMessage message, MessageFlags flags, CancellationToken ct = default)
    {
        var client = await ReadyAsync(ct);
        await _lock.WaitAsync(ct);
        try
        {
            var folder = (ImapFolder)await client.GetFolderAsync(fullName, ct);
            await folder.OpenAsync(FolderAccess.ReadWrite, ct);
            await folder.AppendAsync(FormatOptions.Default, new AppendRequest(message, flags), ct);
        }
        finally { _lock.Release(); }
    }

    public async Task<List<uint>> SearchAsync(string fullName, string query, CancellationToken ct = default)
    {
        var client = await ReadyAsync(ct);
        await _lock.WaitAsync(ct);
        try
        {
            var folder = (ImapFolder)await client.GetFolderAsync(fullName, ct);
            await folder.OpenAsync(FolderAccess.ReadOnly, ct);
            var q = SearchQuery.SubjectContains(query)
                .Or(SearchQuery.FromContains(query))
                .Or(SearchQuery.BodyContains(query));
            var uids = await folder.SearchAsync(q, ct);
            return uids.Select(u => u.Id).ToList();
        }
        finally { _lock.Release(); }
    }

    /// <summary>
    /// IDLE 长循环:自带独立连接与断线重连(指数退避),9 分钟主动退出重进防超时。
    /// 新邮件/计数变化通过 IMailFolder.CountChanged 回调 onMail(在后台线程)。
    /// </summary>
    public async Task RunIdleLoopAsync(string fullName, Action? onMail, Action<string>? onError, CancellationToken ct)
    {
        var backoff = TimeSpan.FromSeconds(2);
        while (!ct.IsCancellationRequested)
        {
            ImapClient? idleClient = null;
            try
            {
                idleClient = new ImapClient { Timeout = 600_000 };
                await idleClient.ConnectAsync(_account.ImapHost, _account.ImapPort, MailSecurityMap.ToMailKit(_account.ImapSecurity), ct);
                await idleClient.AuthenticateAsync(_account.User, _account.Password, ct);
                var folder = await idleClient.GetFolderAsync(fullName, ct);
                await folder.OpenAsync(FolderAccess.ReadWrite, ct);
                backoff = TimeSpan.FromSeconds(2);

                folder.CountChanged += (_, _) =>
                {
                    try { onMail?.Invoke(); } catch { }
                };

                while (!ct.IsCancellationRequested)
                {
                    // MailKit 4.8:Idle(doneToken, ct) —— doneToken 必须可取消
                    using var doneCts = new CancellationTokenSource(TimeSpan.FromMinutes(9));
                    try
                    {
                        idleClient.Idle(doneCts.Token, ct);
                    }
                    catch (OperationCanceledException) when (!ct.IsCancellationRequested)
                    {
                        // 9 分钟到点,重新进入 IDLE
                    }
                }
            }
            catch (NotSupportedException)
            {
                onError?.Invoke("服务器不支持 IDLE,改为等待手动刷新");
                break;
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                onError?.Invoke("实时同步中断,将自动重连:" + ex.Message);
                try { await Task.Delay(backoff, ct); } catch (OperationCanceledException) { break; }
                backoff = TimeSpan.FromSeconds(Math.Min(backoff.TotalSeconds * 2, 120));
            }
            finally
            {
                try { idleClient?.Disconnect(true); } catch { }
                idleClient?.Dispose();
            }
        }
    }

    public void Dispose()
    {
        try { _client?.Dispose(); } catch { }
        _client = null;
        _lock.Dispose();
    }

    public async ValueTask DisposeAsync()
    {
        await DisconnectAsync();
        _lock.Dispose();
    }
}

/// <summary>SMTP 发送 + MIME 构造。</summary>
public static class SmtpService
{
    public static async Task SendAsync(AccountConfig account, MimeMessage message, CancellationToken ct = default)
    {
        using var client = new SmtpClient { Timeout = 60_000 };
        await client.ConnectAsync(account.SmtpHost, account.SmtpPort, MailSecurityMap.ToMailKit(account.SmtpSecurity), ct);
        await client.AuthenticateAsync(account.User, account.Password, ct);
        await client.SendAsync(message, ct);
        await client.DisconnectAsync(true, ct);
    }

    public static MimeMessage BuildMessage(AccountConfig account, ComposeInput input)
    {
        var msg = new MimeMessage();
        msg.From.Add(new MailboxAddress(
            string.IsNullOrWhiteSpace(account.DisplayName) ? account.Email : account.DisplayName,
            account.Email));
        foreach (var a in ParseList(input.To)) msg.To.Add(a);
        foreach (var a in ParseList(input.Cc)) msg.Cc.Add(a);
        foreach (var a in ParseList(input.Bcc)) msg.Bcc.Add(a);
        msg.Subject = input.Subject;
        msg.Date = DateTimeOffset.Now;

        if (!string.IsNullOrEmpty(input.InReplyToMessageId))
        {
            msg.InReplyTo = input.InReplyToMessageId;
            msg.References.Add(input.InReplyToMessageId);
        }

        var builder = new BodyBuilder { TextBody = input.Body };
        foreach (var path in input.AttachmentPaths)
        {
            builder.Attachments.Add(path);
        }
        msg.Body = builder.ToMessageBody();
        return msg;
    }

    public static string QuoteForReply(string originalFrom, string originalDateUnix, string body)
    {
        var date = DateTimeOffset.FromUnixTimeSeconds(long.TryParse(originalDateUnix, out var d) ? d : 0);
        var lines = (body ?? "").Split('\n');
        var quoted = string.Join("\n", lines.Select(l => "> " + l));
        return $"\n\n{date.LocalDateTime:yyyy-MM-dd HH:mm},{originalFrom} 写道:\n{quoted}\n";
    }

    private static IEnumerable<MailboxAddress> ParseList(string text)
        => (text ?? "").Split(new[] { ';', ',', '\n', '\r' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(a => MailboxAddress.Parse(a));
}
