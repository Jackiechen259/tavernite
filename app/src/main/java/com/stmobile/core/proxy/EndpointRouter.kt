package com.stmobile.core.proxy

import com.stmobile.core.model.EndpointHealth

/**
 * Ordered endpoint failover. Each endpoint gets an exponential cooldown after
 * a connection-level failure, and the WebView keeps talking to the same
 * loopback origin so switching never reloads the page or drops the session.
 */
class EndpointRouter(
    endpoints: List<String>,
    private val now: () -> Long = System::currentTimeMillis
) {
    data class Entry(
        val url: String,
        var failures: Int = 0,
        var cooldownUntil: Long = 0L,
        var lastLatencyMs: Long? = null
    )

    private val lock = Any()
    private val entries: MutableList<Entry> = endpoints
        .map(::normalize)
        .filter { it.isNotEmpty() }
        .distinct()
        .map { Entry(it) }
        .toMutableList()

    init {
        require(entries.isNotEmpty()) { "EndpointRouter requires at least one endpoint" }
    }

    fun current(): String = synchronized(lock) {
        val nowMs = now()
        entries.firstOrNull { it.cooldownUntil <= nowMs }?.url
            ?: entries.minByOrNull { it.cooldownUntil }!!.url
    }

    fun reportSuccess(url: String, latencyMs: Long) = synchronized(lock) {
        entries.firstOrNull { it.url == normalize(url) }?.apply {
            failures = 0
            cooldownUntil = 0L
            lastLatencyMs = latencyMs
        }
        Unit
    }

    /** Marks the currently selected endpoint failed; returns the next candidate. */
    fun reportFailure(): String? = synchronized(lock) {
        val active = entries.firstOrNull { it.cooldownUntil <= now() }
            ?: entries.minByOrNull { it.cooldownUntil }!!
        active.failures += 1
        active.cooldownUntil = now() + (10_000L shl (active.failures - 1).coerceIn(0, 4))
        entries.firstOrNull { it.cooldownUntil <= now() }?.url
            ?: entries.minByOrNull { it.cooldownUntil }?.url
    }

    fun snapshot(): List<EndpointHealth> = synchronized(lock) {
        val nowMs = now()
        entries.map { e ->
            EndpointHealth(
                url = e.url,
                reachable = e.cooldownUntil <= nowMs,
                latencyMs = e.lastLatencyMs
            )
        }
    }

    fun endpoints(): List<String> = synchronized(lock) { entries.map { it.url } }

    companion object {
        fun normalize(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            val withScheme = when {
                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                else -> "http://$trimmed"
            }
            return withScheme.trimEnd('/')
        }
    }
}
