package com.stmobile.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.stmobile.BuildConfig
import com.stmobile.core.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
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

    private val downloadLock = Any()
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
            val previous = _state.value
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
                // The owning ViewModel scope may be cancelled while the request
                // is in flight; do not leave the shared state stuck in Checking.
                if (_state.value == UpdateState.Checking) _state.value = previous
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
     *
     * The [downloadJob] reference is authoritative: while it is non-null no
     * second download may start, even if the previous job has been cancelled
     * but has not observed the cancellation yet. The job itself is started
     * atomically so its cleanup always runs, even when cancelled before the
     * dispatcher picks it up.
     */
    fun download() {
        val info = UpdateDialogPolicy.updateInfo(_state.value) ?: return

        synchronized(downloadLock) {
            if (downloadJob != null) return

            _state.value = UpdateState.Downloading(info, bytes = 0, total = info.apkSize)
            downloadJob = scope.launch(start = CoroutineStart.ATOMIC) {
                val self = currentCoroutineContext()[Job]!!
                try {
                    val apk = downloader.download(
                        info = info,
                        targetDir = File(context.cacheDir, "updates")
                    ) { bytes, total ->
                        if (isCurrentJob(self)) {
                            _state.value = UpdateState.Downloading(info, bytes, total)
                        }
                    }
                    if (isCurrentJob(self)) {
                        _state.value = UpdateState.ReadyToInstall(
                            info = info,
                            apkPath = apk.absolutePath,
                            needsPermission = !installer.canInstallPackages()
                        )
                    }
                } catch (e: CancellationException) {
                    if (isCurrentJob(self)) _state.value = UpdateState.Available(info)
                    throw e
                } catch (e: Exception) {
                    if (isCurrentJob(self)) {
                        val message = e.message?.takeIf { it.isNotBlank() } ?: "下载失败"
                        _state.value = UpdateState.Failed(message, info)
                    }
                } finally {
                    synchronized(downloadLock) {
                        if (downloadJob === self) downloadJob = null
                    }
                }
            }
        }
    }

    /**
     * Cancels the active download, if any. The owning job resets the state and
     * clears [downloadJob] when it unwinds; until then new downloads are
     * refused, so a still-blocked request can never race a fresh download.
     */
    fun cancelDownload() {
        val job = synchronized(downloadLock) { downloadJob } ?: return
        job.cancel()
    }

    private fun isCurrentJob(job: Job): Boolean =
        synchronized(downloadLock) { downloadJob === job }

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
        scope.launch { runCatching { settings.setSkippedUpdateVersion(versionName) } }
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
        scope.launch { runCatching { settings.setAutoUpdateCheck(enabled) } }
    }
}
