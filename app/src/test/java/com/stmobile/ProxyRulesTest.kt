package com.stmobile

import com.stmobile.core.proxy.DiskAssetCache
import com.stmobile.core.proxy.ProxyRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProxyRulesTest {

    @Test
    fun `strips cookie domain and secure, normalizes samesite none`() {
        val rewritten = ProxyRules.rewriteSetCookie(
            "session=abc123; Domain=tavern.example.com; Path=/; Secure; HttpOnly; SameSite=None"
        )
        assertEquals("session=abc123; Path=/; HttpOnly; SameSite=Lax", rewritten)
    }

    @Test
    fun `keeps cookie without attributes intact`() {
        assertEquals("token=xyz", ProxyRules.rewriteSetCookie("token=xyz"))
    }

    @Test
    fun `rewrites absolute redirects to loopback origin`() {
        val local = "http://127.0.0.1:4567"
        assertEquals(
            "$local/login?next=/chat",
            ProxyRules.rewriteLocation(
                "https://tavern.example.com/login?next=/chat",
                "https://tavern.example.com",
                local
            )
        )
        assertEquals("/relative", ProxyRules.rewriteLocation("/relative", "https://x", local))
    }

    @Test
    fun `rewrites origin and referer headers`() {
        val local = "http://127.0.0.1:4567"
        val endpoint = "https://tavern.example.com"
        assertEquals(
            endpoint,
            ProxyRules.rewriteRequestHeader("Origin", local, local, endpoint)
        )
        assertEquals(
            "$endpoint/chat",
            ProxyRules.rewriteRequestHeader("Referer", "$local/chat", local, endpoint)
        )
        assertEquals(
            "https://other.example",
            ProxyRules.rewriteRequestHeader("Origin", "https://other.example", local, endpoint)
        )
    }

    @Test
    fun `hop by hop request headers are filtered`() {
        assertTrue(ProxyRules.isHopByHop("Connection"))
        assertTrue(ProxyRules.isHopByHop("accept-encoding"))
        assertTrue(ProxyRules.isHopByHop("Content-Length"))
        assertFalse(ProxyRules.isHopByHop("Authorization"))
        assertFalse(ProxyRules.isHopByHop("Cookie"))
    }

    @Test
    fun `response strips content type and content encoding`() {
        assertTrue(ProxyRules.shouldStripResponseHeader("Content-Type"))
        assertTrue(ProxyRules.shouldStripResponseHeader("content-encoding"))
        assertTrue(ProxyRules.shouldStripResponseHeader("Transfer-Encoding"))
        assertFalse(ProxyRules.shouldStripResponseHeader("Set-Cookie"))
    }

    @Test
    fun `detects stream and generation paths`() {
        assertTrue(ProxyRules.isEventStream("text/event-stream; charset=utf-8"))
        assertFalse(ProxyRules.isEventStream("application/json"))
        assertTrue(ProxyRules.isGenerationPath("/api/backends/chat-completions/generate"))
        assertTrue(ProxyRules.isGenerationPath("/api/stream"))
        assertFalse(ProxyRules.isGenerationPath("/characters/x.png"))
    }
}

class DiskAssetCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `stores and retrieves asset with metadata`() {
        var now = 1_000L
        val cache = DiskAssetCache(temp.root, now = { now })
        val url = "https://st.example/script.js?v=1"
        cache.put(url, "body".toByteArray(), "\"etag-1\"", "Mon, 01 Jan 2024 00:00:00 GMT", "text/javascript")

        val entry = cache.get(url)!!
        assertEquals("body", entry.body.decodeToString())
        assertEquals("\"etag-1\"", entry.meta.etag)
        assertEquals("text/javascript", entry.meta.contentType)

        assertTrue(cache.isFresh(entry))
        now += 25 * 60 * 60 * 1000L
        assertFalse(cache.isFresh(entry))
    }

    @Test
    fun `key is stable and distinct per url`() {
        val cache = DiskAssetCache(temp.root)
        assertEquals(cache.keyFor("https://a/x.css"), cache.keyFor("https://a/x.css"))
        assertFalse(cache.keyFor("https://a/x.css") == cache.keyFor("https://a/y.css"))
    }

    @Test
    fun `missing entry returns null and touch refreshes timestamp`() {
        var now = 10L
        val cache = DiskAssetCache(temp.root, now = { now })
        assertNull(cache.get("https://a/missing.js"))
        val url = "https://a/present.css"
        cache.put(url, byteArrayOf(1, 2, 3), null, null, "text/css")
        now += 90_000
        cache.touch(url)
        assertTrue(cache.isFresh(cache.get(url)!!))
    }

    @Test
    fun `clear removes entries`() {
        val cache = DiskAssetCache(temp.root)
        cache.put("https://a/x.js", byteArrayOf(1), null, null, "text/javascript")
        assertNotNull(cache.get("https://a/x.js"))
        cache.clear()
        assertNull(cache.get("https://a/x.js"))
    }

    @Test
    fun `static extension detection ignores query string`() {
        assertTrue(DiskAssetCache.isStatic("/css/style.css?v=3"))
        assertTrue(DiskAssetCache.isStatic("/fonts/inter.woff2"))
        assertFalse(DiskAssetCache.isStatic("/api/characters/list"))
        assertFalse(DiskAssetCache.isStatic("/api/backends/generate"))
    }
}
