package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAccessTest {
    @Test
    fun rootSourceDeclaresRootAsItsOnlyAccessPath() {
        val source = SourceSelection.Root("/data/user/0/dev.eden.eden_emulator/files")

        assertEquals(SourceAccessCapability.ROOT, source.requiredAccessCapability())
        val status = SourceAccessStatus.unavailable(SourceAccessCapability.ROOT, "Root 未授权")
        assertEquals(SourceAccessCapability.UNAVAILABLE, status.capability)
        assertEquals(SourceAccessCapability.ROOT, status.requiredCapability)
        assertFalse(status.readable)
        assertFalse(status.writable)
    }

    @Test
    fun successfulSafAndRootStatesKeepTheirRestoreCapabilitiesSeparate() {
        val readOnlySaf = SourceAccessStatus.safAccessible(writable = false)
        val root = SourceAccessStatus.rootOnly()

        assertEquals(SourceAccessCapability.SAF, readOnlySaf.capability)
        assertTrue(readOnlySaf.readable)
        assertFalse(readOnlySaf.writable)
        assertEquals(SourceAccessCapability.ROOT, root.capability)
        assertTrue(root.readable)
        assertTrue(root.writable)
    }

    @Test
    fun sourceOptionsExposeTheirRequiredAccessBeforeScanning() {
        val preset = RootSourcePreset("Eden test", "/data/user/0/example.eden/files")
        val option = SaveSourceOptions.build(
            authorizedTrees = emptyList(),
            rootPath = null,
            disabledSourceIds = emptySet(),
            rootPresets = listOf(preset),
        ).single()

        assertEquals(SourceAccessCapability.ROOT, option.requiredAccess)
        assertTrue(option.selected)
    }
}
