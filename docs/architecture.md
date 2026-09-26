# 总体架构

## 1. 目标与核心思路

w-mail 是 Windows + Android 双端原生邮件客户端。核心架构决策只有一条:

> **不造同步轮子:IMAP/SMTP 服务器本身就是同步层。**

两个端的客户端都是标准 IMAP 客户端。已读/星标/文件夹/草稿等状态存在服务器上,任何一端的修改写回服务器,另一端下一次同步(或 IDLE 推送)就能看到。每端各有一份本地 SQLite 缓存,只用于离线查看、快速列表和全文搜索。这样"双端同步"不依赖我们运维任何服务端,也和 Outlook/Thunderbird 等成熟客户端的行为一致。

## 2. 技术选型

### Android

| 项 | 选择 | 理由 |
|---|---|---|
| UI | Jetpack Compose + Material 3 | 用户指定;官方现役方向 |
| 语言 | Kotlin 2.0 + 协程 | Android 官方首选 |
| 邮件协议 | Jakarta Mail 1.6.7(`com.sun.mail:jakarta.mail` + `com.sun.mail:android-activation`) | JavaMail 1.6.x 在 Android 上久经考验(K-9 血统),自带 IMAP IDLE |
| HTML 净化 | Jsoup `Safelist` | 邮件 HTML 不可信,必须净化后才能进 WebView |
| 本地缓存 | Room (SQLite) | 官方 ORM,支持 FTS 全文索引 |
| 后台同步 | WorkManager(周期兜底)+ 前台 Service(IDLE 实时) | 系统省电约束下的标准组合 |
| 凭据存储 | EncryptedSharedPreferences(Android Keystore) | 密码绝不明文落盘 |

### Windows

| 项 | 选择 | 理由 |
|---|---|---|
| UI | **WinUI 3**(Windows App SDK,C#) | 微软现役原生 UI 框架,Fluent Design,框架本身开源(microsoft-ui-xaml,MIT) |
| 语言 | C# / .NET 8 | 服务层生态远好于 C++/WinRT,界面模型与 XAML 一脉相承 |
| 邮件协议 | **MailKit**(+MimeKit) | .NET 事实标准,开源( MIT),IMAP/SMTP/POP3/MIME 全覆盖,维护活跃 |
| HTML 净化 | HtmlSanitizer(Ganss.Xss) | 同上,净化后再交给 WebView2 渲染 |
| HTML 渲染 | WebView2 | WinUI 3 自带控件,渲染真实网页邮件 |
| 本地缓存 | Microsoft.Data.Sqlite + 手写 SQL | 轻量可控;EF Core 对这个体量是负资产 |
| 凭据存储 | DPAPI(`ProtectedData`) | Windows 标准用户级加密 |
| MVVM | CommunityToolkit.Mvvm | 源生成器,样板最少 |

## 3. 被否决的方案(以及为什么)

- **Fork Thunderbird 当 Windows 端**:功能最全没错,但它是 Gecko 平台上的自绘 UI(HTML/CSS),既不是 Windows 原生控件,构建链(Mozilla build system)也极其庞大;改它的成本远高于自建。
- **Fork K-9 / Thunderbird for Android 当 Android 端**:代码质量高,但 UI 是传统 XML View 体系,不符合"最好用 Compose"的要求。
- **Electron / Tauri**:UI 是网页,不算原生,内存占用与启动速度都不理想。
- **Flutter**:自绘引擎,不是系统原生控件。
- **Avalonia / Uno / MAUI 单一代码库**:跨端一致性好,但两头都不是"系统原生"(Avalonia 自绘;MAUI Windows 端其实就是 WinUI 但隔了一层)。用户明确要求"尽量两端原生",所以宁可用两套 UI 换原生度。
- **Kotlin Multiplatform 共享业务核心**:Windows 端并不跑 Kotlin,要共享只能共享到 C# 编译不过的层面;两端各自接成熟协议库(MailKit / Jakarta Mail)成本反而更低。

## 4. 模块图

```
                    ┌────────────────────────┐
                    │   IMAP / SMTP 服务器    │  ← 事实源:邮件、文件夹、标志、草稿
                    └───────┬───────┬────────┘
                            │       │
        IDLE 推送 / 分页拉取 │       │ IDLE 推送 / 分页拉取
                            │       │
        ┌───────────────────┴─┐   ┌─┴───────────────────┐
        │      Windows 端      │   │      Android 端      │
        │  ┌────────────────┐ │   │  ┌────────────────┐ │
        │  │  WMail.App     │ │   │  │  app (Compose) │ │
        │  │  WinUI 3 UI    │ │   │  │  Screens/Nav   │ │
        │  └───────┬────────┘ │   │  └───────┬────────┘ │
        │  ┌───────┴────────┐ │   │  ┌───────┴────────┐ │
        │  │  WMail.Core    │ │   │  │  mailcore      │ │
        │  │  MailKit 封装   │ │   │  │  Jakarta Mail  │ │
        │  │  SyncEngine    │ │   │  │  同步/IDLE 封装 │ │
        │  │  SQLite 缓存    │ │   │  │  Room 缓存      │ │
        │  │  DPAPI 凭据     │ │   │  │  Keystore 凭据  │ │
        │  └────────────────┘ │   │  └────────────────┘ │
        └─────────────────────┘   └─────────────────────┘
```

分层原则:**UI 层不直接碰协议库**,只调 Core 的异步 API 和事件。这样两端行为一致、Core 可单独测试,将来要加第三端(如 Linux)也只是换 UI。

## 5. 仓库结构

```
w-mail/
├── docs/                    # 本文档 + sync.md + data-model.md + roadmap.md
├── windows/
│   ├── WMail.sln
│   └── src/
│       ├── WMail.Core/      # net8.0 类库,零 Windows 依赖,可单测
│       └── WMail.App/       # WinUI 3 壳
└── android/
    ├── mailcore/            # 纯 Kotlin JVM 库,零 Android 依赖,可单测
    └── app/                 # Compose UI + Room + WorkManager
```

## 6. 线程模型(两端一致的规则)

- 所有网络/数据库操作都在后台线程,UI 只消费结果。
- Windows:`async/await` + `IAsyncEnumerable` 分页;IDLE 事件通过 `DispatcherQueue.TryEnqueue` 切回 UI 线程。
- Android:Kotlin 协程 `Dispatchers.IO`;IDLE 在前台 Service 里跑,事件经 Flow 发给 UI。
- 一个账户同一时刻至多一条 IMAP 连接做同步(MailKit / JavaMail 都不是线程安全的,按账户串行化,`SemaphoreSlim` / `Mutex` 保护)。
