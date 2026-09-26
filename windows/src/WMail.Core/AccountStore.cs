using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace WMail.Core;

/// <summary>账户列表持久化:%APPDATA%\WMail\accounts.json,密码走 DPAPI。</summary>
public static class AccountStore
{
    private static readonly JsonSerializerOptions JsonOpts = new() { WriteIndented = true };

    private static string Dir
    {
        get
        {
            var roaming = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
            return Path.Combine(roaming, "WMail");
        }
    }

    private static string FilePath => Path.Combine(Dir, "accounts.json");

    public static List<AccountConfig> Load()
    {
        Paths.EnsureDirs();
        if (!File.Exists(FilePath)) return new List<AccountConfig>();
        try
        {
            var json = File.ReadAllText(FilePath);
            return JsonSerializer.Deserialize<List<AccountConfig>>(json) ?? new List<AccountConfig>();
        }
        catch
        {
            // 配置损坏时不让应用崩:备份后从头开始
            try { File.Move(FilePath, FilePath + ".bad", overwrite: true); } catch { }
            return new List<AccountConfig>();
        }
    }

    public static void Save(IReadOnlyList<AccountConfig> accounts)
    {
        Paths.EnsureDirs();
        Directory.CreateDirectory(Dir);
        var json = JsonSerializer.Serialize(accounts, JsonOpts);
        File.WriteAllText(FilePath, json);
    }

    public static string Protect(string plain)
        => Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(plain), null, DataProtectionScope.CurrentUser));

    public static string Unprotect(string protectedBase64)
        => Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(protectedBase64), null, DataProtectionScope.CurrentUser));
}
