package com.stmobile.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.stmobile.BuildConfig
import com.stmobile.core.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-wide update state machine: checks for releases, downloads and
 * installs APKs. All UI surfaces observe the single [state] flow.
 *
 * Debug builds never check, download or install; [enabled] is false there.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: GitHubReleaseClient,
    private val downloader: UpdateDownloader,
    private val installer: UpdateInstaller,
    private val settings: SettingsStore
) {

    /** Auto-update is a release-only feature. */
    val enabled: Boolean = !BuildConfig.DEBUG

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _dismissedVersion = MutableStateFlow<String?>(null)
    val dismissedVersion: StateFlow<String?> = _dismissedVersion.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()

    @Volatile
    private var downloadJob: Job? = null

    /**
     * Runs the automatic check when it is due: at most once per
     * [UpdateConfig.CHECK_INTERVAL_MS], only while auto-check is enabled.
     * Failures (settings, network) are swallowed so startup is never affected.
     */
    suspend fun autoCheckIfDue() {
        if (!enabled) return

        try {
            if (!settings.autoUpdateCheck.first()) return
            val lastCheckAt = settings.lastUpdateCheckAt.first()
            if (System.currentTimeMillis() - lastCheckAt < UpdateConfig.CHECK_INTERVAL_MS) return
            checkInternal(manual = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Automatic checks must stay silent.
        }
    }

    /** Checks immediately, ignoring the throttle and the skipped version. */
    suspend fun checkManually(): UpdateCheckOutcome =
        if (!enabled) {
            UpdateCheckOutcome.Failure("调试构建不参与自动更新")
        } else {
            checkInternal(manual = true)
        }

    private suspend fun checkInternal(manual: Boolean): UpdateCheckOutcome =
        checkMutex.withLock {
            _state.value = UpdateState.Checking

            try {
                val info = client.checkForUpdate(
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE
                )
                when {
                    info == null -> {
                        _state.value = UpdateState.UpToDate
                        UpdateCheckOutcome.UpToDate
                    }
                    !manual && settings.skippedUpdateVersion.first() == info.versionName -> {
                        _state.value = UpdateState.Idle
                        UpdateCheckOutcome.UpToDate
                    }
                    else -> {
                        _dismissedVersion.value = null
                        _state.value = UpdateState.Available(info)
                        UpdateCheckOutcome.Found(info)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message?.takeIf { it.isNotBlank() } ?: "检查更新失败"
                _state.value = UpdateState.Failed(message)
                UpdateCheckOutcome.Failure(message)
            } finally {
                // Record the attempt even when it failed, so offline devices do
                // not hit the network on every launch. Best effort only.
                runCatching { settings.setLastUpdateCheckAt(System.currentTimeMillis()) }
            }
        }

    /**
     * Starts downloading the available update, if any. Runs on a manager-owned
     * scope so navigation cannot cancel an in-flight download.
     */
    fun download() {
        val info = UpdateDialogPolicy.updateInfo(_state.value) ?: return
        if (downloadJob?.isActive == true) return

        _state.value = UpdateState.Downloading(info, bytes = 0, total = info.apkSize)
        downloadJob = scope.launch {
            try {
                val apk = downloader.download(
                    info = info,
                    targetDir = File(context.cacheDir, "updates")
                ) { bytes, total ->
                    _state.value = UpdateState.Downloading(info, bytes, total)
                }
                _state.value = UpdateState.ReadyToInstall(
                    info = info,
                    apkPath = apk.absolutePath,
                    needsPermission = !installer.canInstallPackages()
                )
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(info)
                throw e
            } catch (e: Exception) {
                val message = e.message?.takeIf { it.isNotBlank() } ?: "下载失败"
                _state.value = UpdateState.Failed(message, info)
            } finally {
                downloadJob = null
            }
        }
    }

    /** Cancels the active download and resets to [UpdateState.Available]. */
    fun cancelDownload() {
        val job = downloadJob ?: return
        job.cancel()
        // A coroutine cancelled before it is dispatched never runs its
        // cancellation branch, so reset the state here as well.
        val downloading = _state.value as? UpdateState.Downloading ?: return
        _state.value = UpdateState.Available(downloading.info)
    }

    /** Launches the system installer, requesting the unknown-sources permission first if needed. */
    fun install() {
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        val apk = File(ready.apkPath)

        if (!apk.exists()) {
            _state.value = UpdateState.Available(ready.info)
            return
        }

        if (!installer.canInstallPackages()) {
            _state.value = ready.copy(needsPermission = true)
            installer.openUnknownSourcesSettings()
            return
        }

        if (!installer.install(apk)) {
            _state.value = UpdateState.Failed("无法启动系统安装器", ready.info)
        }
    }

    /** Persists the skipped version, closes the dialog and resets to [UpdateState.Idle]. */
    fun skipVersion(versionName: String) {
        scope.launch { settings.setSkippedUpdateVersion(versionName) }
        _state.value = UpdateState.Idle
        _dismissedVersion.value = null
    }

    /** Hides the dialog for the current update version until the process restarts. */
    fun dismissDialog() {
        _dismissedVersion.value = UpdateDialogPolicy.updateInfo(_state.value)?.versionName
    }

    /** Makes the dialog visible again, e.g. when opened from the "about" section. */
    fun showDialog() {
        _dismissedVersion.value = null
    }

    /** Opens the GitHub Releases page in a browser as a fallback. */
    fun openReleasePage() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(UpdateConfig.RELEASES_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Persists the "check automatically on startup" preference. */
    fun setAutoUpdateCheck(enabled: Boolean) {
        scope.launch { settings.setAutoUpdateCheck(enabled) }
    }
}
