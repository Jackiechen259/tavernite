package com.stmobile.core.stengine

import android.view.ViewGroup
import com.stmobile.core.model.EnginePhase
import com.stmobile.core.model.EngineStatus
import com.stmobile.core.proxy.LocalProxyServer
import com.stmobile.core.proxy.ProxyConfig
import com.stmobile.core.proxy.ProxyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Application-scoped lifecycle owner for proxy + hidden ST engine. */
@Singleton
class EngineController @Inject constructor(
    private val engine: STEngineManager,
    private val proxy: LocalProxyServer
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    val proxyEvents: SharedFlow<ProxyEvent> get() = proxy.events

    private val _localOrigin = MutableStateFlow<String?>(null)
    val localOrigin: StateFlow<String?> = _localOrigin.asStateFlow()

    init {
        scope.launch {
            engine.events.collect { event ->
                if (event is EngineEvent.Ready) {
                    _status.value = engine.status.value
                }
                _events.emit(event)
            }
        }
        scope.launch {
            engine.status.collect { status ->
                if (status.phase == EnginePhase.READY || status.phase == EnginePhase.ERROR) {
                    _status.value = status
                }
            }
        }
    }

    fun attach(host: ViewGroup) = engine.attach(host)

    suspend fun connect(config: ProxyConfig): Int {
        _status.value = EngineStatus(EnginePhase.STARTING_PROXY, detail = "Starting loopback proxy")
        val port = proxy.start(config)
        val origin = "http://127.0.0.1:$port/"
        _localOrigin.value = origin
        _status.value = EngineStatus(EnginePhase.LOADING_ENGINE, detail = "Loading ST engine")
        engine.load(origin)
        return port
    }

    fun updateConfig(config: ProxyConfig) = proxy.updateConfig(config)

    suspend fun disconnect() {
        engine.reset()
        proxy.stop()
        _localOrigin.value = null
        _status.value = EngineStatus()
    }

    fun send(type: String, payload: kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.JsonObject(emptyMap())) = engine.send(type, payload)

    fun reload() = engine.reload()

    fun reparentTo(container: ViewGroup) = engine.reparentTo(container)

    fun restoreToEngineHost() = engine.restoreToEngineHost()

    fun proxyPort(): Int = proxy.port.value
}
