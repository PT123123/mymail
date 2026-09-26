using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using WMail.Core;

namespace WMail.App.ViewModels;

public partial class FolderVm : ObservableObject
{
    public FolderInfo Info { get; }

    public FolderVm(FolderInfo info) => Info = info;

    public string DisplayName => Info.FullName == "INBOX" ? "收件箱" : Info.Name;
    public string Badge => Info.UnreadCount > 0 ? Info.UnreadCount.ToString() : "";
    public Visibility BadgeVisibility => Info.UnreadCount > 0 ? Visibility.Visible : Visibility.Collapsed;

    public void RaiseCountersChanged()
    {
        OnPropertyChanged(nameof(Badge));
        OnPropertyChanged(nameof(BadgeVisibility));
    }
}

public partial class MessageVm : ObservableObject
{
    public MessageHeader Header { get; }

    public MessageVm(MessageHeader header) => Header = header;

    public string Subject => string.IsNullOrWhiteSpace(Header.Subject) ? "(无主题)" : Header.Subject;
    public string FromDisplay => string.IsNullOrWhiteSpace(Header.From) ? "(未知发件人)" : Header.From;
    public string DateText => DateTimeOffset.FromUnixTimeSeconds(Header.DateUnix).LocalDateTime.ToString("MM-dd HH:mm");
    public string LongDate => DateTimeOffset.FromUnixTimeSeconds(Header.DateUnix).LocalDateTime.ToString("yyyy-MM-dd HH:mm");
    public string Snippet => Header.Snippet;

    public Windows.UI.Text.FontWeight TitleWeight
        => Header.Seen ? Microsoft.UI.Text.FontWeights.Normal : Microsoft.UI.Text.FontWeights.SemiBold;
    public Visibility UnreadDotVisibility => Header.Seen ? Visibility.Collapsed : Visibility.Visible;
    public Visibility FlaggedVisibility => Header.Flagged ? Visibility.Visible : Visibility.Collapsed;
    public Visibility AttachVisibility => Header.HasAttachments ? Visibility.Visible : Visibility.Collapsed;
    public string FlagButtonText => Header.Flagged ? "取消星标" : "星标";
    public string ReadButtonText => Header.Seen ? "标为未读" : "标为已读";

    public string BodyHtmlFilePath { get; set; } = "";
    public string PlainTextFallback { get; set; } = "";
    public List<AttachmentInfo> Attachments { get; set; } = new();

    public void RaiseHeaderChanged()
    {
        OnPropertyChanged(nameof(TitleWeight));
        OnPropertyChanged(nameof(UnreadDotVisibility));
        OnPropertyChanged(nameof(FlaggedVisibility));
        OnPropertyChanged(nameof(AttachVisibility));
        OnPropertyChanged(nameof(FlagButtonText));
        OnPropertyChanged(nameof(ReadButtonText));
    }
}

public partial class MainViewModel : ObservableObject
{
    private readonly LocalCache _db = new();
    private readonly Dictionary<Guid, SyncEngine> _engines = new();
    private CancellationTokenSource? _reloadCts;

    public DispatcherQueue? Dispatcher { get; set; }

    public ObservableCollection<AccountConfig> Accounts { get; } = new();
    public ObservableCollection<FolderVm> Folders { get; } = new();
    public ObservableCollection<MessageVm> Messages { get; } = new();

    [ObservableProperty] private AccountConfig? _selectedAccount;
    [ObservableProperty] private bool _hasAccount;
    [ObservableProperty] private string _statusText = "就绪";
    [ObservableProperty] private FolderVm? _selectedFolder;
    [ObservableProperty] private MessageVm? _selectedMessage;
    [ObservableProperty] private bool _messageOpen;

    /// <summary>正文加载完毕,窗口据此驱动 WebView2。</summary>
    public event Action? ReaderChanged;

    public async Task InitializeAsync()
    {
        foreach (var a in AccountStore.Load()) Accounts.Add(a);
        HasAccount = Accounts.Count > 0;
        SelectedAccount = Accounts.FirstOrDefault();
    }

