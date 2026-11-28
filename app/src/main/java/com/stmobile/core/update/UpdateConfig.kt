package com.stmobile.core.update

object UpdateConfig {
    const val OWNER = "Jackiechen259"
    const val REPO = "tavernite"
    const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    const val RELEASES_URL = "https://github.com/$OWNER/$REPO/releases"
    const val ASSET_PREFIX = "Tavernite-"
    const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
}
