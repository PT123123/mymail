package com.wmail.app.ui.detail

import android.content.Intent
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.wmail.app.AppContainer
import com.wmail.app.data.BodyView
import com.wmail.app.data.db.FolderEntity
import com.wmail.app.data.db.MessageEntity
import com.wmail.core.ComposeDraft
import com.wmail.core.SmtpClient
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    container: AppContainer,
    accountId: String,
    folderId: Long,
    uid: Long,
    onBack: () -> Unit,
    onReply: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf<com.wmail.app.data.db.AccountEntity?>(null) }
    var folder by remember { mutableStateOf<FolderEntity?>(null) }
    var message by remember { mutableStateOf<MessageEntity?>(null) }
    var body by remember { mutableStateOf<BodyView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(uid) {
        val acc = container.db.accountDao().byId(accountId) ?: return@LaunchedEffect
        val f = container.db.folderDao().byId(folderId) ?: return@LaunchedEffect
        val m = container.db.messageDao().find(folderId, uid) ?: return@LaunchedEffect
        account = acc
        folder = f
        message = m
        runCatching { container.sync.loadBody(acc, f, m) }
            .onSuccess { body = it }
            .onFailure { error = it.message ?: it.toString() }
    }

    fun buildReply(replyAll: Boolean, forward: Boolean): ComposeDraft? {
        val m = message ?: return null
        val quotedBody = if (forward) {
            "\n\n---------- 转发的邮件 ----------\n" +
                "发件人:${m.fromAddr}\n主题:${m.subject}\n\n" +
                (body?.text ?: "")
        } else {
            SmtpClient.quoteForReply(m.fromAddr, m.dateUnix, body?.text)
        }
        val subject = if (forward) {
            if (m.subject.startsWith("Fw:")) m.subject else "Fw: ${m.subject}"
        } else {
            if (m.subject.startsWith("Re:")) m.subject else "Re: ${m.subject}"
        }
        val to = if (forward) emptyList() else listOfNotNull(extractAddress(m.fromAddr).takeIf { it.isNotBlank() })
        val cc = if (replyAll) {
            m.toAddrs.split(";", ",").map { it.trim() }
                .filter { it.contains("@") && !it.equals(account?.email, ignoreCase = true) }
        } else emptyList()
        return ComposeDraft(
            to = to,
            cc = cc,
            subject = subject,
            body = quotedBody,
            inReplyToMessageId = if (forward) null else m.messageId,
        )
    }

    fun delete() {
        val m = message ?: return
        val f = folder ?: return
        val acc = account ?: return
        scope.launch {
            busy = true
            runCatching { container.sync.delete(acc, f, m) }
                .onSuccess { onBack() }
                .onFailure {
                    busy = false
                    error = "删除失败:${it.message}"
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(message?.subject?.ifBlank { "(无主题)" } ?: "", fontSize = 16.sp, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", fontSize = 20.sp) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "${message?.fromAddr ?: ""}  ·  ${message?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it.dateUnix * 1000)) } ?: ""}",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(enabled = !busy, onClick = {
                    buildReply(replyAll = false, forward = false)?.let {
                        container.pendingDraft = it
                        onReply()
                    }
                }) { Text("回复") }
                OutlinedButton(enabled = !busy, onClick = {
                    buildReply(replyAll = true, forward = false)?.let {
                        container.pendingDraft = it
                        onReply()
                    }
                }) { Text("全部回复") }
                OutlinedButton(enabled = !busy, onClick = {
                    buildReply(replyAll = false, forward = true)?.let {
                        container.pendingDraft = it
                        onReply()
                    }
                }) { Text("转发") }
                OutlinedButton(enabled = !busy, onClick = {
                    val m = message ?: return@OutlinedButton
                    val f = folder ?: return@OutlinedButton
                    val acc = account ?: return@OutlinedButton
                    scope.launch { container.sync.toggleFlagged(acc, f, m) }
                }) { Text(if (message?.isFlagged == true) "取消星标" else "星标") }
                OutlinedButton(enabled = !busy, onClick = {
                    val m = message ?: return@OutlinedButton
                    val f = folder ?: return@OutlinedButton
                    val acc = account ?: return@OutlinedButton
                    scope.launch { container.sync.setSeen(acc, f, m, !m.isSeen) }
                }) { Text(if (message?.isSeen == true) "标为未读" else "标为已读") }
                OutlinedButton(enabled = !busy, onClick = { showDeleteConfirm = true }) { Text("删除") }
            }

            body?.attachments?.takeIf { it.isNotEmpty() }?.let { atts ->
                LazyRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(atts) { att ->
                        TextButton(onClick = {
                            val m = message ?: return@TextButton
                            val f = folder ?: return@TextButton
                            val acc = account ?: return@TextButton
                            scope.launch {
                                runCatching {
                                    val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
                                    val dest = File(dir, att.fileName)
                                    container.sync.saveAttachment(acc, f, m, att.fileName, dest)
                                    val uri = FileProvider.getUriForFile(context, "com.wmail.app.files", dest)
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, att.contentType.substringBefore(';'))
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "打开附件"))
                                }.onFailure { error = "附件打开失败:${it.message}" }
                            }
                        }) { Text("📎 ${att.fileName}", fontSize = 12.sp, maxLines = 1) }
                    }
                }
            }

            error?.let {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            if (body == null && error == null) {
                Column(Modifier.padding(16.dp)) {
                    Text("加载正文…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            val currentBody = body
            if (currentBody != null) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = false
                            settings.loadsImagesAutomatically = true
                        }
                    },
                    update = { webView ->
                        val key = currentBody.html?.hashCode() ?: currentBody.text?.hashCode() ?: 0
                        if (webView.tag != key) {
                            webView.tag = key
                            if (currentBody.html != null) {
                                webView.loadDataWithBaseURL(null, currentBody.html, "text/html", "utf-8", null)
                            } else {
                                webView.loadData(currentBody.text ?: "", "text/plain", "utf-8")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    delete()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } },
            title = { Text("删除邮件") },
            text = { Text("把这封邮件移到垃圾桶?") },
        )
    }
}

private fun extractAddress(display: String): String {
    val start = display.indexOf('<')
    val end = display.indexOf('>')
    return if (start >= 0 && end > start) display.substring(start + 1, end).trim() else display.trim()
}