    public void AddAccount(AccountConfig account)
    {
        var list = AccountStore.Load();
        if (list.Any(a => string.Equals(a.Email, account.Email, StringComparison.OrdinalIgnoreCase)))
            throw new InvalidOperationException("该邮箱账户已存在");
        list.Add(account);
        AccountStore.Save(list);
        Accounts.Add(account);
        HasAccount = true;
        SelectedAccount = account;
    }

    public async Task<string> TestImapAsync(AccountConfig account)
    {
        var svc = new ImapService(account);
        try
        {
            await svc.ConnectAsync();
            var folders = await svc.ListFoldersAsync();
            return $"IMAP 连接成功,发现 {folders.Count} 个文件夹";
        }
        finally
        {
            svc.Dispose();
        }
    }

    private SyncEngine GetEngine(AccountConfig account)
    {
        if (!_engines.TryGetValue(account.Id, out var engine))
        {
            engine = new SyncEngine(account, _db);
            engine.StatusMessage += s => OnUi(() => StatusText = s);
            engine.MessagesSynced += _ => ScheduleReload();
            _engines[account.Id] = engine;
        }
        return engine;
    }

    private void OnUi(Action action)
    {
        if (Dispatcher is null) { action(); return; }
        if (Dispatcher.HasThreadAccess) action();
        else Dispatcher.TryEnqueue(() => action());
    }

    private void ScheduleReload()
    {
        _reloadCts?.Cancel();
        _reloadCts = new CancellationTokenSource();
        var token = _reloadCts.Token;
        _ = Task.Run(async () =>
        {
            try { await Task.Delay(1200, token); }
            catch (TaskCanceledException) { return; }
            OnUi(() => _ = ReloadMessagesAsync());
        }, CancellationToken.None);
    }

    partial void OnSelectedAccountChanged(AccountConfig? value)
    {
        if (value is not null) _ = LoadFoldersAsync();
    }

    partial void OnSelectedFolderChanged(FolderVm? value)
    {
        if (value is not null) _ = OpenFolderAsync(value);
    }

    partial void OnSelectedMessageChanged(MessageVm? value)
    {
        if (value is not null) _ = OpenMessageAsync(value);
    }

    public async Task LoadFoldersAsync()
    {
        if (SelectedAccount is null) return;
        try
        {
            OnUi(() => StatusText = "连接服务器…");
            var engine = GetEngine(SelectedAccount);
            var folders = await engine.RefreshFoldersAsync();

            var ordered = folders.Where(f => f.IsSelectable)
                .OrderByDescending(f => f.FullName == "INBOX")
                .ThenBy(f => f.FullName, StringComparer.OrdinalIgnoreCase);

            OnUi(() =>
            {
                Folders.Clear();
                foreach (var f in ordered) Folders.Add(new FolderVm(f));
                StatusText = "就绪";
            });
        }
        catch (Exception ex)
        {
            OnUi(() => StatusText = "连接失败:" + ex.Message);
        }
    }

    private async Task OpenFolderAsync(FolderVm folderVm)
    {
        if (SelectedAccount is null) return;
        try
        {
            OnUi(() =>
            {
                StatusText = "同步 " + folderVm.DisplayName + "…";
                SelectedMessage = null;
                MessageOpen = false;
            });
            var engine = GetEngine(SelectedAccount);
            await engine.OpenFolderAsync(folderVm.Info.Id);
            await ReloadMessagesAsync();
            await RefreshFolderBadgesAsync();
            OnUi(() => StatusText = "已同步 " + folderVm.DisplayName);
        }
        catch (Exception ex)
        {
            OnUi(() => StatusText = "同步失败:" + ex.Message);
        }
    }

    public async Task ReloadMessagesAsync()
    {
        var folderVm = SelectedFolder;
        if (folderVm is null || SelectedAccount is null) return;

        var headers = await GetEngine(SelectedAccount).GetCachedMessagesAsync(folderVm.Info.Id, 0, 200);
        OnUi(() =>
        {
            if (SelectedFolder is null || SelectedFolder.Info.Id != folderVm.Info.Id) return;
            var selectedUid = SelectedMessage?.Header.Uid;
            Messages.Clear();
            foreach (var h in headers) Messages.Add(new MessageVm(h));
            if (selectedUid is uint uid)
            {
                var again = Messages.FirstOrDefault(m => m.Header.Uid == uid);
                if (again is not null && !ReferenceEquals(again, SelectedMessage)) SelectedMessage = again;
            }
        });
    }

