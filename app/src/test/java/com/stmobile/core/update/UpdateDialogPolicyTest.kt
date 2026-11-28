package com.stmobile.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDialogPolicyTest {

    private val info = UpdateInfo(
        versionName = "0.2.0",
        versionCode = 200,
        notes = "",
        apkUrl = "https://github.com/x/y/releases/download/v0.2.0/a.apk",
        apkSize = 1,
        sha256Url = null,
        releaseUrl = "https://github.com/x/y/releases/tag/v0.2.0"
    )

    @Test
    fun `shows available update until dismissed`() {
        assertTrue(UpdateDialogPolicy.shouldShow(UpdateState.Available(info), null))
        assertFalse(UpdateDialogPolicy.shouldShow(UpdateState.Available(info), "0.2.0"))
        assertTrue(UpdateDialogPolicy.shouldShow(UpdateState.Available(info), "0.1.9"))
    }

    @Test
    fun `downloading always shows`() {
        assertTrue(
            UpdateDialogPolicy.shouldShow(
                UpdateState.Downloading(info, 1, 2),
                "0.2.0"
            )
        )
    }

    @Test
    fun `ready to install respects dismissal`() {
        assertTrue(
            UpdateDialogPolicy.shouldShow(
                UpdateState.ReadyToInstall(info, "/tmp/a.apk", false),
                null
            )
        )
        assertFalse(
            UpdateDialogPolicy.shouldShow(
                UpdateState.ReadyToInstall(info, "/tmp/a.apk", true),
                "0.2.0"
            )
        )
    }

    @Test
    fun `failed without info never shows`() {
        assertFalse(UpdateDialogPolicy.shouldShow(UpdateState.Failed("boom"), null))
        assertTrue(UpdateDialogPolicy.shouldShow(UpdateState.Failed("boom", info), null))
        assertFalse(UpdateDialogPolicy.shouldShow(UpdateState.Failed("boom", info), "0.2.0"))
    }

    @Test
    fun `passive states never show`() {
        assertFalse(UpdateDialogPolicy.shouldShow(UpdateState.Idle, null))
        assertFalse(UpdateDialogPolicy.shouldShow(UpdateState.Checking, null))
        assertFalse(UpdateDialogPolicy.shouldShow(UpdateState.UpToDate, null))
    }
}
