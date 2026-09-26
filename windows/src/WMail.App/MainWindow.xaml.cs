using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Storage.Pickers;
using WMail.App.ViewModels;
using WMail.Core;

namespace WMail.App;

public sealed partial class MainWindow : Window
{
    public MainViewModel Vm { get; }

    public MainWindow()
    {
        Vm = new MainViewModel
        {
            Dispatcher = DispatcherQueue,
        };
        InitializeComponent();

        Vm.ReaderChanged += OnReaderChanged;
        _ = Vm.InitializeAsync();
    }

    // ---------- x:Bind 可见性函数 ----------

    public Visibility ShowWelcome(bool hasAccount) => hasAccount ? Visibility.Collapsed : Visibility.Visible;
    public Visibility HideWelcome(bool hasAccount) => hasAccount ? Visibility.Visible : Visibility.Collapsed;
    public Visibility ShowReaderEmpty(bool messageOpen) => messageOpen ? Visibility.Collapsed : Visibility.Visible;

    // ---------- 阅读窗格 ----------

    private async void OnReaderChanged()
    {
        var m = Vm.SelectedMessage;
        if (m is null) return;
        try
        {
            await Reader.EnsureCoreWebView2Async();
            if (!string.IsNullOrEmpty(m.BodyHtmlFilePath) && File.Exists(m.BodyHtmlFilePath))
            {
                // 正文缓存文件已是净化后的 HTML
                Reader.CoreWebView2.Navigate(new Uri(m.BodyHtmlFilePath).AbsoluteUri);
            }
            else
            {
                var text = m.PlainTextFallback ?? "";
                Reader.NavigateToString(
                    "<html><body style='font-family:Segoe UI,sans-serif;white-space:pre-wrap;padding:12px'>"
                    + System.Net.WebUtility.HtmlEncode(text) + "</body></html>");
            }
        }
        catch (Exception ex)
        {
            Vm.StatusText = "正文渲染失败:" + ex.Message;
        }
    }

    // ---------- 账户 ----------

    private async void OnAddAccount(object sender, RoutedEventArgs e)
    {
        var dialog = new Views.AccountDialog { XamlRoot = Content.XamlRoot };
        await dialog.ShowAsync();
        if (dialog.Result is null) return;
        try
        {
            Vm.AddAccount(dialog.Result);
        }
        catch (Exception ex)
        {
            Vm.StatusText = ex.Message;
        }
    }

    // ---------- 写信 / 回复 / 转发 ----------

    private void OnCompose(object sender, RoutedEventArgs e) => OpenCompose(null);

    private void OnReply(object sender, RoutedEventArgs e) => OpenReply(replyAll: false);

    private void OnReplyAll(object sender, RoutedEventArgs e) => OpenReply(replyAll: true);

    private void OpenReply(bool replyAll)
    {
        var m = Vm.SelectedMessage;
        if (m is null) return;
        var to = ExtractAddress(m.Header.From);
        var cc = "";
        if (replyAll)
        {
            // v0 简化:除自己外的原收件人放进 Cc
            var others = (m.Header.To ?? "")
                .Split(new[] { ';', ',', '\n', '\r' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                .Where(a => !a.Contains(Vm.SelectedAccount?.Email ?? "\u0000", StringComparison.OrdinalIgnoreCase));
            cc = string.Join("; ", others);
        }
        var subject = m.Header.Subject.StartsWith("Re:", StringComparison.OrdinalIgnoreCase)
            ? m.Header.Subject
            : "Re: " + m.Header.Subject;
        OpenCompose(new ComposeContext
        {
            To = to,
            Cc = cc,
            Subject = subject,
            QuotedText = SmtpService.QuoteForReply(m.Header.From, m.Header.DateUnix.ToString(), m.PlainTextFallback),
            InReplyToMessageId = m.Header.MessageId,
        });
    }

    private void OnForward(object sender, RoutedEventArgs e)
    {
        var m = Vm.SelectedMessage;
        if (m is null) return;
        var subject = m.Header.Subject.StartsWith("Fw:", StringComparison.OrdinalIgnoreCase)
            ? m.Header.Subject
            : "Fw: " + m.Header.Subject;
        OpenCompose(new ComposeContext
        {
            Subject = subject,
            QuotedText = "\n\n---------- 转发的邮件 ----------\n"
                + $"发件人:{m.Header.From}\n日期:{DateTimeOffset.FromUnixTimeSeconds(m.Header.DateUnix).LocalDateTime}\n主题:{m.Header.Subject}\n\n"
                + (m.PlainTextFallback ?? ""),
        });
    }

    private void OpenCompose(ComposeContext? ctx)
    {
        var window = new Views.ComposeWindow(Vm, ctx);
        window.Activate();
    }

    private static string ExtractAddress(string display)
    {
        var start = display.IndexOf('<');
        var end = display.IndexOf('>');
        if (start >= 0 && end > start) return display[(start + 1)..end].Trim();
        return display.Trim();
    }

    // ---------- 邮件操作 ----------

    private void OnToggleRead(object sender, RoutedEventArgs e) => _ = Vm.ToggleReadCommand.ExecuteAsync(null);
    private void OnToggleFlag(object sender, RoutedEventArgs e) => _ = Vm.ToggleFlagCommand.ExecuteAsync(null);

    private async void OnDelete(object sender, RoutedEventArgs e)
    {
        var dialog = new ContentDialog
        {
            Title = "删除邮件",
            Content = "把这封邮件移到垃圾桶?",
            PrimaryButtonText = "删除",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = Content.XamlRoot,
        };
        var result = await dialog.ShowAsync();
        if (result == ContentDialogResult.Primary)
        {
            _ = Vm.DeleteMessageCommand.ExecuteAsync(null);
        }
    }

    private async void OnMove(object sender, RoutedEventArgs e)
    {
        var m = Vm.SelectedMessage;
        if (m is null) return;
        var combo = new ComboBox
        {
            MinWidth = 240,
            ItemsSource = Vm.Folders.ToList(),
            DisplayMemberPath = "DisplayName",
            SelectedIndex = 0,
        };
        var dialog = new ContentDialog
        {
            Title = "移动到…",
            Content = combo,
            PrimaryButtonText = "移动",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = Content.XamlRoot,
        };
        var result = await dialog.ShowAsync();
        if (result == ContentDialogResult.Primary && combo.SelectedItem is FolderVm dest)
        {
            _ = Vm.MoveMessageCommand.ExecuteAsync(dest.Info.Id);
        }
    }

    private async void OnOpenAttachment(object sender, RoutedEventArgs e)
    {
        var m = Vm.SelectedMessage;
        if (m is null || sender is not Button { DataContext: AttachmentInfo att }) return;

        var ext = Path.GetExtension(att.FileName);
        if (string.IsNullOrEmpty(ext)) ext = ".dat";

        var picker = new FileSavePicker
        {
            SuggestedFileName = Path.GetFileNameWithoutExtension(att.FileName),
            SuggestedStartLocation = PickerLocationId.Downloads,
        };
        picker.FileTypeChoices.Add("文件", new List<string> { ext });
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(this));

        var file = await picker.PickSaveFileAsync();
        if (file is null) return;

        try
        {
            Vm.StatusText = "下载附件 " + att.FileName + "…";
            await Vm.DownloadAttachmentAsync(m.Header, att.FileName, file.Path);
            Vm.StatusText = "附件已保存:" + file.Path;
        }
        catch (Exception ex)
        {
            Vm.StatusText = "附件下载失败:" + ex.Message;
        }
    }
}
