package com.stmobile.core.proxy

/** Immutable proxy configuration derived from the active [ServerProfile]. */
data class ProxyConfig(
    val endpoints: List<String>,
    val headers: Map<String, String> = emptyMap(),
    val basicAuth: String? = null,
    val caPem: String? = null,
    val allowInsecureTls: Boolean = false,
    val cacheEnabled: Boolean = true,
    val connectTimeoutMs: Long = 8_000L,
    val readTimeoutMs: Long = 300_000L,
    val writeTimeoutMs: Long = 30_000L
) {
    init {
        require(endpoints.isNotEmpty()) { "ProxyConfig requires at least one endpoint" }
    }
}

sealed interface ProxyEvent {
    data class StreamStarted(val path: String) : ProxyEvent
    data class StreamEnded(val path: String, val bytes: Long) : ProxyEvent
    data class UpstreamError(val path: String, val message: String) : ProxyEvent
    data class EndpointSwitch(val url: String, val from: String?) : ProxyEvent
    data class CacheHit(val path: String) : ProxyEvent
}
