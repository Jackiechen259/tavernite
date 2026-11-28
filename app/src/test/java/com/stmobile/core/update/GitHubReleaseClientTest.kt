package com.stmobile.core.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubReleaseClientTest {

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

    private fun client() = GitHubReleaseClient(
        OkHttpClient(),
        server.url("/releases/latest").toString()
    )

    private fun releaseJson(
        tag: String = "v0.2.0",
        apkName: String = "Tavernite-0.2.0-200.apk",
        apkUrl: String = "https://github.com/Jackiechen259/tavernite/releases/download/v0.2.0/$apkName",
        shaUrl: String? = null
    ) = """
        {
          "tag_name": "$tag",
          "body": "Changes in this release",
          "html_url": "https://github.com/Jackiechen259/tavernite/releases/tag/$tag",
          "draft": false,
          "prerelease": false,
          "assets": [
            {"name": "$apkName", "browser_download_url": "$apkUrl", "size": 23000000},
            {"name": "$apkName.sha256", "browser_download_url": "${shaUrl ?: "$apkUrl.sha256"}", "size": 80}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses release and selects apk asset`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson()))
        val info = client().checkForUpdate("0.1.0", 100)!!
        assertEquals("0.2.0", info.versionName)
        assertEquals(200, info.versionCode)
        assertEquals("Changes in this release", info.notes)
        assertEquals(23000000L, info.apkSize)
        assertEquals(
            "https://github.com/Jackiechen259/tavernite/releases/download/v0.2.0/Tavernite-0.2.0-200.apk",
            info.apkUrl
        )
        assertEquals(info.apkUrl + ".sha256", info.sha256Url)
    }

    @Test
    fun `returns null when remote is not newer`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson()))
        assertNull(client().checkForUpdate("0.2.0", 200))
    }

    @Test
    fun `falls back to tag version code when asset name lacks code`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(releaseJson(apkName = "Tavernite.apk"))
        )
        val info = client().checkForUpdate("0.1.0", 100)!!
        assertEquals(200, info.versionCode)
    }

    @Test
    fun `fails when release has no apk asset`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"tag_name":"v0.2.0","body":"","html_url":"https://github.com/x/y/releases/tag/v0.2.0","assets":[]}"""
            )
        )
        val error = runCatching { client().checkForUpdate("0.1.0", 100) }.exceptionOrNull()
        assertTrue(error is UpdateCheckException)
        assertTrue(error!!.message!!.contains("APK"))
    }

    @Test
    fun `fails on unparseable tag`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(releaseJson(tag = "release-latest"))
        )
        val error = runCatching { client().checkForUpdate("0.1.0", 100) }.exceptionOrNull()
        assertTrue(error is UpdateCheckException)
    }

    @Test
    fun `maps rate limit to friendly error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        val error = runCatching { client().checkForUpdate("0.1.0", 100) }.exceptionOrNull()
        assertEquals("GitHub API 限流，请稍后再试", error?.message)
    }

    @Test
    fun `rejects untrusted asset url`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(releaseJson(apkUrl = "http://evil.example/app.apk"))
        )
        val error = runCatching { client().checkForUpdate("0.1.0", 100) }.exceptionOrNull()
        assertTrue(error is UpdateCheckException)
    }

    @Test
    fun `maps missing release to friendly error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val error = runCatching { client().checkForUpdate("0.1.0", 100) }.exceptionOrNull()
        assertTrue(error is UpdateCheckException)
        assertEquals("找不到 Release，请检查网络或仓库地址", error?.message)
    }

    @Test
    fun `maps server error to friendly error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val error = runCatching { client().checkForUpdate("0.1.0", 100) }.exceptionOrNull()
        assertTrue(error is UpdateCheckException)
        assertEquals("GitHub API 请求失败（HTTP 500）", error?.message)
    }

    @Test
    fun `fails on network interruption`() = runBlocking {
        server.enqueue(
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        )
        val error = runCatching { client().checkForUpdate("0.1.0", 100) }.exceptionOrNull()
        assertTrue(error is UpdateCheckException)
    }

    @Test
    fun `rejects untrusted apk host over https`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(releaseJson(apkUrl = "https://evil.example/app.apk"))
        )
        val error = runCatching { client().checkForUpdate("0.1.0", 100) }.exceptionOrNull()
        assertTrue(error is UpdateCheckException)
    }

    @Test
    fun `rejects untrusted checksum host over https`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(releaseJson(shaUrl = "https://evil.example/app.apk.sha256"))
        )
        val error = runCatching { client().checkForUpdate("0.1.0", 100) }.exceptionOrNull()
        assertTrue(error is UpdateCheckException)
    }

    @Test
    fun `reports malformed json`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{not json"))
        val error = runCatching { client().checkForUpdate("0.1.0", 100) }.exceptionOrNull()
        assertTrue(error is UpdateCheckException)
        assertEquals("GitHub 返回了无法解析的数据", error?.message)
    }
}