    public async Task RefreshFolderBadgesAsync()
    {
        if (SelectedAccount is null) return;
        var folders = await _db.ListFoldersAsync(SelectedAccount.Id);
        OnUi(() =>
        {
            foreach (var vm in Folders)
            {
                var f = folders.FirstOrDefault(x => x.Id == vm.Info.Id);
                if (f is null) continue;
                vm.Info.UnreadCount = f.UnreadCount;
                vm.Info.TotalCount = f.TotalCount;
                vm.RaiseCountersChanged();
            }
        });
    }

    private async Task OpenMessageAsync(MessageVm messageVm)
    {
        if (SelectedAccount is null) return;
        try
        {
            OnUi(() => StatusText = "加载正文…");
            var engine = GetEngine(SelectedAccount);
            var body = await engine.GetMessageBodyAsync(messageVm.Header);
            OnUi(() =>
            {
                messageVm.BodyHtmlFilePath = body.HtmlFilePath ?? "";
                messageVm.PlainTextFallback = body.PlainText ?? "";
                messageVm.Attachments = body.Attachments;
                messageVm.RaiseHeaderChanged();
                MessageOpen = true;
                StatusText = "已加载";
                ReaderChanged?.Invoke();
            });
            _ = RefreshFolderBadgesAsync();
        }
        catch (Exception ex)
        {
            OnUi(() => StatusText = "加载失败:" + ex.Message);
        }
    }

    // ---------- 邮件操作 ----------

    [RelayCommand]
    private async Task ToggleReadAsync()
    {
        var m = SelectedMessage;
        if (m is null || SelectedAccount is null) return;
        await GetEngine(SelectedAccount).SetSeenAsync(m.Header, !m.Header.Seen);
        OnUi(m.RaiseHeaderChanged);
    }

    [RelayCommand]
    private async Task ToggleFlagAsync()
    {
        var m = SelectedMessage;
        if (m is null || SelectedAccount is null) return;
        await GetEngine(SelectedAccount).ToggleFlaggedAsync(m.Header);
        OnUi(m.RaiseHeaderChanged);
    }

    [RelayCommand]
    private async Task DeleteMessageAsync()
    {
        var m = SelectedMessage;
        if (m is null || SelectedAccount is null) return;
        OnUi(() => StatusText = "删除中…");
        await GetEngine(SelectedAccount).DeleteAsync(m.Header);
        OnUi(() =>
        {
            Messages.Remove(m);
            SelectedMessage = null;
            MessageOpen = false;
            StatusText = "已删除";
        });
        _ = RefreshFolderBadgesAsync();
    }

    [RelayCommand]
    private async Task MoveMessageAsync(int destFolderId)
    {
        var m = SelectedMessage;
        if (m is null || SelectedAccount is null) return;
        OnUi(() => StatusText = "移动中…");
        await GetEngine(SelectedAccount).MoveAsync(m.Header, destFolderId);
        OnUi(() =>
        {
            Messages.Remove(m);
            SelectedMessage = null;
            MessageOpen = false;
            StatusText = "已移动";
        });
        _ = RefreshFolderBadgesAsync();
    }

    public Task DownloadAttachmentAsync(MessageHeader header, string fileName, string destPath)
    {
        if (SelectedAccount is null) throw new InvalidOperationException("没有已选账户");
        return GetEngine(SelectedAccount).DownloadAttachmentAsync(header, fileName, destPath);
    }

    // ---------- 写信 ----------

    public async Task SendAsync(ComposeInput input)
    {
        if (SelectedAccount is null) throw new InvalidOperationException("没有已选账户");
        await GetEngine(SelectedAccount).SendAsync(input);
    }

    public async Task SaveDraftAsync(ComposeInput input)
    {
        if (SelectedAccount is null) throw new InvalidOperationException("没有已选账户");
        await GetEngine(SelectedAccount).SaveDraftAsync(input);
    }
}
