package com.stmobile.core.proxy

import com.stmobile.core.model.EndpointHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class UpstreamResult(
    val response: Response,
    val latencyMs: Long,
    val endpoint: String,
    val targetUrl: String
)

/** OkHttp upstream client with per-profile TLS, header injection and failover support. */
class UpstreamClient {

    private val lock = Any()
    private var cachedKey: String? = null
    private var cachedClient: OkHttpClient? = null

    fun client(config: ProxyConfig): OkHttpClient {
        val key = listOf(
            config.caPem?.hashCode() ?: 0,
            config.allowInsecureTls,
            config.connectTimeoutMs,
            config.readTimeoutMs,
            config.writeTimeoutMs
        ).toString()
        synchronized(lock) {
            cachedClient?.let { if (cachedKey == key) return it }
            cachedClient?.connectionPool?.evictAll()
            val builder = OkHttpClient.Builder()
                .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(config.writeTimeoutMs, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
            if (!config.caPem.isNullOrBlank() || config.allowInsecureTls) {
                val factory = buildSslSocketFactory(config.caPem, config.allowInsecureTls)
                if (factory != null) {
                    builder.sslSocketFactory(factory.first, factory.second)
                }
                if (config.allowInsecureTls) {
                    builder.hostnameVerifier { _, _ -> true }
                }
            }
            val client = builder.build()
            cachedKey = key
            cachedClient = client
            return client
        }
    }

    /**
     * Executes one upstream request. The caller owns [UpstreamResult.response]
     * and must close it.
     */
    fun execute(
        config: ProxyConfig,
        method: String,
        targetUrl: String,
        incomingHeaders: List<Pair<String, String>>,
        body: ByteArray?,
        localOrigin: String
    ): UpstreamResult {
        val url: HttpUrl = targetUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid upstream URL: $targetUrl")
        val builder = Request.Builder().url(url)

        val providedNames = incomingHeaders.map { it.first.lowercase() }.toSet()
        var contentType: String? = null
        for ((name, value) in incomingHeaders) {
            val lower = name.lowercase()
            if (lower == "content-type") contentType = value
            if (ProxyRules.isHopByHop(lower)) continue
            if (lower == "authorization" && config.basicAuth != null) continue
            val rewritten = ProxyRules.rewriteRequestHeader(name, value, localOrigin, targetUrl)
            runCatching { builder.addHeader(name, rewritten) }
        }
        config.headers.forEach { (name, value) ->
            if (name.lowercase() !in providedNames) {
                runCatching { builder.header(name, value) }
            }
        }
        if (!config.basicAuth.isNullOrBlank() && "authorization" !in providedNames) {
            val encoded = Base64.getEncoder()
                .encodeToString(config.basicAuth.toByteArray(Charsets.UTF_8))
            builder.header("Authorization", "Basic $encoded")
        }

        val requestBody = when {
            body != null -> body.toRequestBody(contentType?.toMediaTypeOrNull())
            method.equals("POST", true) || method.equals("PUT", true) || method.equals("PATCH", true) ->
                ByteArray(0).toRequestBody(contentType?.toMediaTypeOrNull())
            else -> null
        }
        builder.method(method, requestBody)

        val started = System.nanoTime()
        val response = client(config).newCall(builder.build()).execute()
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        return UpstreamResult(response, elapsed, targetUrl, targetUrl)
    }

    suspend fun probe(endpoints: List<String>, config: ProxyConfig): List<EndpointHealth> =
        withContext(Dispatchers.IO) {
            endpoints.map { endpoint ->
                val base = EndpointRouter.normalize(endpoint)
                if (base.isEmpty()) {
                    return@map EndpointHealth(endpoint, false, error = "Blank endpoint")
                }
                val started = System.nanoTime()
                try {
                    val request = Request.Builder().url("$base/").head().build()
                    client(config).newCall(request).execute().use { response ->
                        val elapsed = (System.nanoTime() - started) / 1_000_000L
                        if (response.isSuccessful || response.code in 300..399) {
                            EndpointHealth(endpoint, true, elapsed)
                        } else {
                            EndpointHealth(endpoint, false, elapsed, "HTTP ${response.code}")
                        }
                    }
                } catch (t: Throwable) {
                    EndpointHealth(endpoint, false, null, t.message ?: t::class.java.simpleName)
                }
            }
        }

    private fun buildSslSocketFactory(caPem: String?, insecure: Boolean): Pair<SSLSocketFactory, X509TrustManager>? {
        val trustManager: X509TrustManager = when {
            insecure -> InsecureTrustManager
            !caPem.isNullOrBlank() -> CompositeTrustManager(
                listOf(systemTrustManager(), pemTrustManager(caPem))
            )
            else -> systemTrustManager()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        return context.socketFactory to trustManager
    }

    private fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun pemTrustManager(pem: String): X509TrustManager {
        val certs = CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(pem.toByteArray(Charsets.UTF_8)))
            .filterIsInstance<X509Certificate>()
        require(certs.isNotEmpty()) { "No certificates found in PEM" }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        certs.forEachIndexed { index, cert -> keyStore.setCertificateEntry("tavernite-ca-$index", cert) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private object InsecureTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class CompositeTrustManager(private val delegates: List<X509TrustManager>) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            check(chain, authType, client = true)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            check(chain, authType, client = false)
        }

        private fun check(chain: Array<out X509Certificate>?, authType: String?, client: Boolean) {
            var last: Exception? = null
            for (delegate in delegates) {
                try {
                    if (client) delegate.checkClientTrusted(chain, authType)
                    else delegate.checkServerTrusted(chain, authType)
                    return
                } catch (e: Exception) {
                    last = e
                }
            }
            chain ?: throw last ?: IllegalStateException("Empty certificate chain")
            throw (last ?: IllegalStateException("Certificate rejected"))
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            delegates.flatMap { it.acceptedIssuers.asList() }.toTypedArray()
    }
}
