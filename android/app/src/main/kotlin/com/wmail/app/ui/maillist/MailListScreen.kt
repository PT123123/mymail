package com.wmail.app.ui.maillist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wmail.app.AppContainer
import com.wmail.app.data.db.MessageEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailListScreen(
    container: AppContainer,
    accountId: String,
    folderId: Long,
    onBack: () -> Unit,
    onOpenMessage: (Long) -> Unit,
) {
    val messages by container.db.messageDao().listFor(folderId).collectAsState(initial = emptyList())
    val folders by container.db.folderDao().listFor(accountId).collectAsState(initial = emptyList())
    val folderName = folders.firstOrNull { it.id == folderId }?.displayName ?: "邮件"
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            status = "同步…"
            runCatching {
                val account = container.db.accountDao().byId(accountId) ?: return@launch
                val folder = container.db.folderDao().byId(folderId) ?: return@launch
                container.sync.openFolder(account, folder)
            }
                .onFailure { status = "同步失败:${it.message}" }
                .onSuccess { status = "" }
        }
    }

    LaunchedEffect(folderId) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(folderName) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", fontSize = 20.sp) }
                },
                actions = {
                    IconButton(onClick = { refresh() }) { Text("⟳", fontSize = 20.sp) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (status.isNotEmpty()) {
                Text(
                    status,
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (messages.isEmpty() && status.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("此文件夹没有邮件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn {
                items(messages, key = { it.id }) { message ->
                    MessageRow(message) { onOpenMessage(message.uid) }
                    HorizontalDivider()
                }
            }
        }
    }
}

private val DateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

@Composable
private fun MessageRow(message: MessageEntity, onClick: () -> Unit) {
    ListItem(
        leadingContent = {
            if (!message.isSeen) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.subject.ifBlank { "(无主题)" },
                    fontWeight = if (message.isSeen) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(DateFmt.format(Date(message.dateUnix * 1000)), fontSize = 11.sp, color = Color.Gray)
            }
        },
        supportingContent = {
            Column {
                Text(message.fromAddr, fontSize = 12.sp, maxLines = 1)
                if (message.snippet.isNotBlank()) {
                    Text(message.snippet, fontSize = 12.sp, maxLines = 1, color = Color.Gray)
                }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (message.isFlagged) Text("★", color = Color(0xFFFFA000))
                if (message.hasAttachments) Text("📎", fontSize = 11.sp)
            }
        },
        modifier = Modifier.clickable { onClick() },
    )
}
