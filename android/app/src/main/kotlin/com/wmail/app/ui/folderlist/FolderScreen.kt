package com.wmail.app.ui.folderlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wmail.app.AppContainer
import com.wmail.app.data.db.AccountEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    container: AppContainer,
    accountId: String,
    onBack: () -> Unit,
    onOpenFolder: (Long) -> Unit,
) {
    val folders by container.db.folderDao().listFor(accountId).collectAsState(initial = emptyList())
    var account by remember { mutableStateOf<AccountEntity?>(null) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(accountId) {
        val acc = container.db.accountDao().byId(accountId) ?: return@LaunchedEffect
        account = acc
        status = "连接服务器…"
        runCatching { container.sync.refreshFolders(acc) }
            .onSuccess { status = "" }
            .onFailure { status = "连接失败:${it.message}" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(account?.displayName ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", fontSize = 20.sp) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (status.isNotEmpty()) {
                Text(
                    status,
                    Modifier.padding(12.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn {
                items(folders, key = { it.id }) { folder ->
                    ListItem(
                        headlineContent = { Text(folder.displayName) },
                        trailingContent = {
                            if (folder.unreadCount > 0) {
                                Badge { Text("${folder.unreadCount}") }
                            }
                        },
                        modifier = Modifier.clickable { onOpenFolder(folder.id) },
                    )
                }
            }
        }
    }
}
