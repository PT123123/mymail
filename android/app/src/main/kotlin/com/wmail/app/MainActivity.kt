package com.wmail.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wmail.app.sync.IdleService
import com.wmail.app.sync.SyncWorker
import com.wmail.app.ui.accounts.AccountsScreen
import com.wmail.app.ui.compose.ComposeScreen
import com.wmail.app.ui.detail.DetailScreen
import com.wmail.app.ui.folderlist.FolderScreen
import com.wmail.app.ui.maillist.MailListScreen
import com.wmail.app.ui.theme.WMailTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (needsNotificationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        SyncWorker.schedule(this)
        IdleService.start(this)

        setContent {
            WMailTheme { AppNav() }
        }
    }

    private fun needsNotificationPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
}

@Composable
fun AppNav() {
    val container = (LocalContextApplication() as WMailApp).container
    val nav = rememberNavController()

    NavHost(nav, startDestination = "accounts") {
        composable("accounts") {
            AccountsScreen(
                container = container,
                onOpenAccount = { nav.navigate("folders/$it") },
            )
        }
        composable(
            "folders/{accountId}",
            arguments = listOf(navArgument("accountId") { type = NavType.StringType }),
        ) { entry ->
            val accountId = entry.arguments?.getString("accountId") ?: return@composable
            FolderScreen(
                container = container,
                accountId = accountId,
                onBack = { nav.popBackStack() },
                onOpenFolder = { nav.navigate("list/$accountId/$it") },
            )
        }
        composable(
            "list/{accountId}/{folderId}",
            arguments = listOf(
                navArgument("accountId") { type = NavType.StringType },
                navArgument("folderId") { type = NavType.LongType },
            ),
        ) { entry ->
            val accountId = entry.arguments?.getString("accountId") ?: return@composable
            val folderId = entry.arguments?.getLong("folderId") ?: return@composable
            MailListScreen(
                container = container,
                accountId = accountId,
                folderId = folderId,
                onBack = { nav.popBackStack() },
                onOpenMessage = { nav.navigate("detail/$accountId/$folderId/$it") },
            )
        }
        composable(
            "detail/{accountId}/{folderId}/{uid}",
            arguments = listOf(
                navArgument("accountId") { type = NavType.StringType },
                navArgument("folderId") { type = NavType.LongType },
                navArgument("uid") { type = NavType.LongType },
            ),
        ) { entry ->
            val accountId = entry.arguments?.getString("accountId") ?: return@composable
            val folderId = entry.arguments?.getLong("folderId") ?: return@composable
            val uid = entry.arguments?.getLong("uid") ?: return@composable
            DetailScreen(
                container = container,
                accountId = accountId,
                folderId = folderId,
                uid = uid,
                onBack = { nav.popBackStack() },
                onReply = { nav.navigate("compose/$accountId") },
            )
        }
        composable(
            "compose/{accountId}",
            arguments = listOf(navArgument("accountId") { type = NavType.StringType }),
        ) { entry ->
            val accountId = entry.arguments?.getString("accountId") ?: return@composable
            ComposeScreen(
                container = container,
                accountId = accountId,
                onDone = { nav.popBackStack() },
            )
        }
    }
}

@Composable
private fun LocalContextApplication(): android.app.Application =
    androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
