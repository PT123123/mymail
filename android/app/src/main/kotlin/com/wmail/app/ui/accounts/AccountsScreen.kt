package com.wmail.app.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wmail.app.AppContainer
import com.wmail.app.data.db.AccountEntity
import com.wmail.app.sync.IdleService
import com.wmail.core.MailEndpoint
import com.wmail.core.MailSecurity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(container: AppContainer, onOpenAccount: (String) -> Unit) {
    val accounts by container.db.accountDao().list().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AccountEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("w-mail") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Text("+", fontSize = 22.sp) }
        },
    ) { padding ->
        if (accounts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("还没有账户,点 + 添加", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(accounts, key = { it.id }) { account ->
                    ListItem(
                        headlineContent = { Text(account.displayName) },
                        supportingContent = { Text(account.email) },
                        trailingContent = {
                            TextButton(onClick = { pendingDelete = account }) { Text("删除") }
                        },
                        modifier = Modifier.clickable { onOpenAccount(account.id) },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddAccountDialog(
            container = container,
            onDone = { showAdd = false },
            onDismiss = { showAdd = false },
        )
    }
    pendingDelete?.let { account ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除账户") },
            text = {
                Text("删除 ${account.displayName}(${account.email})?\n本地缓存将一并删除,服务器上的邮件不受影响。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    IdleService.onAccountRemoved(account.id)
                    scope.launch { container.sync.deleteAccount(account) }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

/// 服务商预设(与 README「常见邮箱服务参数」及 Windows 端 PRESETS 保持一致)。
private data class Preset(
    val label: String,
    val imap: MailEndpoint,
    val smtp: MailEndpoint,
    val hint: String,
) {
    companion object {
        val All = listOf(
            Preset("自定义", MailEndpoint("", 993, MailSecurity.SSL), MailEndpoint("", 465, MailSecurity.SSL),
                "按服务商说明填写服务器与端口。"),
            Preset("QQ 邮箱", MailEndpoint("imap.qq.com", 993, MailSecurity.SSL), MailEndpoint("smtp.qq.com", 465, MailSecurity.SSL),
                "网页版设置→账户→开启 IMAP/SMTP 服务;密码填 16 位授权码。"),
            Preset("163 邮箱", MailEndpoint("imap.163.com", 993, MailSecurity.SSL), MailEndpoint("smtp.163.com", 465, MailSecurity.SSL),
                "设置→POP3/SMTP/IMAP 开启服务;密码填客户端授权码。"),
            Preset("126 邮箱", MailEndpoint("imap.126.com", 993, MailSecurity.SSL), MailEndpoint("smtp.126.com", 465, MailSecurity.SSL),
                "同 163:开启 IMAP/SMTP 服务,密码填客户端授权码。"),
            Preset("新浪邮箱", MailEndpoint("imap.sina.com", 993, MailSecurity.SSL), MailEndpoint("smtp.sina.com", 465, MailSecurity.SSL),
                "需在设置中开启 IMAP/SMTP 服务,密码填授权码。"),
            Preset("搜狐邮箱", MailEndpoint("imap.sohu.com", 993, MailSecurity.SSL), MailEndpoint("smtp.sohu.com", 465, MailSecurity.SSL),
                "需在设置中开启 IMAP/SMTP 服务,密码填授权码。"),
            Preset("Gmail", MailEndpoint("imap.gmail.com", 993, MailSecurity.SSL), MailEndpoint("smtp.gmail.com", 465, MailSecurity.SSL),
                "需开启两步验证,并在 Google 账户里生成应用专用密码。"),
            Preset("Outlook / M365", MailEndpoint("outlook.office365.com", 993, MailSecurity.SSL), MailEndpoint("smtp.office365.com", 587, MailSecurity.STARTTLS),
                "个人账户需开两步验证并生成应用密码;企业 M365 视管理员策略而定。"),
            Preset("Yahoo Mail", MailEndpoint("imap.mail.yahoo.com", 993, MailSecurity.SSL), MailEndpoint("smtp.mail.yahoo.com", 465, MailSecurity.SSL),
                "在账户安全设置里生成应用专用密码。"),
            Preset("iCloud 邮箱", MailEndpoint("imap.mail.me.com", 993, MailSecurity.SSL), MailEndpoint("smtp.mail.me.com", 587, MailSecurity.STARTTLS),
                "在 Apple 账户「登录与安全」里生成应用专用密码。"),
            Preset("Zoho Mail", MailEndpoint("imap.zoho.com", 993, MailSecurity.SSL), MailEndpoint("smtp.zoho.com", 465, MailSecurity.SSL),
                "设置中开启 IMAP 访问;密码填应用专用密码。"),
            Preset("Yandex Mail", MailEndpoint("imap.yandex.com", 993, MailSecurity.SSL), MailEndpoint("smtp.yandex.com", 465, MailSecurity.SSL),
                "设置中启用 IMAP,并使用应用专用密码。"),
            Preset("Fastmail", MailEndpoint("imap.fastmail.com", 993, MailSecurity.SSL), MailEndpoint("smtp.fastmail.com", 465, MailSecurity.SSL),
                "在账户隐私与安全里生成应用专用密码。"),
            Preset("腾讯企业邮箱", MailEndpoint("imap.exmail.qq.com", 993, MailSecurity.SSL), MailEndpoint("smtp.exmail.qq.com", 465, MailSecurity.SSL),
                "成员端开启 IMAP/SMTP(或由管理后台开启安全登录),密码填授权码/客户端专用密码。"),
            Preset("网易企业邮箱", MailEndpoint("imap.qiye.163.com", 993, MailSecurity.SSL), MailEndpoint("smtp.qiye.163.com", 465, MailSecurity.SSL),
                "管理后台开启 IMAP 功能;密码填登录密码或客户端授权密码。"),
            Preset("阿里企业邮箱", MailEndpoint("imap.mxhichina.com", 993, MailSecurity.SSL), MailEndpoint("smtp.mxhichina.com", 465, MailSecurity.SSL),
                "由管理员开启 IMAP 服务;密码填邮箱登录密码。"),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddAccountDialog(container: AppContainer, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var preset by remember { mutableStateOf(Preset.All[0]) }
    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var imapHost by remember { mutableStateOf("") }
    var imapPort by remember { mutableStateOf("993") }
    var smtpHost by remember { mutableStateOf("") }
    var smtpPort by remember { mutableStateOf("465") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("添加账户") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Preset.All.forEach { p ->
                        TextButton(onClick = {
                            preset = p
                            imapHost = if (p.label == "自定义") "" else p.imap.host
                            smtpHost = if (p.label == "自定义") "" else p.smtp.host
                        }) { Text(p.label, fontSize = 12.sp) }
                    }
                }
                Text(
                    preset.hint,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(displayName, { displayName = it }, label = { Text("显示名称(可选,多账号时便于区分)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it }, label = { Text("邮箱地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("密码 / 授权码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(imapHost, { imapHost = it }, label = { Text("IMAP 服务器") }, singleLine = true, modifier = Modifier.weight(2f))
                    OutlinedTextField(imapPort, { imapPort = it }, label = { Text("端口") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(smtpHost, { smtpHost = it }, label = { Text("SMTP 服务器") }, singleLine = true, modifier = Modifier.weight(2f))
                    OutlinedTextField(smtpPort, { smtpPort = it }, label = { Text("端口") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                if (busy) Text("验证连接中…", fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    val imap = MailEndpoint(imapHost.trim(), imapPort.trim().toIntOrNull() ?: 993, preset.imap.security)
                    val smtp = MailEndpoint(smtpHost.trim(), smtpPort.trim().toIntOrNull() ?: 465, preset.smtp.security)
                    busy = true
                    scope.launch {
                        runCatching { container.sync.addAccount(displayName, email.trim(), password, imap, smtp) }
                            .onSuccess { onDone() }
                            .onFailure {
                                busy = false
                                error = it.message ?: it.toString()
                            }
                    }
                },
            ) { Text("添加") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}
