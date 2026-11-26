package com.stmobile.core.proxy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Disk cache for static ST frontend assets (js/css/fonts/images), revalidated
 * with ETag / Last-Modified. Keys are hashed URLs so nested paths stay flat.
 */
class DiskAssetCache(
    private val root: File,
    private val maxBytes: Long = 200L * 1024 * 1024,
    private val maxAgeMs: Long = 24L * 60 * 60 * 1000,
    private val now: () -> Long = System::currentTimeMillis
) {
    @Serializable
    data class Meta(
        val url: String,
        val etag: String? = null,
        val lastModified: String? = null,
        val contentType: String? = null,
        val savedAt: Long = 0L
    )

    data class Entry(val body: ByteArray, val meta: Meta)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun bodyFile(key: String) = File(root, "$key.body")
    private fun metaFile(key: String) = File(root, "$key.meta")

    fun keyFor(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isFresh(entry: Entry): Boolean = now() - entry.meta.savedAt < maxAgeMs

    fun get(url: String): Entry? = synchronized(this) {
        val key = keyFor(url)
        val body = bodyFile(key)
        val meta = metaFile(key)
        if (!body.isFile || !meta.isFile) return null
        return runCatching {
            val parsed = json.decodeFromString(Meta.serializer(), meta.readText())
            Entry(body.readBytes(), parsed)
        }.recoverCatching {
            body.delete(); meta.delete(); null
        }.getOrNull()
    }

    fun put(url: String, body: ByteArray, etag: String?, lastModified: String?, contentType: String?) {
        synchronized(this) {
            if (body.size > 8L * 1024 * 1024) return
            runCatching {
                if (!root.isDirectory) root.mkdirs()
                val key = keyFor(url)
                val meta = Meta(
                    url = url,
                    etag = etag,
                    lastModified = lastModified,
                    contentType = contentType,
                    savedAt = now()
                )
                val bodyTmp = File(root, "$key.body.tmp")
                val metaTmp = File(root, "$key.meta.tmp")
                bodyTmp.writeBytes(body)
                metaTmp.writeText(json.encodeToString(Meta.serializer(), meta))
                bodyTmp.renameTo(bodyFile(key))
                metaTmp.renameTo(metaFile(key))
            }
            pruneLocked()
        }
    }

    fun touch(url: String, etag: String? = null, lastModified: String? = null) {
        synchronized(this) {
            val key = keyFor(url)
            val meta = metaFile(key)
            if (!meta.isFile) return
            runCatching {
                val parsed = json.decodeFromString(Meta.serializer(), meta.readText())
                meta.writeText(
                    json.encodeToString(
                        Meta.serializer(),
                        parsed.copy(
                            etag = etag ?: parsed.etag,
                            lastModified = lastModified ?: parsed.lastModified,
                            savedAt = now()
                        )
                    )
                )
            }
        }
    }

    fun clear() {
        synchronized(this) {
            root.listFiles()?.forEach { it.delete() }
        }
    }

    fun totalBytes(): Long = synchronized(this) {
        root.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun pruneLocked() {
        val files = root.listFiles() ?: return
        val total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val bodies = files.filter { it.name.endsWith(".body") }.sortedBy { it.lastModified() }
        var current = total
        for (file in bodies) {
            if (current <= maxBytes) break
            current -= file.length()
            file.delete()
            metaFile(file.name.removeSuffix(".body")).delete()
        }
    }

    companion object {
        val STATIC_EXTENSIONS = setOf(
            ".js", ".mjs", ".css", ".woff", ".woff2", ".ttf", ".otf",
            ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".ico", ".map"
        )

        fun isStatic(path: String): Boolean {
            val lower = path.substringBefore('?').lowercase()
            return STATIC_EXTENSIONS.any { lower.endsWith(it) }
        }
    }
}
