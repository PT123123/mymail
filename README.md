# w-mail

Windows + Android 双端原生邮件客户端。两端都是独立的原生应用,通过 **IMAP/SMTP 服务器**天然实现数据同步(服务器是唯一事实源,各端本地 SQLite 只做缓存与离线索引),不依赖任何自建同步服务。

## 技术栈

| 端 | UI 框架 | 语言 | 邮件协议 | 本地存储 |
|---|---|---|---|---|
| Android | Jetpack Compose (Material 3) | Kotlin | Jakarta Mail 1.6.7 | Room (SQLite) |
| Windows | Slint | Rust | `imap` 2.4(含 IDLE)+ `lettre`(SMTP)+ `mail-parser`(MIME) | rusqlite (SQLite) |

选型理由与被否方案见 [docs/architecture.md](docs/architecture.md),同步模型见 [docs/sync.md](docs/sync.md),功能路线图见 [docs/roadmap.md](docs/roadmap.md)。

## 目录结构

```
w-mail/
├── docs/               # 架构 / 同步模型 / 数据模型 / 路线图
├── windows/            # Windows 端(Rust + Slint)
│   ├── ui/app.slint    # 主窗口 / 写信 / 账户 / 移动对话框
│   └── src/
│       ├── imap.rs     # IMAP 封装(rustls,含 IDLE)
│       ├── smtp.rs     # SMTP 发送 + MIME 构建
│       ├── sync.rs     # 每账户同步引擎:缓存/回填/IDLE/离线队列
│       ├── db.rs       # SQLite 缓存(rusqlite,含 FTS5 表)
│       ├── accounts.rs # 账户列表持久化(JSON)
│       ├── dpapi.rs    # DPAPI 密码加密
│       └── main.rs     # UI 状态与事件接线
└── android/            # Android 端(Jetpack Compose)
    ├── mailcore/       # 纯 Kotlin 邮件引擎:IMAP/SMTP/HTML 净化
    └── app/            # Compose 界面 + Room + WorkManager
```

## 环境要求

**Windows 端**
- Rust 工具链(rustup,MSVC target)

**Android 端**
- JDK 17、Android SDK(platforms;android-35、build-tools 35)
- Gradle 8.9(wrapper 已配置;首次构建需联网拉取 AGP/Kotlin 依赖)

## 构建与运行

### Windows

```powershell
cd windows
cargo run                # 开发运行
cargo build --release    # 产物: target\release\wmail.exe(单文件,无运行时依赖)
```

### Android

```bash
cd android
./gradlew :app:assembleDebug        # 产物: app/build/outputs/apk/debug/
./gradlew :app:installDebug         # 装到已连接的设备/模拟器
```

或直接用 Android Studio 打开 `android/` 目录。

## 常见邮箱服务参数(两端「添加账户」已内置以下预设)

同一服务商可以有多个不同账号,直接分别添加即可;同一邮箱地址只能添加一次。Windows 端 IMAP 走 SSL(993),SMTP 支持 SSL(465)与 STARTTLS(587)。

| 服务商 | IMAP | SMTP | 备注 |
|---|---|---|---|
| QQ 邮箱 | imap.qq.com:993 (SSL) | smtp.qq.com:465 (SSL) | 需在设置里开 IMAP 并用「授权码」当密码 |
| 163 邮箱 | imap.163.com:993 (SSL) | smtp.163.com:465 (SSL) | 同上,用授权码 |
| 126 邮箱 | imap.126.com:993 (SSL) | smtp.126.com:465 (SSL) | 同上,用授权码 |
| 新浪邮箱 | imap.sina.com:993 (SSL) | smtp.sina.com:465 (SSL) | 开启 IMAP/SMTP 服务,用授权码 |
| 搜狐邮箱 | imap.sohu.com:993 (SSL) | smtp.sohu.com:465 (SSL) | 开启 IMAP/SMTP 服务,用授权码 |
| Gmail | imap.gmail.com:993 (SSL) | smtp.gmail.com:465 (SSL) | 需两步验证 + 应用专用密码;OAuth2 在路线图 M2 |
| Outlook/M365 | outlook.office365.com:993 (SSL) | smtp.office365.com:587 (STARTTLS) | 个人账户需应用密码;企业 M365 视策略,OAuth2 在 M2 |
| Yahoo Mail | imap.mail.yahoo.com:993 (SSL) | smtp.mail.yahoo.com:465 (SSL) | 应用专用密码 |
| iCloud 邮箱 | imap.mail.me.com:993 (SSL) | smtp.mail.me.com:587 (STARTTLS) | 在 Apple 账户里生成应用专用密码 |
| Zoho Mail | imap.zoho.com:993 (SSL) | smtp.zoho.com:465 (SSL) | 设置中开启 IMAP 访问 |
| Yandex Mail | imap.yandex.com:993 (SSL) | smtp.yandex.com:465 (SSL) | 启用 IMAP,用应用专用密码 |
| Fastmail | imap.fastmail.com:993 (SSL) | smtp.fastmail.com:465 (SSL) | 应用专用密码 |
| 腾讯企业邮箱 | imap.exmail.qq.com:993 (SSL) | smtp.exmail.qq.com:465 (SSL) | 管理后台/成员端开启 IMAP |
| 网易企业邮箱 | imap.qiye.163.com:993 (SSL) | smtp.qiye.163.com:465 (SSL) | 管理后台开启 IMAP |
| 阿里企业邮箱 | imap.mxhichina.com:993 (SSL) | smtp.mxhichina.com:465 (SSL) | 管理员开启 IMAP |

## 当前状态

见 [docs/roadmap.md](docs/roadmap.md)。当前为 **M0(可用骨架)**:添加账户(内置上述服务商预设、连接测试、重复邮箱校验)、同一服务商可添加多个账号、可删除账户;列文件夹、收件列表、读信(HTML 已净化)、写信/回信/发附件、已读/星标/删除/移动、IMAP IDLE 新信通知、本地缓存与离线查看。
