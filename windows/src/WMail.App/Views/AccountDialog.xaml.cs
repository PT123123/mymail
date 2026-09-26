using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using WMail.Core;

namespace WMail.App.Views;

public sealed partial class AccountDialog : ContentDialog
{
    /// <summary>用户点「保存」且校验通过后携带结果,否则为 null。</summary>
    public AccountConfig? Result { get; private set; }

    public AccountDialog()
    {
        InitializeComponent();
        PrimaryButtonClick += OnPrimaryButtonClick;
        SecondaryButtonClick += OnSecondaryButtonClick;
    }

    private static MailSecurity SecurityOf(ComboBox box) => box.SelectedIndex switch
    {
        1 => MailSecurity.StartTls,
        2 => MailSecurity.None,
        _ => MailSecurity.SslOnConnect,
    };

    private (AccountConfig? config, string error) BuildFromFields()
    {
        var email = EmailBox.Text.Trim();
        var password = PasswordBox.Password;
        var imapHost = ImapHostBox.Text.Trim();
        var smtpHost = SmtpHostBox.Text.Trim();
        if (string.IsNullOrEmpty(email) || !email.Contains('@')) return (null, "请填写正确的邮箱地址");
        if (string.IsNullOrEmpty(password)) return (null, "请填写密码(或服务商授权码)");
        if (string.IsNullOrEmpty(imapHost) || string.IsNullOrEmpty(smtpHost)) return (null, "请填写 IMAP / SMTP 服务器");
        if (!int.TryParse(ImapPortBox.Text.Trim(), out var imapPort) || !int.TryParse(SmtpPortBox.Text.Trim(), out var smtpPort))
            return (null, "端口必须是数字");

        var config = new AccountConfig
        {
            DisplayName = string.IsNullOrWhiteSpace(DisplayNameBox.Text) ? email : DisplayNameBox.Text.Trim(),
            Email = email,
            User = email,
            ImapHost = imapHost,
            ImapPort = imapPort,
            ImapSecurity = SecurityOf(ImapSecurityBox),
            SmtpHost = smtpHost,
            SmtpPort = smtpPort,
            SmtpSecurity = SecurityOf(SmtpSecurityBox),
        }.WithPassword(password);
        return (config, "");
    }

    private async void OnPrimaryButtonClick(ContentDialog sender, ContentDialogButtonClickEventArgs args)
    {
        var deferral = args.GetDeferral();
        args.Cancel = true;
        try
        {
            var (config, error) = BuildFromFields();
            if (config is null)
            {
                FeedbackText.Text = error;
                return;
            }
            FeedbackText.Text = "正在验证 IMAP 连接…";
            try
            {
                var svc = new ImapService(config);
                try
                {
                    await svc.ConnectAsync();
                }
                finally
                {
                    svc.Dispose();
                }
            }
            catch (Exception ex)
            {
                FeedbackText.Text = "IMAP 连接失败:" + ex.Message;
                return;
            }
            Result = config;
            Hide();
        }
        finally
        {
            deferral.Complete();
        }
    }

    private async void OnSecondaryButtonClick(ContentDialog sender, ContentDialogButtonClickEventArgs args)
    {
        var deferral = args.GetDeferral();
        args.Cancel = true;
        try
        {
            var (config, error) = BuildFromFields();
            if (config is null)
            {
                FeedbackText.Text = error;
                return;
            }
            FeedbackText.Text = "测试中…";
            var svc = new ImapService(config);
            try
            {
                var folders = await svc.ListFoldersAsync();
                FeedbackText.Text = $"连接成功,发现 {folders.Count} 个文件夹。";
            }
            catch (Exception ex)
            {
                FeedbackText.Text = "连接失败:" + ex.Message;
            }
            finally
            {
                svc.Dispose();
            }
        }
        finally
        {
            deferral.Complete();
        }
    }

    private void OnProviderChanged(object sender, SelectionChangedEventArgs e)
    {
        switch (ProviderBox.SelectedIndex)
        {
            case 1: // QQ
                ImapHostBox.Text = "imap.qq.com"; ImapPortBox.Text = "993"; ImapSecurityBox.SelectedIndex = 0;
                SmtpHostBox.Text = "smtp.qq.com"; SmtpPortBox.Text = "465"; SmtpSecurityBox.SelectedIndex = 0;
                break;
            case 2: // 163
                ImapHostBox.Text = "imap.163.com"; ImapPortBox.Text = "993"; ImapSecurityBox.SelectedIndex = 0;
                SmtpHostBox.Text = "smtp.163.com"; SmtpPortBox.Text = "465"; SmtpSecurityBox.SelectedIndex = 0;
                break;
            case 3: // Gmail
                ImapHostBox.Text = "imap.gmail.com"; ImapPortBox.Text = "993"; ImapSecurityBox.SelectedIndex = 0;
                SmtpHostBox.Text = "smtp.gmail.com"; SmtpPortBox.Text = "465"; SmtpSecurityBox.SelectedIndex = 0;
                break;
            case 4: // Outlook
                ImapHostBox.Text = "outlook.office365.com"; ImapPortBox.Text = "993"; ImapSecurityBox.SelectedIndex = 0;
                SmtpHostBox.Text = "smtp.office365.com"; SmtpPortBox.Text = "587"; SmtpSecurityBox.SelectedIndex = 1;
                break;
        }
    }
}
