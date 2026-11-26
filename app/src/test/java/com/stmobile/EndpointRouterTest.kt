package com.stmobile

import com.stmobile.core.proxy.EndpointRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointRouterTest {

    @Test
    fun `normalizes missing scheme and trailing slash`() {
        assertEquals("http://host:8000", EndpointRouter.normalize("host:8000/"))
        assertEquals("https://host/path", EndpointRouter.normalize("https://host/path/"))
        assertEquals("", EndpointRouter.normalize("   "))
    }

    @Test
    fun `failover moves to next endpoint and backs off`() {
        var now = 0L
        val router = EndpointRouter(
            listOf("https://primary", "https://secondary"),
            now = { now }
        )
        assertEquals("https://primary", router.current())

        val next = router.reportFailure()
        assertEquals("https://secondary", next)
        assertEquals("https://secondary", router.current())

        now += 9_000
        assertEquals("https://secondary", router.current())

        now += 2_000
        assertEquals("https://primary", router.current())
    }

    @Test
    fun `success resets failure state and records latency`() {
        var now = 0L
        val router = EndpointRouter(listOf("https://a", "https://b"), now = { now })
        router.reportFailure()
        assertEquals("https://b", router.current())
        router.reportSuccess("https://b", 42)

        val health = router.snapshot().first { it.url == "https://b" }
        assertTrue(health.reachable)
        assertEquals(42L, health.latencyMs)
        assertEquals("https://b", router.current())
    }

    @Test
    fun `deduplicates endpoints preserving priority`() {
        val router = EndpointRouter(listOf("https://a", "https://a/", "https://b"))
        assertEquals(listOf("https://a", "https://b"), router.endpoints())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects empty endpoint list`() {
        EndpointRouter(emptyList())
    }

    @Test
    fun `snapshot includes every endpoint`() {
        val router = EndpointRouter(listOf("https://a", "https://b"))
        assertEquals(2, router.snapshot().size)
        assertNotNull(router.snapshot().first().url)
    }
}
