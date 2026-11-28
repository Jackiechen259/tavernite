package com.stmobile.core.update

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256Url: String?,
    val releaseUrl: String
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val bytes: Long, val total: Long) : UpdateState
    data class ReadyToInstall(
        val info: UpdateInfo,
        val apkPath: String,
        val needsPermission: Boolean
    ) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

sealed interface UpdateCheckOutcome {
    data class Found(val info: UpdateInfo) : UpdateCheckOutcome
    data object UpToDate : UpdateCheckOutcome
    data class Failure(val message: String) : UpdateCheckOutcome
}

class UpdateCheckException(message: String, cause: Throwable? = null) : Exception(message, cause)

class UpdateDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
