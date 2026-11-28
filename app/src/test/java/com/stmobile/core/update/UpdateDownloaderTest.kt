package com.stmobile.core.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class UpdateDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val apkBytes = ByteArray(4096) { (it % 251).toByte() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun info(shaUrl: String?): UpdateInfo = UpdateInfo(
        versionName = "0.2.0",
        versionCode = 200,
        notes = "",
        apkUrl = server.url("/apk").toString(),
        apkSize = apkBytes.size.toLong(),
        sha256Url = shaUrl,
        releaseUrl = "https://github.com/Jackiechen259/tavernite/releases/tag/v0.2.0"
    )

    @Test
    fun `downloads apk and verifies sha256`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(apkBytes)))
        server.enqueue(MockResponse().setBody("${sha256(apkBytes)}  Tavernite-0.2.0-200.apk\n"))
        val dir = temp.newFolder("updates")
        var lastRead = 0L
        var lastTotal = 0L
        val file = UpdateDownloader(OkHttpClient()).download(
            info(server.url("/sha").toString()),
            dir
        ) { read, total ->
            lastRead = read
            lastTotal = total
        }
        assertArrayEquals(apkBytes, file.readBytes())
        assertEquals(apkBytes.size.toLong(), lastRead)
        assertEquals(apkBytes.size.toLong(), lastTotal)
        assertFalse(File(dir, "${file.name}.part").exists())
    }

    @Test
    fun `deletes file on sha mismatch`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(apkBytes)))
        server.enqueue(MockResponse().setBody("${"0".repeat(64)}  x.apk\n"))
        val dir = temp.newFolder("updates")
        val error = runCatching {
            UpdateDownloader(OkHttpClient()).download(info(server.url("/sha").toString()), dir)
        }.exceptionOrNull()
        assertTrue(error is UpdateDownloadException)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `skips verification when no sha asset`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(apkBytes)))
        val dir = temp.newFolder("updates")
        val file = UpdateDownloader(OkHttpClient()).download(info(null), dir)
        assertArrayEquals(apkBytes, file.readBytes())
    }

    @Test
    fun `fails on http error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val dir = temp.newFolder("updates")
        val error = runCatching {
            UpdateDownloader(OkHttpClient()).download(info(null), dir)
        }.exceptionOrNull()
        assertTrue(error is UpdateDownloadException)
    }

    @Test
    fun `fails on truncated download`() = runBlocking {
        val truncated = apkBytes.copyOf(apkBytes.size - 100)
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(truncated), 1024))
        val dir = temp.newFolder("updates")
        val error = runCatching {
            UpdateDownloader(OkHttpClient()).download(info(null), dir)
        }.exceptionOrNull()
        assertTrue(error is UpdateDownloadException)
        assertEquals("安装包下载不完整", error?.message)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }
}
