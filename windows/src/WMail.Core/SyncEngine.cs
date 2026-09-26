using System.Text.Json;
using MailKit;
using MimeKit;

namespace WMail.Core;

/// <summary>
/// 每账户一个引擎:负责“服务器 ↔ 本地缓存”的同步、IDLE 监听、以及所有写操作
/// (标记/移动/删除/发信/草稿)。UI 只与本类对话,不直接碰 MailKit(见 docs/architecture.md §4)。
/// </summary>
public sealed class SyncEngine : IDisposable
{
    private readonly AccountConfig _account;
    private readonly LocalCache _db;
    private readonly ImapService _imap;
    private readonly SemaphoreSlim _opLock = new(1, 1);
    private readonly CancellationTokenSource _lifetime = new();
    private Task? _idleTask;
    private readonly HashSet<int> _backfillFolders = new();

    public event Action<IReadOnlyList<MessageHeader>>? MessagesSynced;
    public event Action? FolderCountersChanged;
    public event Action<string>? StatusMessage;

    public SyncEngine(AccountConfig account, LocalCache db)
    {
        _account = account;
        _db = db;
        _imap = new ImapService(account);
    }

    // ---------- 文件夹 ----------

    public async Task<List<FolderInfo>> RefreshFoldersAsync(CancellationToken ct = default)
    {
        var server = await _imap.ListFoldersAsync(ct);
        foreach (var f in server)
        {
            f.Id = await _db.UpsertFolderAsync(f);
        }
        FolderCountersChanged?.Invoke();
        return await _db.ListFoldersAsync(_account.Id);
    }

    // ---------- 打开文件夹:校验 UIDVALIDITY → 同步最新页 → 后台回填 → (INBOX)IDLE ----------

    public async Task OpenFolderAsync(int folderId, CancellationToken ct = default)
    {
        var folder = await _db.GetFolderAsync(folderId) ?? throw new InvalidOperationException("文件夹不存在");
        var state = await _imap.SelectAsync(folder.FullName, ct);

        if (state.UidValidity != folder.UidValidity)
        {
            // UID 序列换代:旧缓存全部作废(见 docs/sync.md §1)
            await _db.DeleteMessagesByFolderAsync(folderId);
            folder.UidValidity = state.UidValidity;
        }
        await _db.UpdateFolderStateAsync(folderId, state.UidValidity, state.UidNext, state.Unread, state.Exists);

        await SyncNewestAsync(folderId, state.Exists, 200, ct);
        StartBackfill(folderId, folder.FullName, state.Exists);
        if (string.Equals(folder.FullName, "INBOX", StringComparison.OrdinalIgnoreCase))
        {
            StartIdle(folder.FullName);
        }
    }

    /// <summary>从缓存读一页列表(UI 用,永不碰网络)。</summary>
    public Task<List<MessageHeader>> GetCachedMessagesAsync(int folderId, int offset, int count)
        => _db.ListMessagesAsync(folderId, offset, count);

    private async Task SyncNewestAsync(int folderId, int serverTotal, int newestCount, CancellationToken ct)
    {
        await _opLock.WaitAsync(ct);
        try
        {
            var folder = await _db.GetFolderAsync(folderId);
            if (folder is null) return;

            var page = await _imap.FetchPageAsync(_account.Id, folderId, folder.FullName, 0, Math.Min(newestCount, serverTotal), ct);
            if (page.Count > 0)
            {
                await _db.UpsertMessagesAsync(page);
                MessagesSynced?.Invoke(page);
            }
            FolderCountersChanged?.Invoke();
        }
        finally { _opLock.Release(); }
    }

