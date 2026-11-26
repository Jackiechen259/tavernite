package com.stmobile.feature.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stmobile.core.model.Character
import com.stmobile.core.model.EngineStatus
import com.stmobile.core.proxy.ProxyConfig
import com.stmobile.core.proxy.ProxyEvent
import com.stmobile.core.stengine.EngineCommand
import com.stmobile.core.stengine.EngineController
import com.stmobile.core.stengine.EngineEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

data class DebugState(
    val url: String = "http://10.0.2.2:8000",
    val headersText: String = "",
    val status: EngineStatus = EngineStatus(),
    val connected: Boolean = false,
    val characters: List<Character> = emptyList(),
    val logs: List<String> = emptyList(),
    val draft: String = "",
    val streamed: String = "",
    val generating: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val controller: EngineController
) : ViewModel() {

    private val _state = MutableStateFlow(DebugState())
    val state: StateFlow<DebugState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            controller.status.collect { status ->
                _state.update { it.copy(status = status) }
            }
        }
        viewModelScope.launch {
            controller.events.collect(::onEngineEvent)
        }
        viewModelScope.launch {
            controller.proxyEvents.collect { event -> log(describe(event)) }
        }
    }

    fun onUrlChanged(value: String) = _state.update { it.copy(url = value) }

    fun onHeadersChanged(value: String) = _state.update { it.copy(headersText = value) }

    fun onDraftChanged(value: String) = _state.update { it.copy(draft = value) }

    fun connect() {
        val current = _state.value
        val headers = parseHeaders(current.headersText)
        if (headers == null) {
            _state.update { it.copy(error = "Headers must be 'Key: Value' per line") }
            return
        }
        if (current.url.isBlank()) {
            _state.update { it.copy(error = "Server URL is required") }
            return
        }
        log("Connecting to ${current.url}")
        viewModelScope.launch {
            runCatching { controller.connect(ProxyConfig(endpoints = listOf(current.url), headers = headers)) }
                .onSuccess { port ->
                    _state.update { it.copy(connected = true, error = null) }
                    log("Loopback proxy listening on 127.0.0.1:$port")
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "Connect failed") }
                    log("Connect failed: ${error.message}")
                }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            controller.disconnect()
            _state.update { it.copy(connected = false, generating = false) }
            log("Disconnected")
        }
    }

    fun send() {
        val text = _state.value.draft.trim()
        if (text.isEmpty()) return
        controller.send(EngineCommand.SEND_MESSAGE, buildJsonObject { put("text", text) })
        _state.update { it.copy(draft = "", streamed = "") }
        log(">> $text")
    }

    fun stopGeneration() {
        controller.send(EngineCommand.STOP_GENERATION)
        log("Stop requested")
    }

    fun requestCharacters() {
        controller.send(EngineCommand.REQUEST_CHARACTERS)
        log("Requested character list")
    }

    fun clearLog() = _state.update { it.copy(logs = emptyList()) }

    private fun onEngineEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.Ready -> {
                _state.update { it.copy(connected = true, error = null) }
                log("ENGINE_READY version=${event.version} user=${event.currentUser}")
            }
            is EngineEvent.CharactersSync -> {
                _state.update { it.copy(characters = event.characters) }
                log("Characters synced: ${event.characters.size}")
            }
            is EngineEvent.ChatLoaded -> {
                val lastAssistant = event.messages.lastOrNull { it.role != com.stmobile.core.model.MessageRole.USER }
                _state.update {
                    it.copy(streamed = lastAssistant?.displayText ?: it.streamed)
                }
                log("Chat loaded: ${event.chatId} (${event.messages.size} messages)")
            }
            is EngineEvent.StreamToken -> {
                _state.update { it.copy(streamed = event.displayText) }
            }
            is EngineEvent.MessageUpdated -> {
                log("Message ${event.messageId} updated (${event.message.displayText.length} chars)")
            }
            is EngineEvent.GenerationState -> {
                _state.update { it.copy(generating = event.isGenerating) }
                log(if (event.isGenerating) "Generation started" else "Generation ended (${event.reason})")
            }
            is EngineEvent.PresetsSync -> {
                log("Presets synchronized (${event.presets.size} presets, active=${event.activePreset})")
            }
            is EngineEvent.SlashResult -> log("Slash result ok=${event.ok} output=${event.output ?: "-"}")
            is EngineEvent.EngineError -> {
                _state.update { it.copy(error = event.message) }
                log("ENGINE ERROR: ${event.message}")
            }
            is EngineEvent.Unknown -> log("Unknown event: ${event.type}")
        }
    }

    private fun describe(event: ProxyEvent): String = when (event) {
        is ProxyEvent.StreamStarted -> "proxy: stream started ${event.path}"
        is ProxyEvent.StreamEnded -> "proxy: stream ended ${event.path} (${event.bytes} bytes)"
        is ProxyEvent.UpstreamError -> "proxy: upstream error ${event.path}: ${event.message}"
        is ProxyEvent.EndpointSwitch -> "proxy: switched endpoint ${event.from} -> ${event.url}"
        is ProxyEvent.CacheHit -> "proxy: cache hit ${event.path}"
    }

    private fun log(line: String) {
        _state.update { current ->
            val next = (current.logs + line).takeLast(300)
            current.copy(logs = next)
        }
    }

    private fun parseHeaders(raw: String): Map<String, String>? {
        if (raw.isBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val index = trimmed.indexOf(':')
            if (index <= 0) return null
            val name = trimmed.substring(0, index).trim()
            val value = trimmed.substring(index + 1).trim()
            if (name.isEmpty()) return null
            result[name] = value
        }
        return result
    }

    override fun onCleared() {
        // Keep the engine alive across navigation; nothing to release here.
        super.onCleared()
    }
}
