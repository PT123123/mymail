using Microsoft.UI.Xaml;
using Windows.Storage.Pickers;
using WMail.App.ViewModels;
using WMail.Core;

namespace WMail.App.Views;

public sealed partial class ComposeWindow : Window
{
    private readonly MainViewModel _vm;
    private readonly string? _inReplyToMessageId;
    private readonly List<string> _attachments = new();

    public ComposeWindow(MainViewModel vm, ComposeContext? ctx)
    {
        _vm = vm;
        _inReplyToMessageId = ctx?.InReplyToMessageId;
        InitializeComponent();

        if (ctx is not null)
        {
            ToBox.Text = ctx.To;
            CcBox.Text = ctx.Cc;
            SubjectBox.Text = ctx.Subject;
            BodyBox.Text = ctx.QuotedText;
        }
    }

    private void UpdateAttachBox()
        => AttachBox.Text = _attachments.Count == 0 ? "" : string.Join("; ", _attachments);

    private async void OnAddAttachment(object sender, RoutedEventArgs e)
    {
        var picker = new FileOpenPicker();
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(this));
        picker.FileTypeFilter.Add("*");
        picker.ViewMode = PickerViewMode.List;

        var files = await picker.PickMultipleFilesAsync();
        foreach (var f in files)
        {
            if (!_attachments.Contains(f.Path)) _attachments.Add(f.Path);
        }
        UpdateAttachBox();
    }

    private ComposeInput BuildInput()
    {
        var input = new ComposeInput
        {
            To = ToBox.Text.Trim(),
            Cc = CcBox.Text.Trim(),
            Subject = SubjectBox.Text,
            Body = BodyBox.Text,
            InReplyToMessageId = _inReplyToMessageId,
        };
        input.AttachmentPaths.AddRange(_attachments);
        return input;
    }

    private async void OnSend(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(ToBox.Text) && string.IsNullOrWhiteSpace(CcBox.Text))
        {
            FeedbackText.Text = "请至少填写一个收件人";
            return;
        }
        FeedbackText.Text = "发送中…";
        try
        {
            await _vm.SendAsync(BuildInput());
            Close();
        }
        catch (Exception ex)
        {
            FeedbackText.Text = "发送失败:" + ex.Message;
        }
    }

    private async void OnSaveDraft(object sender, RoutedEventArgs e)
    {
        FeedbackText.Text = "保存草稿中…";
        try
        {
            await _vm.SaveDraftAsync(BuildInput());
            Close();
        }
        catch (Exception ex)
        {
            FeedbackText.Text = "保存草稿失败:" + ex.Message;
        }
    }

    private void OnCancel(object sender, RoutedEventArgs e) => Close();
}
