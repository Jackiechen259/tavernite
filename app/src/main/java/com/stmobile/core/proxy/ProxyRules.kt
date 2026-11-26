package com.stmobile.core.proxy

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Pure header/path rewriting rules used by the proxy. Kept side-effect free so
 * they can be unit-tested on the JVM.
 */
object ProxyRules {

    private val HOP_BY_HOP = setOf(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailer", "transfer-encoding", "upgrade", "host",
        "content-length", "accept-encoding", "expect"
    )

    fun isHopByHop(name: String): Boolean = name.lowercase() in HOP_BY_HOP

    fun shouldStripResponseHeader(name: String): Boolean {
        val lower = name.lowercase()
        return lower in HOP_BY_HOP || lower == "content-type" || lower == "content-encoding"
    }

    fun originOf(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url.trimEnd('/')
        val defaultPort = (parsed.scheme == "http" && parsed.port == 80) ||
            (parsed.scheme == "https" && parsed.port == 443)
        return if (defaultPort) "${parsed.scheme}://${parsed.host}"
        else "${parsed.scheme}://${parsed.host}:${parsed.port}"
    }

    /** Rewrites a browser-supplied Origin/Referer from loopback to the upstream origin. */
    fun rewriteRequestHeader(
        name: String,
        value: String,
        localOrigin: String,
        endpointUrl: String
    ): String {
        val lower = name.lowercase()
        if (lower != "origin" && lower != "referer") return value
        val endpointOrigin = originOf(endpointUrl)
        if (value == localOrigin) return endpointOrigin
        if (value.startsWith("$localOrigin/")) return endpointOrigin + value.removePrefix(localOrigin)
        return value
    }

    /** Rewrites an absolute upstream redirect back to the loopback origin. */
    fun rewriteLocation(value: String, endpointUrl: String, localOrigin: String): String {
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) return value
        val endpointOrigin = originOf(endpointUrl)
        if (value.startsWith(endpointOrigin)) {
            return localOrigin + value.removePrefix(endpointOrigin)
        }
        return value
    }

    /**
     * Cookies must be re-scoped to 127.0.0.1 so the WebView keeps them and
     * forwards them back through the proxy on the next request.
     */
    fun rewriteSetCookie(value: String): String {
        val parts = value.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val kept = parts.filterNot { part ->
            part.startsWith("domain=", true) || part.equals("secure", true) ||
                part.equals("partitioned", true)
        }.map { part ->
            if (part.equals("samesite=none", true)) "SameSite=Lax" else part
        }
        return kept.joinToString("; ")
    }

    fun rewriteCorsOrigin(value: String, endpointUrl: String, localOrigin: String): String =
        if (value == originOf(endpointUrl)) localOrigin else value

    fun isEventStream(contentType: String?): Boolean =
        contentType?.lowercase()?.contains("text/event-stream") == true

    fun isGenerationPath(path: String): Boolean =
        path.contains("/generate", ignoreCase = true) ||
            path.contains("/stream", ignoreCase = true)
}
