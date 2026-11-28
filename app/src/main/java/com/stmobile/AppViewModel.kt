package com.stmobile

import android.content.Context
import android.view.ViewGroup
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stmobile.core.model.EngineStatus
import com.stmobile.core.model.EnginePhase
import com.stmobile.core.repository.EngineSyncRepository
import com.stmobile.core.repository.ServerRepository
import com.stmobile.core.settings.SettingsStore
import com.stmobile.core.stengine.EngineCommand
import com.stmobile.core.stengine.EngineController
import com.stmobile.core.update.UpdateManager
import com.stmobile.core.update.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val servers: ServerRepository,
    private val controller: EngineController,
    private val sync: EngineSyncRepository,
    private val settings: SettingsStore,
    private val updates: UpdateManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val status: StateFlow<EngineStatus> = controller.status
    val localOrigin: StateFlow<String?> = controller.localOrigin
    val generating: StateFlow<Boolean> = sync.generating

    val updateState: StateFlow<UpdateState> = updates.state
    val updateDismissedVersion: StateFlow<String?> = updates.dismissedVersion

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val connected: Boolean get() = status.value.phase == EnginePhase.READY

    init {
        sync.ensureStarted()
        viewModelScope.launch {
            val activeId = settings.activeProfileId.first() ?: servers.firstProfileId()
            if (activeId != null) connectTo(activeId)
        }
        viewModelScope.launch { updates.autoCheckIfDue() }
    }

    fun connectTo(profileId: String) {
        viewModelScope.launch {
            if (_busy.value) return@launch
            _busy.value = true
            val profile = servers.loadFull(profileId)
            if (profile == null) {
                _message.value = "Server profile not found"
                _busy.value = false
                return@launch
            }
            runCatching { controller.connect(servers.toProxyConfig(profile)) }
                .onSuccess {
                    servers.setActive(profileId)
                    _message.value = null
                }
                .onFailure { error ->
                    _message.value = error.message ?: "Unable to connect"
                }
            _busy.value = false
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            controller.disconnect()
            servers.setActive(null)
            _message.value = null
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun dismissUpdateDialog() = updates.dismissDialog()

    fun downloadUpdate() = updates.download()

    fun cancelUpdateDownload() = updates.cancelDownload()

    fun installUpdate() = updates.install()

    fun skipUpdateVersion(versionName: String) = updates.skipVersion(versionName)

    fun openUpdateReleasePage() = updates.openReleasePage()

    fun openExtensions(host: ViewGroup) {
        controller.reparentTo(host)
        controller.send(
            EngineCommand.SET_EXTENSION_MODE,
            buildJsonObject {
                put("enabled", true)
                put("css", extensionCss())
            }
        )
    }

    fun closeExtensions() {
        controller.send(
            EngineCommand.SET_EXTENSION_MODE,
            buildJsonObject { put("enabled", false) }
        )
        controller.restoreToEngineHost()
    }

    private fun extensionCss(): String = runCatching {
        context.assets.open("css/extension_mobile.css").bufferedReader().use { it.readText() }
    }.getOrDefault("")
}
