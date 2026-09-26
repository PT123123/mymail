package com.wmail.app.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
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
import com.wmail.core.MailEndpoint
import com.wmail.core.MailSecurity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(container: AppContainer, onOpenAccount: (String) -> Unit) {
    val accounts by container.db.accountDao().list().collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = { TextButton(onClick = { error = null }) { Text("好") } },
            title = { Text("出错了") },
            text = { Text(message) },
        )
    }
}

private data class Preset(val label: String, val imap: MailEndpoint, val smtp: MailEndpoint) {
    companion object {
        val All = listOf(
            Preset("自定义", MailEndpoint("", 993, MailSecurity.SSL), MailEndpoint("", 465, MailSecurity.SSL)),
            Preset("QQ 邮箱", MailEndpoint("imap.qq.com", 993, MailSecurity.SSL), MailEndpoint("smtp.qq.com", 465, MailSecurity.SSL)),
            Preset("163 邮箱", MailEndpoint("imap.163.com", 993, MailSecurity.SSL), MailEndpoint("smtp.163.com", 465, MailSecurity.SSL)),
            Preset("Gmail", MailEndpoint("imap.gmail.com", 993, MailSecurity.SSL), MailEndpoint("smtp.gmail.com", 465, MailSecurity.SSL)),
            Preset("Outlook / M365", MailEndpoint("outlook.office365.com", 993, MailSecurity.SSL), MailEndpoint("smtp.office365.com", 587, MailSecurity.STARTTLS)),
        )
    }
}

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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Preset.All.forEach { p ->
                        TextButton(onClick = {
                            preset = p
                            imapHost = if (p.label == "自定义") "" else p.imap.host
                            smtpHost = if (p.label == "自定义") "" else p.smtp.host
                        }) { Text(p.label, fontSize = 12.sp) }
                    }
                }
                OutlinedTextField(displayName, { displayName = it }, label = { Text("显示名称(可选)") }, modifier = Modifier.fillMaxWidth())
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
