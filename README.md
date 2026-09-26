# w-mail

Windows + Android 双端原生邮件客户端。两端都是独立的原生应用,通过 **IMAP/SMTP 服务器**天然实现数据同步(服务器是唯一事实源,各端本地 SQLite 只做缓存与离线索引),不依赖任何自建同步服务。

## 技术栈

| 端 | UI 框架 | 语言 | 邮件协议 | 本地存储 |
|---|---|---|---|---|
| Android | Jetpack Compose (Material 3) | Kotlin | Jakarta Mail 1.6.7 | Room (SQLite) |
| Windows | WinUI 3 (Windows App SDK) | C# | MailKit | Microsoft.Data.Sqlite (SQLite) |

选型理由与被否方案见 [docs/architecture.md](docs/architecture.md),同步模型见 [docs/sync.md](docs/sync.md),功能路线图见 [docs/roadmap.md](docs/roadmap.md)。

## 目录结构

```
w-mail/
├── docs/               # 架构 / 同步模型 / 数据模型 / 路线图
├── windows/            # Windows 端(WinUI 3)
│   ├── WMail.sln
│   └── src/
│       ├── WMail.Core/     # 平台无关服务层:IMAP/SMTP/SQLite 缓存/同步引擎
│       └── WMail.App/      # WinUI 3 界面
└── android/            # Android 端(Jetpack Compose)
    ├── mailcore/       # 纯 Kotlin 邮件引擎:IMAP/SMTP/HTML 净化
    └── app/            # Compose 界面 + Room + WorkManager
```

## 环境要求

**Windows 端**
- .NET SDK 8+(构建)
- 运行:`dotnet publish` 出自包含包,或安装 .NET 8 Desktop Runtime

**Android 端**
- JDK 17、Android SDK(platforms;android-35、build-tools 35)
- Gradle 8.9(wrapper 已配置;首次构建需联网拉取 AGP/Kotlin 依赖)

## 构建与运行

### Windows

```powershell
cd windows
dotnet build src\WMail.App\WMail.App.csproj -c Debug -p:Platform=x64
# 生成可直接双击运行的 exe(自包含,无需装运行时):
dotnet publish src\WMail.App\WMail.App.csproj -c Release -p:Platform=x64 -r win-x64 --self-contained true -o publish\
```

用 Visual Studio 2022 打开 `WMail.sln` 也可以(F5 调试建议装「Windows 应用程序开发」工作负载)。

### Android

```bash
cd android
./gradlew :app:assembleDebug        # 产物: app/build/outputs/apk/debug/
./gradlew :app:installDebug         # 装到已连接的设备/模拟器
```

或直接用 Android Studio 打开 `android/` 目录。

## 常见邮箱服务参数(添加账户时可参考)

| 服务商 | IMAP | SMTP | 备注 |
|---|---|---|---|
| QQ 邮箱 | imap.qq.com:993 (SSL) | smtp.qq.com:465 (SSL) | 需在设置里开 IMAP 并用「授权码」当密码 |
| 163 邮箱 | imap.163.com:993 (SSL) | smtp.163.com:465 (SSL) | 同上,用授权码 |
| Gmail | imap.gmail.com:993 (SSL) | smtp.gmail.com:465 (SSL) | 需应用专用密码;OAuth2 在路线图 M2 |
| Outlook/M365 | outlook.office365.com:993 (SSL) | smtp.office365.com:587 (STARTTLS) | 基本认证已被微软禁用,等 M2 OAuth2 |

## 当前状态

见 [docs/roadmap.md](docs/roadmap.md)。当前为 **M0(可用骨架)**:添加账户、列文件夹、收件列表、读信(HTML 已净化)、写信/回信/发附件、已读/星标/删除/移动、IMAP IDLE 新信通知、本地缓存与离线查看。
