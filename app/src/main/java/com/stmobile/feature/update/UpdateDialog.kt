package com.stmobile.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stmobile.core.update.UpdateDialogPolicy
import com.stmobile.core.update.UpdateState
import com.stmobile.feature.common.formatBytes

/**
 * Root-level update dialog, mounted in `TaverniteApp` so it is visible on every
 * tab. Visibility is decided by [UpdateDialogPolicy]; callers only supply state
 * and action callbacks.
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    dismissedVersion: String?,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onSkip: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenReleasePage: () -> Unit
) {
    if (!UpdateDialogPolicy.shouldShow(state, dismissedVersion)) return

    when (state) {
        is UpdateState.Available -> AvailableDialog(
            state = state,
            onDismiss = onDismiss,
            onDownload = onDownload,
            onSkip = onSkip,
            onOpenReleasePage = onOpenReleasePage
        )

        is UpdateState.Downloading -> DownloadingDialog(
            state = state,
            onCancelDownload = onCancelDownload
        )

        is UpdateState.ReadyToInstall -> ReadyToInstallDialog(
            state = state,
            onDismiss = onDismiss,
            onInstall = onInstall
        )

        is UpdateState.Failed -> {
            // The policy filters out failures without an update context; the
            // guard keeps this branch safe on its own.
            if (state.info == null) return
            FailedDialog(
                message = state.message,
                onDismiss = onDismiss,
                onRetry = onRetry,
                onOpenReleasePage = onOpenReleasePage
            )
        }

        UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate -> Unit
    }
}

@Composable
private fun AvailableDialog(
    state: UpdateState.Available,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onSkip: (String) -> Unit,
    onOpenReleasePage: () -> Unit
) {
    val info = state.info
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 v${info.versionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = info.notes.ifBlank { "该版本没有附带更新说明。" },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                )
                Text(
                    text = "安装包 ${formatBytes(info.apkSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column {
                    TextButton(
                        onClick = { onSkip(info.versionName) },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                    ) {
                        Text("跳过此版本")
                    }
                    TextButton(
                        onClick = onOpenReleasePage,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                    ) {
                        Text("在 GitHub 查看")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload) { Text("下载并安装") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后") }
        }
    )
}

@Composable
private fun DownloadingDialog(
    state: UpdateState.Downloading,
    onCancelDownload: () -> Unit
) {
    val percent = if (state.total > 0) {
        (state.bytes * 100 / state.total).toInt().coerceIn(0, 100)
    } else {
        null
    }
    AlertDialog(
        // A running download cannot be dismissed by tapping outside or back;
        // the only exit is the explicit cancel action.
        onDismissRequest = {},
        title = { Text("正在下载 v${state.info.versionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (percent != null) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text(
                    text = if (percent != null) {
                        "已下载 $percent%（${formatBytes(state.bytes)} / ${formatBytes(state.total)}）"
                    } else {
                        "已下载 ${formatBytes(state.bytes)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancelDownload) { Text("取消") }
        }
    )
}

@Composable
private fun ReadyToInstallDialog(
    state: UpdateState.ReadyToInstall,
    onDismiss: () -> Unit,
    onInstall: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("v${state.info.versionName} 已下载") },
        text = {
            Text(
                text = if (state.needsPermission) {
                    "需要允许 Tavernite 安装未知应用"
                } else {
                    "点击安装完成升级"
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onInstall) {
                Text(if (state.needsPermission) "去允许安装" else "安装")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后") }
        }
    )
}

@Composable
private fun FailedDialog(
    message: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenReleasePage: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新失败") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(
                    onClick = onOpenReleasePage,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                ) {
                    Text("在 GitHub 打开")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRetry) { Text("重试") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
