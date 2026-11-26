package com.stmobile

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stmobile.core.stengine.EngineController
import com.stmobile.feature.server.DebugConsoleScreen
import com.stmobile.feature.server.DebugViewModel
import com.stmobile.ui.theme.TaverniteTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var engineController: EngineController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TaverniteTheme {
                Box(Modifier.fillMaxSize()) {
                    val viewModel: DebugViewModel = hiltViewModel()
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
                        onClearLog = viewModel::clearLog
                    )
                    EngineHost(engineController)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            // The engine is application-scoped and survives Activity recreation.
        }
    }
}

@Composable
private fun EngineHost(controller: EngineController) {
    AndroidView(
        factory = { context ->
            FrameLayout(context).also { controller.attach(it) }
        },
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
    )
}
