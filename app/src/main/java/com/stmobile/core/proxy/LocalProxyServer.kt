package com.stmobile.core.proxy

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Local reverse proxy bound to 127.0.0.1 on an ephemeral port. The hidden ST
 * engine WebView always talks to this single origin, so endpoint failover
 * never reloads the page or loses the session.
 */
class LocalProxyServer(
    private val upstream: UpstreamClient = UpstreamClient(),
    cacheDir: File,
    private val cache: DiskAssetCache = DiskAssetCache(File(cacheDir, "proxy_assets")),
    @Suppress("unused") private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        const val HOST = "127.0.0.1"
        private const val MAX_ATTEMPTS = 3
    }

    private val _port = MutableStateFlow(0)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _events = MutableSharedFlow<ProxyEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ProxyEvent> = _events.asSharedFlow()

    @Volatile private var config: ProxyConfig? = null
    @Volatile private var router: EndpointRouter? = null
    private var engine: EmbeddedServer<*, *>? = null

    val localOrigin: String get() = "http://$HOST:${_port.value}"

    suspend fun start(config: ProxyConfig): Int {
        stop()
        this.config = config
        this.router = EndpointRouter(config.endpoints)
        val server = embeddedServer(CIO, host = HOST, port = 0) { installRoutes() }
        server.start(wait = false)
        val connectors = withContext(Dispatchers.IO) { server.engine.resolvedConnectors() }
        val bound = connectors.firstOrNull() ?: error("Proxy failed to bind any connector")
        engine = server
        _port.value = bound.port
        return bound.port
    }

    fun updateConfig(config: ProxyConfig) {
        this.config = config
        this.router = EndpointRouter(config.endpoints)
    }

    suspend fun stop() {
        val server = engine
        engine = null
        _port.value = 0
        if (server != null) {
            withContext(Dispatchers.IO) { runCatching { server.stop(300, 1_500) } }
        }
    }

    fun cacheBytes(): Long = cache.totalBytes()

    fun clearCache() = cache.clear()

    private fun Application.installRoutes() {
        routing {
            get("/__tavernite/status") {
                val active = router?.current() ?: "none"
                call.respondText(
                    """{"activeEndpoint":"$active","port":${_port.value}}""",
                    ContentType.Application.Json
                )
            }
            handle { call.proxyCall() }
            route("{path...}") { handle { call.proxyCall() } }
        }
    }

    private suspend fun ApplicationCall.proxyCall() {
        val cfg = config
        val router = router
        if (cfg == null || router == null) {
            respondText("Proxy not configured", status = HttpStatusCode.ServiceUnavailable)
            return
        }

        val method = request.httpMethod.value
        val rawUri = request.uri
        val pathOnly = rawUri.substringBefore('?')
        val local = localOrigin
        val body = if (methodHasBody(method)) {
            runCatching { receive<ByteArray>() }.getOrNull()
        } else {
            null
        }
        val incomingHeaders = request.headers.entries().flatMap { entry ->
            entry.value.map { entry.key to it }
        }

        val cacheableRequest = cfg.cacheEnabled &&
            method.equals(HttpMethod.Get.value, true) &&
            DiskAssetCache.isStatic(pathOnly)

        var attempt = 0
        var lastEndpoint: String? = null
        var lastError: Throwable? = null

        while (attempt < MAX_ATTEMPTS) {
            attempt++
            val endpoint = router.current()
            if (endpoint == lastEndpoint && attempt > 1) break
            lastEndpoint = endpoint
            val targetUrl = joinUrl(endpoint, rawUri)
            val cached = if (cacheableRequest) cache.get(targetUrl) else null

            if (cached != null && cache.isFresh(cached)) {
                _events.tryEmit(ProxyEvent.CacheHit(pathOnly))
                respondCached(cached)
                return
            }

            val headers = if (cached != null) {
                incomingHeaders + buildList {
                    cached.meta.etag?.let { add("If-None-Match" to it) }
                    cached.meta.lastModified?.let { add("If-Modified-Since" to it) }
                }
            } else {
                incomingHeaders
            }

            try {
                val result = withContext(Dispatchers.IO) {
                    upstream.execute(cfg, method, targetUrl, headers, body, local)
                }
                router.reportSuccess(endpoint, result.latencyMs)
                val response = result.response
                try {
                    if (cacheableRequest && response.code == 304 && cached != null) {
                        cache.touch(
                            targetUrl,
                            etag = response.header("ETag"),
                            lastModified = response.header("Last-Modified")
                        )
                        respondCached(cached)
                        return
                    }
                    if (cacheableRequest && response.code == 200) {
                        val bytes = runCatching { response.body?.bytes() }.getOrNull()
                        if (bytes != null) {
                            cache.put(
                                targetUrl,
                                bytes,
                                response.header("ETag"),
                                response.header("Last-Modified"),
                                response.header("Content-Type")
                            )
                            applyResponseHeaders(response, local, endpoint)
                            val contentType = response.header("Content-Type")?.let {
                                runCatching { ContentType.parse(it) }.getOrNull()
                            }
                            respondBytes(bytes, contentType, HttpStatusCode.fromValue(response.code))
                            return
                        }
                    }
                    respondStreaming(response, local, endpoint, pathOnly)
                    return
                } finally {
                    runCatching { response.close() }
                }
            } catch (e: IOException) {
                lastError = e
                _events.tryEmit(ProxyEvent.UpstreamError(pathOnly, e.message ?: "IO error"))
                val next = router.reportFailure()
                if (next != null && next != endpoint) {
                    _events.tryEmit(ProxyEvent.EndpointSwitch(next, endpoint))
                }
            }
        }

        val message = lastError?.message ?: "All upstream endpoints failed"
        _events.tryEmit(ProxyEvent.UpstreamError(pathOnly, message))
        respondText(
            "Upstream unreachable: $message",
            status = HttpStatusCode.BadGateway,
            contentType = ContentType.Text.Plain
        )
    }

    private suspend fun ApplicationCall.respondCached(entry: DiskAssetCache.Entry) {
        entry.meta.etag?.let { response.headers.append("ETag", it) }
        entry.meta.lastModified?.let { response.headers.append("Last-Modified", it) }
        val contentType = entry.meta.contentType?.let {
            runCatching { ContentType.parse(it) }.getOrNull()
        }
        respondBytes(entry.body, contentType, HttpStatusCode.OK)
    }

    private suspend fun ApplicationCall.respondStreaming(
        response0: okhttp3.Response,
        localOrigin: String,
        endpoint: String,
        path: String
    ) {
        applyResponseHeaders(response0, localOrigin, endpoint)
        val body = response0.body
        val contentType = response0.header("Content-Type")?.let {
            runCatching { ContentType.parse(it) }.getOrNull()
        }
        if (body == null) {
            respond(HttpStatusCode.fromValue(response0.code))
            return
        }
        val contentLength = body.contentLength().takeIf { it > 0 }
        val isStream = ProxyRules.isEventStream(response0.header("Content-Type")) ||
            ProxyRules.isGenerationPath(path)
        val status = HttpStatusCode.fromValue(response0.code)
        if (isStream) _events.tryEmit(ProxyEvent.StreamStarted(path))
        var total = 0L
        try {
            respondBytesWriter(
                contentType = contentType,
                status = status,
                contentLength = contentLength
            ) {
                val source = body.source()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read == -1) break
                    writeFully(buffer, 0, read)
                    flush()
                    total += read
                }
            }
        } finally {
            if (isStream) _events.tryEmit(ProxyEvent.StreamEnded(path, total))
        }
    }

    private fun ApplicationCall.applyResponseHeaders(
        upstreamResponse: okhttp3.Response,
        localOrigin: String,
        endpoint: String
    ) {
        upstreamResponse.headers.names().forEach { name ->
            if (ProxyRules.shouldStripResponseHeader(name)) return@forEach
            upstreamResponse.headers.values(name).forEach { value ->
                val rewritten = when (name.lowercase()) {
                    "location" -> ProxyRules.rewriteLocation(value, endpoint, localOrigin)
                    "set-cookie" -> ProxyRules.rewriteSetCookie(value)
                    "access-control-allow-origin" ->
                        ProxyRules.rewriteCorsOrigin(value, endpoint, localOrigin)
                    else -> value
                }
                runCatching { response.headers.append(name, rewritten) }
            }
        }
    }

    private fun methodHasBody(method: String): Boolean =
        method.equals("POST", true) || method.equals("PUT", true) ||
            method.equals("PATCH", true) || method.equals("DELETE", true)

    private fun joinUrl(endpoint: String, rawUri: String): String {
        val base = endpoint.trimEnd('/')
        return if (rawUri.startsWith("/")) base + rawUri else "$base/$rawUri"
    }
}
