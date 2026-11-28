package com.stmobile.feature.server

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stmobile.core.model.EndpointHealth
import com.stmobile.core.model.EngineStatus
import com.stmobile.core.model.ServerProfile
import com.stmobile.core.model.ThemeMode
import com.stmobile.core.proxy.EndpointRouter
import com.stmobile.core.proxy.UpstreamClient
import com.stmobile.core.repository.ServerRepository
import com.stmobile.core.settings.SettingsStore
import com.stmobile.core.stengine.EngineController
import com.stmobile.core.update.UpdateCheckOutcome
import com.stmobile.core.update.UpdateManager
import com.stmobile.core.update.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

data class ProfileDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val endpointsText: String = "http://10.0.2.2:8000",
    val headersText: String = "",
    val basicAuthUser: String = "",
    val basicAuthPassword: String = "",
    val caPem: String = "",
    val allowInsecureTls: Boolean = false,
    val cacheEnabled: Boolean = true
)

data class ServerUiState(
    val profiles: List<ServerProfile> = emptyList(),
    val activeProfileId: String? = null,
    val draft: ProfileDraft? = null,
    val health: List<EndpointHealth> = emptyList(),
    val probing: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val status: EngineStatus = EngineStatus(),
    val localOrigin: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val cacheBytes: Long = 0L,
    val updateState: UpdateState = UpdateState.Idle,
    val autoUpdateCheck: Boolean = true
)

