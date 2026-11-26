package com.stmobile.feature.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stmobile.core.model.EnginePhase
import com.stmobile.core.model.EngineStatus
import com.stmobile.ui.theme.Ember
import com.stmobile.ui.theme.Gold
import com.stmobile.ui.theme.Moss
import com.stmobile.ui.theme.ParchmentDim

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugConsoleScreen(
    state: DebugState,
    onUrlChange: (String) -> Unit,
    onHeadersChange: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRequestCharacters: () -> Unit,
    onClearLog: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Engine Lab", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = state.status.phase.name.lowercase().replace('_', ' '),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor(state.status),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRequestCharacters) {
                        Icon(Icons.Default.Refresh, contentDescription = "Resync characters")
                    }
                    IconButton(onClick = onClearLog) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear log")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = state.url,
                onValueChange = onUrlChange,
                label = { Text("SillyTavern URL") },
                placeholder = { Text("http://192.168.1.10:8000") },
                singleLine = true,
                enabled = !state.connected,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.headersText,
                onValueChange = onHeadersChange,
                label = { Text("Custom headers (Key: Value)") },
                placeholder = { Text("CF-Access-Client-Id: ...") },
                enabled = !state.connected,
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.connected) {
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                } else {
                    Button(onClick = onConnect) { Text("Connect engine") }
                }
                StatusPill(state.status)
            }
            if (state.error != null) {
                Text(
                    text = state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.characters.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.characters, key = { it.id }) { character ->
                        AssistChip(
                            onClick = {},
                            label = { Text(character.name, maxLines = 1) }
                        )
                    }
                }
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    Text(
                        "LIVE STREAM",
                        style = MaterialTheme.typography.labelSmall,
                        color = ParchmentDim
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = state.streamed.ifEmpty {
                                "Connect to a SillyTavern server, then send a message.\n\nStreaming tokens received through the hidden engine will appear here."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Serif,
                            color = if (state.streamed.isEmpty()) ParchmentDim
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text("Test message") },
                    maxLines = 3,
                    modifier = Modifier.weight(1f)
                )
                if (state.generating) {
                    FilledIconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop generation")
                    }
                } else {
                    FilledIconButton(onClick = onSend) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Text(
                    text = state.logs.takeLast(40).joinToString("\n").ifEmpty { "No events yet." },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = ParchmentDim
                )
            }
        }
    }
}

@Composable
private fun StatusPill(status: EngineStatus) {
    Surface(
        color = statusColor(status).copy(alpha = 0.14f),
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .then(Modifier)
            ) {
                Surface(color = statusColor(status), modifier = Modifier.fillMaxSize()) {}
            }
            Text(
                text = status.phase.name.lowercase().replace('_', ' '),
                style = MaterialTheme.typography.labelLarge,
                color = statusColor(status)
            )
        }
    }
}

private fun statusColor(status: EngineStatus): Color = when (status.phase) {
    EnginePhase.READY -> Moss
    EnginePhase.ERROR -> Ember
    EnginePhase.LOADING_ENGINE, EnginePhase.STARTING_PROXY -> Gold
    EnginePhase.IDLE -> ParchmentDim
}
