package com.stmobile.core.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.stmobile.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Launches the system package installer for a downloaded APK and manages the
 * "install unknown apps" permission flow.
 */
@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Whether this app may currently request installation of unknown packages. */
    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    /**
     * Opens the system screen that grants "install unknown apps" for this app,
     * falling back to the app details settings when the dedicated screen is
     * unavailable. Returns silently when neither screen can be opened.
     */
    fun openUnknownSourcesSettings() {
        val packageUri = Uri.parse("package:${context.packageName}")

        val unknownSources = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (startActivity(unknownSources)) return

        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(appDetails)
    }

    /**
     * Opens the package installer for [apk].
     *
     * @return false when the file is missing, cannot be exposed through the
     * FileProvider, or no activity can handle the install intent.
     */
    fun install(apk: File): Boolean {
        if (!apk.exists()) return false

        val uri = try {
            FileProvider.getUriForFile(context, AUTHORITY, apk)
        } catch (e: IllegalArgumentException) {
            return false
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return startActivity(intent)
    }

    private fun startActivity(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private companion object {
        val AUTHORITY = "${BuildConfig.APPLICATION_ID}.fileprovider"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
