package com.stmobile.core.update

object UpdateMetadata {

    private val SEMVER = Regex("""^(\d+)\.(\d+)\.(\d+)$""")
    private val ASSET_VERSION_CODE = Regex("""-([0-9]+)\.apk$""")

    /**
     * Strips one leading `v`/`V` from [tag] and returns the resulting
     * `MAJOR.MINOR.PATCH` version name, or null if it is not valid semver.
     */
    fun versionNameFromTag(tag: String): String? {
        val stripped = tag.trim().removePrefix("v").removePrefix("V")
        return stripped.takeIf { SEMVER.matches(it) }
    }

    /**
     * Converts `MAJOR.MINOR.PATCH` to `MAJOR * 10000 + MINOR * 100 + PATCH`.
     * Returns null when the input is not valid semver, MINOR or PATCH is
     * 100 or greater, or the result does not fit in an Int.
     */
    fun versionCodeOf(versionName: String): Int? {
        val (major, minor, patch) = parseComponents(versionName) ?: return null
        if (minor >= 100 || patch >= 100) return null
        val code = major.toLong() * 10_000L + minor * 100L + patch
        return code.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
    }

    /**
     * Compares two version names numerically. Returns 0 when either side is
     * not a valid `MAJOR.MINOR.PATCH` version (with optional `v` prefix).
     */
    fun compareVersions(a: String, b: String): Int {
        val (leftMajor, leftMinor, leftPatch) = versionNameFromTag(a)?.let(::parseComponents) ?: return 0
        val (rightMajor, rightMinor, rightPatch) = versionNameFromTag(b)?.let(::parseComponents) ?: return 0
        if (leftMajor != rightMajor) return if (leftMajor > rightMajor) 1 else -1
        if (leftMinor != rightMinor) return if (leftMinor > rightMinor) 1 else -1
        if (leftPatch != rightPatch) return if (leftPatch > rightPatch) 1 else -1
        return 0
    }

    /**
     * Extracts the trailing `-<versionCode>.apk` number from an asset name,
     * or null when the asset name does not carry one.
     */
    fun versionCodeFromAssetName(assetName: String): Int? =
        ASSET_VERSION_CODE.find(assetName)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Returns true when the remote release is newer than the current install.
     * Prefers the asset versionCode when available, falling back to semantic
     * comparison of version names.
     */
    fun isNewer(
        remoteVersionName: String,
        remoteVersionCode: Int?,
        currentVersionName: String,
        currentVersionCode: Int
    ): Boolean =
        if (remoteVersionCode != null) {
            remoteVersionCode > currentVersionCode
        } else {
            compareVersions(remoteVersionName, currentVersionName) > 0
        }

    private fun parseComponents(versionName: String): Triple<Int, Int, Int>? {
        val match = SEMVER.matchEntire(versionName.trim()) ?: return null
        val (major, minor, patch) = match.destructured
        return Triple(
            major.toIntOrNull() ?: return null,
            minor.toIntOrNull() ?: return null,
            patch.toIntOrNull() ?: return null
        )
    }
}
