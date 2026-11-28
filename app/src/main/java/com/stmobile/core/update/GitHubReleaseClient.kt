package com.stmobile.core.update

import com.stmobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI

/**
 * Fetches the latest GitHub release and turns it into an [UpdateInfo].
 *
 * The repository is public, so no token is sent. Asset URLs are validated
 * against an allowlist before they are exposed to the downloader.
 */
class GitHubReleaseClient(
    private val client: OkHttpClient,
    private val apiUrl: String = UpdateConfig.API_URL
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns the newer release, or null when the remote version is not newer
     * than [currentVersionName]/[currentVersionCode].
     *
     * @throws UpdateCheckException when the request fails or the response is
     * malformed or untrusted.
     */
    suspend fun checkForUpdate(
        currentVersionName: String,
        currentVersionCode: Int
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        val release = fetchLatestRelease()

        val versionName = UpdateMetadata.versionNameFromTag(release.tagName)
            ?: throw UpdateCheckException("无法解析版本号：${release.tagName}")

        val apkAsset = release.assets.firstOrNull {
            it.name.startsWith(UpdateConfig.ASSET_PREFIX) && it.name.endsWith(APK_SUFFIX)
        } ?: release.assets.firstOrNull { it.name.endsWith(APK_SUFFIX) }
            ?: throw UpdateCheckException("该版本没有可用的 APK 安装包")

        requireTrustedAssetUrl(apkAsset.browserDownloadUrl)

        val sha256Url = release.assets
            .firstOrNull { it.name == apkAsset.name + SHA256_SUFFIX }
            ?.browserDownloadUrl
        sha256Url?.let(::requireTrustedAssetUrl)

        val versionCode = UpdateMetadata.versionCodeFromAssetName(apkAsset.name)
            ?: UpdateMetadata.versionCodeOf(versionName)
            ?: throw UpdateCheckException("无法解析版本号：$versionName")

        if (!UpdateMetadata.isNewer(versionName, versionCode, currentVersionName, currentVersionCode)) {
            return@withContext null
        }

        UpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            notes = release.body.orEmpty(),
            apkUrl = apkAsset.browserDownloadUrl,
            apkSize = apkAsset.size,
            sha256Url = sha256Url,
            releaseUrl = release.htmlUrl.orEmpty()
        )
    }

    private fun fetchLatestRelease(): ReleaseDto {
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Tavernite/${BuildConfig.VERSION_NAME}")
            .build()

        val body = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw UpdateCheckException(describeHttpError(response.code))
                }
                response.body?.string()
                    ?: throw UpdateCheckException("GitHub 返回了无法解析的数据")
            }
        } catch (e: IOException) {
            throw UpdateCheckException("网络请求失败，请检查网络连接", e)
        }

        return try {
            json.decodeFromString<ReleaseDto>(body)
        } catch (e: SerializationException) {
            throw UpdateCheckException("GitHub 返回了无法解析的数据", e)
        }
    }

    private fun describeHttpError(code: Int): String = when (code) {
        403 -> "GitHub API 限流，请稍后再试"
        404 -> "找不到 Release，请检查网络或仓库地址"
        else -> "GitHub API 请求失败（HTTP $code）"
    }

    private fun requireTrustedAssetUrl(url: String) {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.lowercase()
        val trusted = uri?.scheme.equals("https", ignoreCase = true) &&
            (host == "github.com" || host?.endsWith(".$GITHUB_USERCONTENT_DOMAIN") == true)
        if (!trusted) throw UpdateCheckException("安装包地址不受信任")
    }

    @Serializable
    private data class ReleaseDto(
        @SerialName("tag_name") val tagName: String,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
        val assets: List<AssetDto> = emptyList()
    )

    @Serializable
    private data class AssetDto(
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
        val size: Long = 0L
    )

    private companion object {
        const val APK_SUFFIX = ".apk"
        const val SHA256_SUFFIX = ".sha256"
        const val GITHUB_USERCONTENT_DOMAIN = "githubusercontent.com"
    }
}
