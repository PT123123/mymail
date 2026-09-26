namespace WMail.App;

/// <summary>写信窗口的预填上下文(回复/转发)。</summary>
public sealed class ComposeContext
{
    public string To { get; init; } = "";
    public string Cc { get; init; } = "";
    public string Subject { get; init; } = "";
    public string QuotedText { get; init; } = "";
    public string? InReplyToMessageId { get; init; }
}
