package com.stmobile.core.update

object UpdateDialogPolicy {

    fun updateInfo(state: UpdateState): UpdateInfo? = when (state) {
        is UpdateState.Available -> state.info
        is UpdateState.Downloading -> state.info
        is UpdateState.ReadyToInstall -> state.info
        is UpdateState.Failed -> state.info
        else -> null
    }

    fun shouldShow(state: UpdateState, dismissedVersion: String?): Boolean {
        val info = updateInfo(state) ?: return false
        if (state is UpdateState.Downloading) return true
        return dismissedVersion != info.versionName
    }
}