@HiltViewModel
class ServerViewModel @Inject constructor(
    private val servers: ServerRepository,
    private val controller: EngineController,
    private val settings: SettingsStore,
    private val updates: UpdateManager,
    private val upstream: UpstreamClient,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(ServerUiState())
    val state: StateFlow<ServerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            servers.observeProfiles().collect { list ->
                _state.update { it.copy(profiles = list) }
            }
        }
        viewModelScope.launch {
            settings.activeProfileId.collect { id ->
                _state.update { it.copy(activeProfileId = id) }
            }
        }
        viewModelScope.launch {
            controller.status.collect { status ->
                _state.update { it.copy(status = status) }
            }
        }
        viewModelScope.launch {
            controller.localOrigin.collect { origin ->
                _state.update { it.copy(localOrigin = origin) }
            }
        }
        viewModelScope.launch {
            settings.themeMode.collect { mode ->
                _state.update { it.copy(themeMode = mode) }
            }
        }
        viewModelScope.launch {
            updates.state.collect { updateState ->
                _state.update { it.copy(updateState = updateState) }
            }
        }
        viewModelScope.launch {
            settings.autoUpdateCheck.collect { enabled ->
                _state.update { it.copy(autoUpdateCheck = enabled) }
            }
        }
        refreshCacheSize()
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun startNewProfile() {
        _state.update {
            it.copy(draft = ProfileDraft(), health = emptyList())
        }
    }

    fun editProfile(profileId: String) {
        viewModelScope.launch {
            val profile = servers.loadFull(profileId) ?: return@launch
            _state.update {
                it.copy(
                    draft = ProfileDraft(
                        id = profile.id,
                        name = profile.name,
                        endpointsText = profile.endpoints.joinToString("\n"),
                        headersText = profile.customHeaders.entries.joinToString("\n") {
                            "${it.key}: ${it.value}"
                        },
                        basicAuthUser = profile.basicAuthUser.orEmpty(),
                        basicAuthPassword = profile.basicAuthPassword.orEmpty(),
                        caPem = profile.caPem.orEmpty(),
                        allowInsecureTls = profile.allowInsecureTls,
                        cacheEnabled = profile.cacheEnabled
                    ),
                    health = emptyList()
                )
            }
        }
    }

    fun dismissDraft() {
        _state.update { it.copy(draft = null, health = emptyList()) }
    }

    fun updateDraft(draft: ProfileDraft) {
        _state.update { it.copy(draft = draft) }
    }

    fun importCa(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()
                        ?.use { it.readText() }
                }.getOrNull()
            }
            val draft = _state.value.draft ?: return@launch
            if (text.isNullOrBlank()) {
                _state.update { it.copy(message = "无法读取证书文件") }
            } else {
                _state.update { it.copy(draft = draft.copy(caPem = text)) }
            }
        }
    }

    fun saveDraft(connect: Boolean) {
        val draft = _state.value.draft ?: return
        val endpoints = parseEndpoints(draft.endpointsText)
        if (endpoints.isEmpty()) {
            _state.update { it.copy(message = "至少填写一个服务器地址") }
            return
        }
        val headers = parseHeaders(draft.headersText)
        if (headers == null) {
            _state.update { it.copy(message = "自定义 Header 必须每行 Key: Value") }
            return
        }
        val profile = ServerProfile(
            id = draft.id,
            name = draft.name.ifBlank { deriveName(endpoints.first()) },
            endpoints = endpoints,
            customHeaders = headers,
            basicAuthUser = draft.basicAuthUser.takeIf { it.isNotBlank() },
            basicAuthPassword = draft.basicAuthPassword.takeIf { it.isNotBlank() },
            caPem = draft.caPem.takeIf { it.isNotBlank() },
            allowInsecureTls = draft.allowInsecureTls,
            cacheEnabled = draft.cacheEnabled
        )
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            servers.save(profile)
            servers.setActive(profile.id)
            _state.update { it.copy(draft = null, health = emptyList(), busy = false) }
            if (connect) connectTo(profile.id, profile)
        }
    }

    fun connectTo(profileId: String) {
        viewModelScope.launch {
            val profile = servers.loadFull(profileId)
            if (profile == null) {
                _state.update { it.copy(message = "服务器配置不存在") }
                return@launch
            }
            connectTo(profileId, profile)
        }
    }

    private suspend fun connectTo(profileId: String, profile: ServerProfile) {
        _state.update { it.copy(busy = true) }
        runCatching { controller.connect(servers.toProxyConfig(profile)) }
            .onSuccess {
                servers.setActive(profileId)
                _state.update { it.copy(message = null) }
            }
            .onFailure { error ->
                _state.update { it.copy(message = error.message ?: "连接失败") }
            }
        _state.update { it.copy(busy = false) }
    }

    fun disconnect() {
        viewModelScope.launch {
            controller.disconnect()
            servers.setActive(null)
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            servers.delete(profileId)
            _state.update { it.copy(message = "已删除服务器配置") }
        }
    }

    fun probeDraft() {
        val draft = _state.value.draft ?: return
        val endpoints = parseEndpoints(draft.endpointsText)
        if (endpoints.isEmpty()) {
            _state.update { it.copy(message = "至少填写一个服务器地址") }
            return
        }
        val headers = parseHeaders(draft.headersText)
        if (headers == null) {
            _state.update { it.copy(message = "自定义 Header 必须每行 Key: Value") }
            return
        }
        val config = ServerProfile(
            id = draft.id,
            name = draft.name,
            endpoints = endpoints,
            customHeaders = headers,
            basicAuthUser = draft.basicAuthUser.takeIf { it.isNotBlank() },
            basicAuthPassword = draft.basicAuthPassword.takeIf { it.isNotBlank() },
            caPem = draft.caPem.takeIf { it.isNotBlank() },
            allowInsecureTls = draft.allowInsecureTls,
            cacheEnabled = draft.cacheEnabled
        ).let(servers::toProxyConfig)

        viewModelScope.launch {
            _state.update { it.copy(probing = true, health = emptyList()) }
            val results = upstream.probe(endpoints, config)
            _state.update { it.copy(probing = false, health = results) }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            when (val outcome = updates.checkManually()) {
                is UpdateCheckOutcome.UpToDate ->
                    _state.update { it.copy(message = "已是最新版本") }
                is UpdateCheckOutcome.Failure ->
                    _state.update { it.copy(message = outcome.message) }
                is UpdateCheckOutcome.Found -> Unit
            }
        }
    }

    fun setAutoUpdateCheck(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoUpdateCheck(enabled) }
    }

    fun openUpdateDialog() = updates.showDialog()
    fun downloadUpdate() = updates.download()
    fun retryUpdateDownload() = updates.download()
    fun installUpdate() = updates.install()
    fun openUpdateReleasePage() = updates.openReleasePage()

    fun clearCache() {
        controller.clearCache()
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = runCatching { controller.cacheBytes() }.getOrDefault(0L)
            _state.update { it.copy(cacheBytes = bytes) }
        }
    }

    private fun parseEndpoints(text: String): List<String> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { EndpointRouter.normalize(it) }
        .filter { it.isNotEmpty() }
        .distinct()

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

    private fun deriveName(endpoint: String): String =
        endpoint.removePrefix("https://").removePrefix("http://").trimEnd('/')
}
