package com.stmobile.core.update

import com.stmobile.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Downloads a release APK into [targetDir], streaming the response to disk
 * while computing its SHA-256 digest.
 *
 * The download is staged as `<target>.part` and only renamed to its final
 * name after the checksum (when [UpdateInfo.sha256Url] is present) has been
 * verified. Any failure removes both the staged file and the target.
 */
class UpdateDownloader @Inject constructor(private val client: OkHttpClient) {

    /**
     * Downloads [info] into [targetDir] and returns the verified APK file.
     *
     * @throws UpdateDownloadException when the request fails, the checksum
     * cannot be fetched or does not match, or the file cannot be saved.
     */
    suspend fun download(
        info: UpdateInfo,
        targetDir: File,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw UpdateDownloadException("无法创建下载目录")
        }
        targetDir.listFiles()?.forEach { it.deleteRecursively() }

        val target = File(targetDir, "Tavernite-${info.versionName}-${info.versionCode}.apk")
        val part = File(targetDir, "${target.name}.part")

        try {
            val actualSha256 = downloadApk(info, part, onProgress)
            info.sha256Url?.let { shaUrl ->
                val expectedSha256 = fetchExpectedSha256(shaUrl)
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    throw UpdateDownloadException("安装包校验失败")
                }
            }
            currentCoroutineContext().ensureActive()
            if (!part.renameTo(target)) {
                throw UpdateDownloadException("无法保存安装包")
            }
            target
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: UpdateDownloadException) {
            part.delete()
            target.delete()
            throw e
        } catch (e: IOException) {
            part.delete()
            target.delete()
            throw UpdateDownloadException("下载失败，请检查网络连接", e)
        } catch (e: Exception) {
            part.delete()
            target.delete()
            throw UpdateDownloadException("下载失败", e)
        }
    }

    private suspend fun downloadApk(
        info: UpdateInfo,
        part: File,
        onProgress: (bytes: Long, total: Long) -> Unit
    ): String {
        val request = Request.Builder()
            .url(info.apkUrl)
            .header("User-Agent", "Tavernite/${BuildConfig.VERSION_NAME}")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UpdateDownloadException("下载失败（HTTP ${response.code}）")
            }
            val body = response.body ?: throw UpdateDownloadException("下载失败（响应为空）")
            val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L

            FileOutputStream(part).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }

            if (total > 0 && written != total) {
                part.delete()
                throw UpdateDownloadException("安装包下载不完整")
            }

            return digest.digest().toHex()
        }
    }

    private fun fetchExpectedSha256(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Tavernite/${BuildConfig.VERSION_NAME}")
            .build()

        val text = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw UpdateDownloadException("无法获取安装包校验文件")
                }
                response.body?.string() ?: throw UpdateDownloadException("无法获取安装包校验文件")
            }
        } catch (e: UpdateDownloadException) {
            throw e
        } catch (e: IOException) {
            throw UpdateDownloadException("无法获取安装包校验文件", e)
        }

        return SHA256_PATTERN.find(text)?.value
            ?: throw UpdateDownloadException("无法获取安装包校验文件")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
        const val BUFFER_SIZE = 8 * 1024
    }
}