    private void StartBackfill(int folderId, string fullName, int serverTotal)
    {
        lock (_backfillFolders)
        {
            if (!_backfillFolders.Add(folderId)) return;
        }
        _ = Task.Run(async () =>
        {
            try
            {
                while (!_lifetime.IsCancellationRequested)
                {
                    var cached = await _db.CountMessagesAsync(folderId);
                    if (cached >= serverTotal) break;

                    StatusMessage?.Invoke($"后台回填 {fullName}:{cached}/{serverTotal}");
                    List<MessageHeader> page;
                    try
                    {
                        page = await _imap.FetchPageAsync(_account.Id, folderId, fullName, cached, 500, _lifetime.Token);
                    }
                    catch (Exception ex)
                    {
                        StatusMessage?.Invoke($"回填失败,稍后重试:{ex.Message}");
                        await Task.Delay(TimeSpan.FromSeconds(30), _lifetime.Token);
                        continue;
                    }
                    if (page.Count == 0) break;
                    await _db.UpsertMessagesAsync(page);
                }
            }
            catch (OperationCanceledException) { }
            catch (Exception ex)
            {
                StatusMessage?.Invoke($"回填终止:{ex.Message}");
            }
            finally
            {
                lock (_backfillFolders) { _backfillFolders.Remove(folderId); }
                StatusMessage?.Invoke($"回填完成:{fullName}");
            }
        }, CancellationToken.None);
    }

    private void StartIdle(string fullName)
    {
        if (_idleTask is not null) return;
        var token = _lifetime.Token;
        _idleTask = Task.Run(() => _imap.RunIdleLoopAsync(
            fullName,
            onMail: () =>
            {
                StatusMessage?.Invoke("收到新邮件");
                _ = Task.Run(async () =>
                {
                    try
                    {
                        var folder = await _db.ListFoldersAsync(_account.Id);
                        var inbox = folder.FirstOrDefault(f => f.FullName == fullName);
                        if (inbox is not null) await OpenFolderAsync(inbox.Id, token);
                    }
                    catch { }
                }, CancellationToken.None);
            },
            err => StatusMessage?.Invoke(err),
            token), CancellationToken.None);
    }

    // ---------- 读信 ----------

    public async Task<MailBodyResult> GetMessageBodyAsync(MessageHeader header, CancellationToken ct = default)
    {
        var folder = await _db.GetFolderAsync(header.FolderId) ?? throw new InvalidOperationException("文件夹不存在");

        MailBodyResult result;
        if (header.FetchedBody && !string.IsNullOrEmpty(header.BodyHtmlPath) && File.Exists(header.BodyHtmlPath) && !header.HasAttachments)
        {
            // 纯文本/无附件邮件才走缓存;带附件的重新拉一次以取到附件列表
            result = new MailBodyResult
            {
                HtmlFilePath = header.BodyHtmlPath,
                PlainText = header.Snippet,
                Attachments = new List<AttachmentInfo>(),
            };
        }
        else
        {
            result = await _imap.FetchBodyAsync(_account.Id, header.FolderId, folder.FullName, header.Uid, ct);
            var snippet = Truncate(result.PlainText ?? "(HTML 邮件)", 200);
            await _db.UpdateBodyAsync(header.FolderId, header.Uid, snippet, result.HtmlFilePath);
        }

        // 打开即标记已读(缓存 + 服务器,失败不阻塞阅读)
        if (!header.Seen)
        {
            header.Seen = true;
            await _db.UpdateMessageFlagsAsync(header.FolderId, header.Uid, header.Flags);
            try { await _imap.SetFlagAsync(folder.FullName, header.Uid, MessageFlags.Seen, true, ct); }
            catch (Exception ex) { await EnqueueFlagOpAsync(folder.FullName, header.Uid, "Seen", true); StatusMessage?.Invoke("已读标记已入离线队列:" + ex.Message); }
        }
        return result;
    }

    public Task DownloadAttachmentAsync(MessageHeader header, string fileName, string destPath, CancellationToken ct = default)
        => _imap.SaveAttachmentAsync(FolderNameOrThrow(header), header.Uid, fileName, destPath, ct);

    // ---------- 标志 / 移动 / 删除 ----------

    public async Task SetSeenAsync(MessageHeader header, bool seen, CancellationToken ct = default)
    {
        header.Seen = seen;
        await _db.UpdateMessageFlagsAsync(header.FolderId, header.Uid, header.Flags);
        try { await _imap.SetFlagAsync(FolderNameOrThrow(header), header.Uid, MessageFlags.Seen, seen, ct); }
        catch (Exception ex) { await EnqueueFlagOpAsync(FolderNameOrThrow(header), header.Uid, "Seen", seen); StatusMessage?.Invoke("已入离线队列:" + ex.Message); }
    }

