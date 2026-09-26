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
| UI | **Slint**(`ui/app.slint` 声明式 UI) | 用户指定;单二进制、无 .NET/WebView2 运行时依赖,Rust 生态与 Android 端一样"引擎与 UI 分层" |
| 语言 | Rust(2024 edition) | `rusqlite`/`mail-parser`/`lettre`/`imap` 覆盖全部引擎需求;发布产物是一个静态链接的 exe |
| 邮件协议 | `imap` 2.4(SSL + IDLE)+ `lettre`(SMTP) + `mail-parser`(MIME/RFC2047) | 纯 Rust、维护活跃;IDLE 经 `IdleStream` 包装支持读超时 |
| HTML 净化 | `ammonia` | 邮件 HTML 不可信,净化后落盘,点按钮用系统浏览器打开 |
| 本地缓存 | `rusqlite`(bundled SQLite)+ 手写 SQL | 轻量可控;已建 FTS5 表供 M1 搜索 |
| 凭据存储 | DPAPI(`CryptProtectData`,CurrentUser) | Windows 标准用户级加密,密文 base64 存 JSON |

> 注:M0 首版用的是 WinUI 3 + MailKit(C#),后按用户决定整体切换到 Slint + Rust;两版的同步模型与缓存结构一致(见 docs/sync.md、docs/data-model.md)。

## 3. 被否决的方案(以及为什么)

- **Fork Thunderbird 当 Windows 端**:功能最全没错,但它是 Gecko 平台上的自绘 UI(HTML/CSS),既不是 Windows 原生控件,构建链(Mozilla build system)也极其庞大;改它的成本远高于自建。
- **Fork K-9 / Thunderbird for Android 当 Android 端**:代码质量高,但 UI 是传统 XML View 体系,不符合"最好用 Compose"的要求。
- **Electron / Tauri**:UI 是网页,不算原生,内存占用与启动速度都不理想。
- **Flutter**:自绘引擎,不是系统原生控件。
- **Avalonia / Uno / MAUI 单一代码库**:跨端一致性好,但两头都不是"系统原生"(Avalonia 自绘;MAUI Windows 端其实就是 WinUI 但隔了一层)。宁可用两套技术栈换各端的原生度与顺手的生态。
- **Kotlin Multiplatform 共享业务核心**:Windows 端并不跑 Kotlin,要共享只能共享到 C# 编译不过的层面;两端各自接成熟协议库成本反而更低。

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
        │  │ Slint UI       │ │   │  │  app (Compose) │ │
        │  │ (app.slint)    │ │   │  │  Screens/Nav   │ │
        │  └───────┬────────┘ │   │  └───────┬────────┘ │
        │  ┌───────┴────────┐ │   │  ┌───────┴────────┐ │
        │  │ src/*.rs       │ │   │  │  mailcore      │ │
        │  │ imap/SMTP 封装  │ │   │  │  Jakarta Mail  │ │
        │  │ 同步引擎 sync.rs │ │   │  │  同步/IDLE 封装 │ │
        │  │ rusqlite 缓存   │ │   │  │  Room 缓存      │ │
        │  │ DPAPI 凭据      │ │   │  │  Keystore 凭据  │ │
        │  └────────────────┘ │   │  └────────────────┘ │
        └─────────────────────┘   └─────────────────────┘
```

分层原则:**UI 层(.slint)不直接碰协议库**,事件回调只调 `src/` 里的引擎方法,结果经 `slint::invoke_from_event_loop` 回到 UI。这样两端行为一致,将来要加第三端(如 Linux)也只是换 UI。

## 5. 仓库结构

```
w-mail/
├── docs/                    # 本文档 + sync.md + data-model.md + roadmap.md
├── windows/                 # Rust + Slint 工程(cargo)
│   ├── ui/app.slint         # 全部 UI 组件
│   └── src/                 # 引擎:imap / smtp / sync / db / accounts / dpapi
└── android/
    ├── mailcore/            # 纯 Kotlin JVM 库,零 Android 依赖,可单测
    └── app/                 # Compose UI + Room + WorkManager
```

## 6. 线程模型(两端一致的规则)

- 所有网络/数据库操作都在后台线程,UI 只消费结果。
- Windows:`std::thread` + 每类操作独立 IMAP 连接;结果经 `slint::invoke_from_event_loop` 切回 UI 线程;`AppState`/DB 各用一把 `Mutex` 串行化(两把锁不嵌套,避免死锁)。
- Android:Kotlin 协程 `Dispatchers.IO`;IDLE 在前台 Service 里跑,事件经 Flow 发给 UI。
- 一个账户同一时刻至多一条 IMAP 连接做同步(协议库都不是线程安全的,按账户串行化)。
