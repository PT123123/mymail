# 同步模型

## 0. 一句话

**服务器是唯一事实源;本地库只是缓存 + 离线索引;所有改动先落队列再写服务器;实时性靠 IDLE,兜底靠周期同步。**

## 1. IMAP 关键语义(两端都必须遵守)

| 概念 | 含义 | 我们怎么用 |
|---|---|---|
| `UID` | 邮件在文件夹内的稳定编号 | 本地缓存的邮件唯一键 = (folder, uid) |
| `UIDVALIDITY` | 文件夹 UID 序列的世代号 | 打开文件夹先比对:变了就**清空该文件夹缓存重拉**,绝不复用旧 UID |
| `UIDNEXT` | 下一个将分配的 UID | 增量同步的游标:只拉 `uid >= 上次 UIDNEXT` 的新邮件 |
| `\*` 标志 | Seen/Flagged/Answered/Draft/Deleted | 客户端只改标志,永不擅自删邮件(删除 = 移到垃圾桶) |
| `APPEND` | 把邮件放进某文件夹 | 草稿保存、已发送归档都走它 |

## 2. 同步流程

### 打开一个文件夹时

```
1. SELECT 文件夹,读 UIDVALIDITY / UIDNEXT / EXISTS
2. UIDVALIDITY 变化 → 清本地缓存该文件夹
3. 用本地已存的最大 UID 之后的部分做 UID 拉取(新邮件入缓存)
4. 顶层 EXISTS 比本地多/少 → 校正(服务器端可能被其他端清理过)
5. 对当前可见窗口的一页,补拉 FLAGS(其他端改了已读/星标)
```

### 首次全量回填("收取所有邮件")

- 先拉**最新一页**(如 200 封)让用户立刻能用;
- 后台按 500 封一页向历史回填,只取头部(Envelope/Flags/BodyStructure),不取正文;
- 回填可暂停、可断点续传(本地记 `backfilled_uid_low`);
- 正文按需拉取,拉到即缓存(收件箱体积可控)。

### 实时性

| 端 | 机制 |
|---|---|
| Windows | 每账户一条后台任务跑 IMAP `IDLE`,收到 EXISTS 变化即触发增量同步;断线自动重连(指数退避);应用退出时停 |
| Android | 应用在前台:前台 Service 跑 IDLE;应用在后台:WorkManager 每 ≥15 分钟周期同步(系统下限),收到新邮件发系统通知 |

### 离线与操作队列

写操作(标记已读、星标、移动、删除、发信)在离线时**先写本地 op 队列表,UI 乐观更新**,联网后按序重放:

```
outbox_ops(id, account_id, folder, uid, op_type, payload_json, created_at, retry_count)
```

- op 幂等:重复应用同一标志/移动不会出错(IMAP 天然幂等);
- 冲突策略:**服务器赢**。重放时如果邮件已被移动/删除,该 op 标记失败丢弃,UI 提示;
- 发信特殊:先入 Outbox 本地表(应用内"发件箱"),网络可用才走 SMTP;发送成功后 `APPEND` 到服务器的 Sent 文件夹(若服务器不自动归档)。

### 草稿

两端草稿统一存服务器 `Drafts` 文件夹(IMAP APPEND,带 `\Draft` 标志),自动保存间隔 20 秒或失焦时。另一端自然能看到。

## 3. 搜索

双通道,UI 合并去重:

1. **本地 FTS5**(SQLite full-text):对已缓存的头/正文片段做即时搜索,离线可用;
2. **服务器端 IMAP SEARCH**(`TEXT`/`FROM`/`SUBJECT`):补漏,结果逐页取回摘要。

## 4. 垃圾箱与文件夹语义

- 删除 = `MOVE` 到有 `\Trash` 属性的文件夹;找不到垃圾桶才用 `\Deleted + EXPUNGE`。
- 垃圾邮件:识别 `\Junk` 属性文件夹,支持"标记为垃圾"(移入 Junk)。
- 文件夹层级用服务器返回的 delimiter 建 Tree;`XLIST`/特殊属性(Sent/Drafts/Trash/Junk/All)用来映射语义文件夹,不做硬编码猜测。

## 5. 协议兼容性注意

- 不假设服务器支持 `CONDSTORE/QRESYNC`(M1 探测 `CAPABILITY`,支持则启用快速标志同步,不支持退回第 2 节的 FLAGS 补拉)。
- Gmail 有自己的 All Mail/标签模型:Gmail 账户按普通 IMAP 文件夹处理(M0),X-GM-EXT 能力放在 M3。
- Exchange ActiveSync **不支持**,只走 IMAP(Outlook.com/M365 都有 IMAP)。
