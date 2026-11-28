package com.stmobile

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stmobile.core.model.EnginePhase
import com.stmobile.feature.characters.CharactersScreen
import com.stmobile.feature.chatroom.ChatRoomScreen
import com.stmobile.feature.chats.ChatsScreen
import com.stmobile.feature.server.DebugConsoleScreen
import com.stmobile.feature.server.DebugViewModel
import com.stmobile.feature.server.ExtensionOverlay
import com.stmobile.feature.server.ServerScreen
import com.stmobile.feature.tuning.TuningScreen
import com.stmobile.feature.update.UpdateDialog

object Routes {
    const val CHATS = "chats"
    const val CHARACTERS = "characters"
    const val TUNING = "tuning"
    const val SERVER = "server"
    const val DEBUG = "debug"
    const val CHAT_ROOM = "chatroom?chatId={chatId}&characterId={characterId}"

    fun chatRoom(chatId: String, characterId: String): String =
        "chatroom?chatId=${Uri.encode(chatId)}&characterId=${Uri.encode(characterId)}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.CHATS, "对话", Icons.Default.Chat),
    Tab(Routes.CHARACTERS, "角色库", Icons.Default.People),
    Tab(Routes.TUNING, "调校", Icons.Default.Tune),
    Tab(Routes.SERVER, "连接", Icons.Default.Settings)
)

private val TOP_LEVEL = TABS.map { it.route }.toSet()

@Composable
fun TaverniteApp(
    appViewModel: AppViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val status by appViewModel.status.collectAsStateWithLifecycle()
    val message by appViewModel.message.collectAsStateWithLifecycle()
    val updateState by appViewModel.updateState.collectAsStateWithLifecycle()
    val updateDismissedVersion by appViewModel.updateDismissedVersion.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showExtensions by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        appViewModel.clearMessage()
    }

    LaunchedEffect(status.phase) {
        if (status.phase != EnginePhase.READY) showExtensions = false
    }

    val openServer: () -> Unit = {
        navController.navigate(Routes.SERVER) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val openExtensions: () -> Unit = {
        if (status.phase == EnginePhase.READY) showExtensions = true
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (route != null && route in TOP_LEVEL) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CHATS,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            composable(Routes.CHATS) {
                ChatsScreen(
                    onOpenChat = { session ->
                        navController.navigate(Routes.chatRoom(session.id, session.characterId))
                    },
                    onOpenServer = openServer
                )
            }
            composable(Routes.CHARACTERS) {
                CharactersScreen(
                    onOpenCharacter = { character ->
                        navController.navigate(Routes.chatRoom(character.id, character.id))
                    },
                    onOpenServer = openServer
                )
            }
            composable(Routes.TUNING) {
                TuningScreen(
                    onOpenExtensions = openExtensions,
                    onOpenServer = openServer
                )
            }
            composable(Routes.SERVER) {
                ServerScreen(
                    onOpenDebug = { navController.navigate(Routes.DEBUG) },
                    onOpenExtensions = openExtensions
                )
            }
            composable(Routes.DEBUG) {
                DebugRoute(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.CHAT_ROOM,
                arguments = listOf(
                    navArgument("chatId") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("characterId") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) {
                ChatRoomScreen(onBack = { navController.popBackStack() })
            }
        }
    }

    if (showExtensions) {
        ExtensionOverlay(
            viewModel = appViewModel,
            onDismiss = { showExtensions = false }
        )
    }

    UpdateDialog(
        state = updateState,
        dismissedVersion = updateDismissedVersion,
        onDismiss = appViewModel::dismissUpdateDialog,
        onDownload = appViewModel::downloadUpdate,
        onCancelDownload = appViewModel::cancelUpdateDownload,
        onInstall = appViewModel::installUpdate,
        onSkip = appViewModel::skipUpdateVersion,
        onRetry = appViewModel::downloadUpdate,
        onOpenReleasePage = appViewModel::openUpdateReleasePage
    )
}

@Composable
private fun DebugRoute(
    onBack: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DebugConsoleScreen(
        state = state,
        onUrlChange = viewModel::onUrlChanged,
        onHeadersChange = viewModel::onHeadersChanged,
        onDraftChange = viewModel::onDraftChanged,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onSend = viewModel::send,
        onStop = viewModel::stopGeneration,
        onRequestCharacters = viewModel::requestCharacters,
        onClearLog = viewModel::clearLog,
        onBack = onBack
    )
}
