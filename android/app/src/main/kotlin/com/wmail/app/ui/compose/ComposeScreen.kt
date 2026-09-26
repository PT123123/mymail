package com.wmail.app.ui.compose

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wmail.app.AppContainer
import com.wmail.core.ComposeDraft
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    container: AppContainer,
    accountId: String,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val attachments = mutableStateListOf<String>()

    // 预填(回复/转发)并取走 pendingDraft
    val pending = remember { container.pendingDraft.also { container.pendingDraft = null } }
    var to by remember { mutableStateOf(pending?.to?.joinToString("; ") ?: "") }
    var cc by remember { mutableStateOf(pending?.cc?.joinToString("; ") ?: "") }
    var subject by remember { mutableStateOf(pending?.subject ?: "") }
    var body by remember { mutableStateOf(pending?.body ?: "") }
    val inReplyToMessageId = pending?.inReplyToMessageId
    remember { attachments.addAll(pending?.attachmentPaths ?: emptyList()) }

    val pickAttachment = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val name = queryDisplayName(context, uri) ?: "attachment-${attachments.size + 1}"
                    val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
                    val dest = File(dir, name)
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                    attachments += dest.absolutePath
                }.onFailure { status = "附件添加失败:${it.message}" }
            }
        }
    }

    fun buildDraft() = ComposeDraft(
        to = to.split(";", ",").map { it.trim() }.filter { it.contains("@") },
        cc = cc.split(";", ",").map { it.trim() }.filter { it.contains("@") },
        subject = subject,
        body = body,
        attachmentPaths = attachments.toList(),
        inReplyToMessageId = inReplyToMessageId,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("写邮件") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Text("←", fontSize = 20.sp) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(to, { to = it }, label = { Text("收件人(多个用 ; 分隔)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cc, { cc = it }, label = { Text("抄送") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(subject, { subject = it }, label = { Text("主题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(body, { body = it }, label = { Text("正文") }, modifier = Modifier.fillMaxWidth().weight(1f, fill = false))

            if (attachments.isNotEmpty()) {
                Text(attachments.joinToString("\n") { "📎 ${File(it).name}" }, fontSize = 12.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = !busy, onClick = { pickAttachment.launch(arrayOf("*/*")) }) { Text("添加附件") }
                Button(
                    enabled = !busy,
                    onClick = {
                        if (buildDraft().to.isEmpty() && buildDraft().cc.isEmpty()) {
                            status = "请至少填写一个收件人"
                            return@Button
                        }
                        busy = true
                        status = "发送中…"
                        scope.launch {
                            runCatching {
                                val account = container.db.accountDao().byId(accountId) ?: error("账户不存在")
                                container.sync.send(account, buildDraft())
                            }
                                .onSuccess { onDone() }
                                .onFailure {
                                    busy = false
                                    status = "发送失败:${it.message}"
                                }
                        }
                    },
                ) { Text("发送") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        status = "保存草稿…"
                        scope.launch {
                            runCatching {
                                val account = container.db.accountDao().byId(accountId) ?: error("账户不存在")
                                container.sync.saveDraft(account, buildDraft())
                            }
                                .onSuccess { onDone() }
                                .onFailure {
                                    busy = false
                                    status = "保存草稿失败:${it.message}"
                                }
                        }
                    },
                ) { Text("存草稿") }
            }

            if (status.isNotEmpty()) {
                Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    LaunchedEffect(Unit) { /* 占位:保持结构稳定 */ }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
