package com.stmobile.feature.server

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stmobile.BuildConfig
import com.stmobile.core.model.EndpointHealth
import com.stmobile.core.model.EnginePhase
import com.stmobile.core.model.ServerProfile
import com.stmobile.core.model.ThemeMode
import com.stmobile.core.update.UpdateState
import com.stmobile.feature.common.EngineDot
import com.stmobile.feature.common.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    onOpenDebug: () -> Unit,
    onOpenExtensions: () -> Unit,
    viewModel: ServerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var deleteTarget by remember { mutableStateOf<ServerProfile?>(null) }

    val caPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importCa) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.clearMessage()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("连接") },
                actions = {
                    IconButton(onClick = onOpenExtensions) {
                        Icon(Icons.Default.Extension, contentDescription = "第三方扩展")
                    }
                    IconButton(onClick = onOpenDebug) {
                        Icon(Icons.Default.BugReport, contentDescription = "Engine Lab")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::startNewProfile) {
                Icon(Icons.Default.Add, contentDescription = "添加服务器")
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            EngineStatusCard(
                state = state,
                onDisconnect = viewModel::disconnect,
                onConnectActive = {
                    state.activeProfileId?.let(viewModel::connectTo)
                }
            )

            SectionTitle("服务器")
            if (state.profiles.isEmpty()) {
                Text(
                    text = "还没有服务器配置。点击右下角 + 添加 SillyTavern 地址。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.profiles.forEach { profile ->
                ProfileCard(
                    profile = profile,
                    active = profile.id == state.activeProfileId,
                    busy = state.busy,
                    ready = state.status.phase == EnginePhase.READY,
                    onConnect = { viewModel.connectTo(profile.id) },
                    onEdit = { viewModel.editProfile(profile.id) },
                    onDelete = { deleteTarget = profile }
                )
            }

            SectionTitle("外观")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.values().forEach { mode ->
                    val label = when (mode) {
                        ThemeMode.SYSTEM -> "跟随系统"
                        ThemeMode.DARK -> "深色"
                        ThemeMode.LIGHT -> "浅色"
                    }
                    FilterChip(
                        selected = state.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(label) }
                    )
                }
            }

            SectionTitle("缓存")
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("静态资源缓存", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "已占用 ${formatBytes(state.cacheBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = viewModel::refreshCacheSize) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新缓存大小")
                    }
                    TextButton(onClick = viewModel::clearCache) { Text("清除") }
                }
            }

            SectionTitle("关于")
            AboutCard(
                state = state,
                onCheckForUpdate = viewModel::checkForUpdate,
                onOpenUpdateDialog = viewModel::openUpdateDialog,
                onInstallUpdate = viewModel::installUpdate,
                onRetryUpdateDownload = viewModel::retryUpdateDownload,
                onOpenReleasePage = viewModel::openUpdateReleasePage,
                onAutoUpdateCheckChange = viewModel::setAutoUpdateCheck
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Text(
                text = "Tavernite ${BuildConfig.VERSION_NAME} · 本客户端不修改服务端 SillyTavern",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(20.dp))
        }
    }

    state.draft?.let { draft ->
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissDraft,
            sheetState = sheetState
        ) {
            ProfileEditor(
                draft = draft,
                health = state.health,
                probing = state.probing,
                busy = state.busy,
                onDraftChange = viewModel::updateDraft,
                onProbe = viewModel::probeDraft,
                onSave = { viewModel.saveDraft(connect = false) },
                onSaveAndConnect = { viewModel.saveDraft(connect = true) },
                onImportCa = {
                    caPicker.launch(arrayOf("application/x-pem-file", "text/plain", "application/octet-stream"))
                },
                onDismiss = viewModel::dismissDraft
            )
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除服务器") },
            text = { Text("确定删除「${target.name}」？已加密保存的凭据会一并清除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProfile(target.id)
                    deleteTarget = null
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun EngineStatusCard(
    state: ServerUiState,
    onDisconnect: () -> Unit,
    onConnectActive: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EngineDot(state.status)
                Spacer(Modifier.width(10.dp))
                Text("ST-Engine", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    text = phaseLabel(state.status.phase),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = state.localOrigin ?: "回环代理未启动",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.status.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            state.status.serverVersion?.let { version ->
                Text(
                    text = "ST $version · ${state.status.currentUser ?: ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.status.phase == EnginePhase.READY) {
                    OutlinedButton(onClick = onDisconnect) { Text("断开连接") }
                } else if (state.activeProfileId != null) {
                    Button(onClick = onConnectActive, enabled = !state.busy) {
                        Text("连接当前配置")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: ServerProfile,
    active: Boolean,
    busy: Boolean,
    ready: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (active) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "当前",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            Text(
                text = profile.endpoints.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect, enabled = !busy && !(active && ready)) {
                    Text(if (active && ready) "已连接" else "连接")
                }
                OutlinedButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("删除")
                }
            }
        }
    }
}

@Composable
private fun ProfileEditor(
    draft: ProfileDraft,
    health: List<EndpointHealth>,
    probing: Boolean,
    busy: Boolean,
    onDraftChange: (ProfileDraft) -> Unit,
    onProbe: () -> Unit,
    onSave: () -> Unit,
    onSaveAndConnect: () -> Unit,
    onImportCa: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .navigationBarsPadding()
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("服务器配置", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            label = { Text("名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = draft.endpointsText,
            onValueChange = { onDraftChange(draft.copy(endpointsText = it)) },
            label = { Text("服务器地址（每行一个，按优先级）") },
            placeholder = { Text("https://tavern.example.com") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = draft.headersText,
            onValueChange = { onDraftChange(draft.copy(headersText = it)) },
            label = { Text("自定义 Header（Key: Value）") },
            placeholder = { Text("CF-Access-Client-Id: ...") },
            minLines = 1,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.basicAuthUser,
                onValueChange = { onDraftChange(draft.copy(basicAuthUser = it)) },
                label = { Text("Basic Auth 用户名") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = draft.basicAuthPassword,
                onValueChange = { onDraftChange(draft.copy(basicAuthPassword = it)) },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = draft.caPem,
            onValueChange = { onDraftChange(draft.copy(caPem = it)) },
            label = { Text("自签名 CA（PEM）") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(onClick = onImportCa) { Text("从文件导入 PEM / CRT") }
        SwitchRow(
            title = "跳过 TLS 证书校验",
            subtitle = "仅用于自签名且无法导入 CA 的服务器",
            checked = draft.allowInsecureTls,
            onCheckedChange = { onDraftChange(draft.copy(allowInsecureTls = it)) }
        )
        SwitchRow(
            title = "静态资源磁盘缓存",
            subtitle = "缓存 js / css / 字体与头像，使用 ETag 重新校验",
            checked = draft.cacheEnabled,
            onCheckedChange = { onDraftChange(draft.copy(cacheEnabled = it)) }
        )

        if (probing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            OutlinedButton(onClick = onProbe, modifier = Modifier.fillMaxWidth()) {
                Text("探测链路延迟")
            }
        }
        health.forEach { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.url,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (item.reachable) "${item.latencyMs ?: 0} ms" else item.error ?: "不可达",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.reachable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("取消")
            }
            Button(onClick = onSaveAndConnect, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("保存并连接")
            }
        }
        TextButton(onClick = onSave, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("仅保存")
        }
        Spacer(Modifier.size(16.dp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun AboutCard(
    state: ServerUiState,
    onCheckForUpdate: () -> Unit,
    onOpenUpdateDialog: () -> Unit,
    onInstallUpdate: () -> Unit,
    onRetryUpdateDownload: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onAutoUpdateCheckChange: (Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Tavernite ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyLarge
            )

            when (val update = state.updateState) {
                UpdateState.Idle, UpdateState.UpToDate ->
                    CheckForUpdateRow(onCheckForUpdate = onCheckForUpdate)

                UpdateState.Checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "正在检查更新…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = {}, enabled = false) { Text("检查更新") }
                    }
                }

                is UpdateState.Available -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "新版本 v${update.info.versionName} 可用",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onOpenUpdateDialog) { Text("查看") }
                    }
                }

                is UpdateState.Downloading -> {
                    val percent = if (update.total > 0) {
                        (update.bytes * 100 / update.total).toInt().coerceIn(0, 100)
                    } else {
                        null
                    }
                    if (percent != null) {
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(
                        text = if (percent != null) "下载中 $percent%" else "下载中",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is UpdateState.ReadyToInstall -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "v${update.info.versionName} 已下载",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = onInstallUpdate) { Text("安装") }
                    }
                }

                is UpdateState.Failed -> {
                    if (update.info == null) {
                        // A check failure is reported via the Snackbar only; keep
                        // the manual check reachable if the shared state sticks.
                        CheckForUpdateRow(onCheckForUpdate = onCheckForUpdate)
                    } else {
                        Text(
                            text = update.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = onRetryUpdateDownload) { Text("重试") }
                    }
                }
            }

            if (BuildConfig.DEBUG) {
                Text(
                    text = "调试构建不参与自动更新",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                SwitchRow(
                    title = "启动时自动检查更新",
                    subtitle = "每天最多检查一次",
                    checked = state.autoUpdateCheck,
                    onCheckedChange = onAutoUpdateCheckChange
                )
            }

            TextButton(onClick = onOpenReleasePage) { Text("在 GitHub 查看 Releases") }
        }
    }
}

@Composable
private fun CheckForUpdateRow(onCheckForUpdate: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "检查是否有新的 Tavernite 版本。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onCheckForUpdate) { Text("检查更新") }
    }
}

private fun phaseLabel(phase: EnginePhase): String = when (phase) {
    EnginePhase.IDLE -> "未连接"
    EnginePhase.STARTING_PROXY -> "启动代理"
    EnginePhase.LOADING_ENGINE -> "加载内核"
    EnginePhase.READY -> "就绪"
    EnginePhase.ERROR -> "错误"
}
