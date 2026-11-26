package com.stmobile

import com.stmobile.core.proxy.LocalProxyServer
import com.stmobile.core.proxy.ProxyConfig
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class LocalProxyServerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var upstream: MockWebServer
    private lateinit var proxy: LocalProxyServer
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val upstreamUrl: String get() = upstream.url("/").toString().trimEnd('/')

    @Before
    fun setUp() {
        upstream = MockWebServer()
        upstream.start()
        proxy = LocalProxyServer(cacheDir = temp.root)
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { proxy.stop() } }
        runCatching { upstream.shutdown() }
    }

    private fun startProxy(
        headers: Map<String, String> = emptyMap(),
        endpoints: List<String> = listOf(upstreamUrl),
        cacheEnabled: Boolean = false
    ): Int = runBlocking {
        proxy.start(
            ProxyConfig(
                endpoints = endpoints,
                headers = headers,
                cacheEnabled = cacheEnabled
            )
        )
    }

    private fun get(port: Int, path: String): okhttp3.Response =
        client.newCall(Request.Builder().url("http://127.0.0.1:$port$path").build()).execute()

    @Test
    fun `forwards get with query and injects custom headers`() {
        upstream.enqueue(
            MockResponse().setBody("hello").setHeader("Content-Type", "text/plain")
        )
        val port = startProxy(headers = mapOf("X-Test" to "abc"))

        get(port, "/hello?x=1").use { response ->
            assertEquals(200, response.code)
            assertEquals("hello", response.body?.string())
        }

        val recorded = upstream.takeRequest()
        assertEquals("/hello?x=1", recorded.path)
        assertEquals("abc", recorded.getHeader("X-Test"))
        assertTrue(recorded.getHeader("Accept-Encoding")!!.contains("gzip"))
    }

    @Test
    fun `forwards post body unchanged`() {
        upstream.enqueue(MockResponse().setBody("ok"))
        val port = startProxy()
        val payload = """{"text":"hi there"}"""

        client.newCall(
            Request.Builder()
                .url("http://127.0.0.1:$port/api/send")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("ok", response.body?.string())
        }

        val recorded = upstream.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(payload, recorded.body.readUtf8())
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun `rewrites redirect location and set cookie for loopback`() {
        upstream.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "${upstreamUrl}/login?next=/chat")
                .setHeader("Set-Cookie", "sid=1; Domain=example.com; Path=/; Secure; HttpOnly; SameSite=None")
        )
        val port = startProxy()

        get(port, "/").use { response ->
            assertEquals(302, response.code)
            assertEquals("http://127.0.0.1:$port/login?next=/chat", response.header("Location"))
            assertEquals("sid=1; Path=/; HttpOnly; SameSite=Lax", response.headers.values("Set-Cookie").first())
        }
    }

    @Test
    fun `rewrites origin header to upstream origin`() {
        upstream.enqueue(MockResponse().setBody("ok"))
        val port = startProxy()

        client.newCall(
            Request.Builder()
                .url("http://127.0.0.1:$port/api/thing")
                .header("Origin", "http://127.0.0.1:$port")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { response -> assertEquals(200, response.code) }

        assertEquals(upstreamUrl, upstream.takeRequest().getHeader("Origin"))
    }

    @Test
    fun `failover skips dead endpoint and serves from live one`() {
        upstream.enqueue(MockResponse().setBody("recovered"))

        val deadPort = ServerSocket(0).use { it.localPort }
        val port = startProxy(
            endpoints = listOf("http://127.0.0.1:$deadPort", upstreamUrl)
        )

        get(port, "/health").use { response ->
            assertEquals(200, response.code)
            assertEquals("recovered", response.body?.string())
        }
    }

    @Test
    fun `passes through sse body unbuffered`() {
        val body = "data: one\n\ndata: two\n\ndata: [DONE]\n\n"
        upstream.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
        )
        val port = startProxy()

        get(port, "/api/backends/chat-completions/generate").use { response ->
            assertEquals(200, response.code)
            assertTrue(response.header("Content-Type")!!.startsWith("text/event-stream"))
            assertEquals(body, response.body?.string())
        }
    }

    @Test
    fun `serves static asset from cache on second request`() {
        upstream.enqueue(
            MockResponse()
                .setBody("console.log('st')")
                .setHeader("Content-Type", "text/javascript")
                .setHeader("ETag", "\"v1\"")
        )
        val port = startProxy(cacheEnabled = true)

        get(port, "/script/app.js").use { response ->
            assertEquals(200, response.code)
            assertEquals("console.log('st')", response.body?.string())
            assertEquals("\"v1\"", response.header("ETag"))
        }
        get(port, "/script/app.js").use { response ->
            assertEquals(200, response.code)
            assertEquals("console.log('st')", response.body?.string())
        }

        assertEquals(1, upstream.requestCount)
    }

    @Test
    fun `returns bad gateway when all endpoints are unreachable`() {
        val deadPort = ServerSocket(0).use { it.localPort }
        val port = startProxy(endpoints = listOf("http://127.0.0.1:$deadPort"))

        get(port, "/health").use { response ->
            assertEquals(502, response.code)
        }
    }
}
