package com.stmobile.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateMetadataTest {

    @Test
    fun `strips v prefix and validates tag`() {
        assertEquals("0.2.0", UpdateMetadata.versionNameFromTag("v0.2.0"))
        assertEquals("0.2.0", UpdateMetadata.versionNameFromTag("0.2.0"))
        assertNull(UpdateMetadata.versionNameFromTag("v0.2"))
        assertNull(UpdateMetadata.versionNameFromTag("v0.2.0-beta1"))
    }

    @Test
    fun `derives version code from semver`() {
        assertEquals(100, UpdateMetadata.versionCodeOf("0.1.0"))
        assertEquals(200, UpdateMetadata.versionCodeOf("0.2.0"))
        assertEquals(10203, UpdateMetadata.versionCodeOf("1.2.3"))
    }

    @Test
    fun `rejects version components at or above one hundred`() {
        assertNull(UpdateMetadata.versionCodeOf("1.100.0"))
        assertNull(UpdateMetadata.versionCodeOf("1.2.100"))
        assertNull(UpdateMetadata.versionCodeOf("nope"))
    }

    @Test
    fun `extracts version code from asset name`() {
        assertEquals(200, UpdateMetadata.versionCodeFromAssetName("Tavernite-0.2.0-200.apk"))
        assertNull(UpdateMetadata.versionCodeFromAssetName("Tavernite-0.2.0-200.apk.sha256"))
        assertNull(UpdateMetadata.versionCodeFromAssetName("app-release.apk"))
    }

    @Test
    fun `compares semantic versions numerically`() {
        assertEquals(1, UpdateMetadata.compareVersions("0.10.0", "0.9.9"))
        assertEquals(0, UpdateMetadata.compareVersions("1.2.3", "1.2.3"))
        assertEquals(-1, UpdateMetadata.compareVersions("1.2.2", "1.2.3"))
    }

    @Test
    fun `treats equal or older versions as not newer`() {
        assertFalse(UpdateMetadata.isNewer("0.2.0", 200, "0.2.0", 200))
        assertFalse(UpdateMetadata.isNewer("0.1.9", 199, "0.2.0", 200))
        assertTrue(UpdateMetadata.isNewer("0.2.1", 201, "0.2.0", 200))
    }

    @Test
    fun `falls back to semantic comparison without asset code`() {
        assertTrue(UpdateMetadata.isNewer("0.2.1", null, "0.2.0", 200))
        assertFalse(UpdateMetadata.isNewer("0.1.9", null, "0.2.0", 200))
    }
}