    public async Task ToggleFlaggedAsync(MessageHeader header, CancellationToken ct = default)
    {
        var on = !header.Flagged;
        header.Flagged = on;
        await _db.UpdateMessageFlagsAsync(header.FolderId, header.Uid, header.Flags);
        try { await _imap.SetFlagAsync(FolderNameOrThrow(header), header.Uid, MessageFlags.Flagged, on, ct); }
        catch (Exception ex) { await EnqueueFlagOpAsync(FolderNameOrThrow(header), header.Uid, "Flagged", on); StatusMessage?.Invoke("已入离线队列:" + ex.Message); }
    }

    public async Task DeleteAsync(MessageHeader header, CancellationToken ct = default)
    {
        var fullName = FolderNameOrThrow(header);
        var folders = await _db.ListFoldersAsync(_account.Id);
        var trash = folders.FirstOrDefault(f => f.IsTrash && f.IsSelectable);
        if (trash is not null && trash.FullName != fullName)
        {
            await MoveAsync(header, trash.Id, ct);
            return;
        }
        // 没有垃圾桶语义的账户:标记删除 + 压缩
        header.Flags |= MessageFlagBits.Deleted;
        await _db.UpdateMessageFlagsAsync(header.FolderId, header.Uid, header.Flags);
        await _imap.SetFlagAsync(fullName, header.Uid, MessageFlags.Deleted, true, ct);
        await _imap.ExpungeAsync(fullName, ct);
        await _db.DeleteMessageAsync(header.FolderId, header.Uid);
    }

    public async Task MoveAsync(MessageHeader header, int destFolderId, CancellationToken ct = default)
    {
        var fromName = FolderNameOrThrow(header);
        var dest = await _db.GetFolderAsync(destFolderId) ?? throw new InvalidOperationException("目标文件夹不存在");
        await _imap.MoveAsync(fromName, header.Uid, dest.FullName, ct);
        await _db.DeleteMessageAsync(header.FolderId, header.Uid);
    }

    // ---------- 写信 / 草稿 ----------

    public async Task SendAsync(ComposeInput input, CancellationToken ct = default)
    {
        var msg = SmtpService.BuildMessage(_account, input);
        await SmtpService.SendAsync(_account, msg, ct);
        StatusMessage?.Invoke("已发送");

        // 归档到服务器 Sent(服务器自动归档的会失败,忽略即可)
        try
        {
            var folders = await _db.ListFoldersAsync(_account.Id);
            var sent = folders.FirstOrDefault(f => f.IsSent && f.IsSelectable);
            if (sent is not null) await _imap.AppendAsync(sent.FullName, msg, MessageFlags.Seen, ct);
        }
        catch { }
    }

    public async Task SaveDraftAsync(ComposeInput input, CancellationToken ct = default)
    {
        var msg = SmtpService.BuildMessage(_account, input);
        var folders = await _db.ListFoldersAsync(_account.Id);
        var drafts = folders.FirstOrDefault(f => f.IsDrafts && f.IsSelectable)
            ?? throw new InvalidOperationException("服务器上没有 \\Drafts 文件夹");
        await _imap.AppendAsync(drafts.FullName, msg, MessageFlags.Draft, ct);
        StatusMessage?.Invoke("草稿已保存到服务器");
    }

    // ---------- 杂项 ----------

    public Task<List<MessageHeader>> SearchLocalAsync(string query) => _db.SearchAsync(query);

    public async Task<List<uint>> SearchServerAsync(string fullName, string query, CancellationToken ct = default)
        => await _imap.SearchAsync(fullName, query, ct);

    private async Task EnqueueFlagOpAsync(string folderName, uint uid, string flag, bool value)
    {
        var payload = JsonSerializer.Serialize(new { flag, value });
        await _db.EnqueueOpAsync(_account.Id, folderName, uid, "set_flags", payload);
    }

    private string FolderNameOrThrow(MessageHeader header)
        => _db.GetFolderAsync(header.FolderId).GetAwaiter().GetResult()?.FullName
           ?? throw new InvalidOperationException("文件夹不存在");

    private static string Truncate(string s, int max)
    {
        s = s.Replace("\r", " ").Replace("\n", " ").Trim();
        return s.Length <= max ? s : s[..max] + "…";
    }

    public void Dispose()
    {
        _lifetime.Cancel();
        _imap.Dispose();
        _opLock.Dispose();
        _lifetime.Dispose();
    }
}
