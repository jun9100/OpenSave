package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootSourcePresetsTest {
    @Test
    fun providesCommonAndroidGameAndEmulatorRoots() {
        assertEquals(
            "GameHub PC",
            RootSourcePresets.find("/data/user/0/com.xiaoji.egggame/files")?.label,
        )
        assertEquals(
            "Eden Switch",
            RootSourcePresets.find("/data/user/0/dev.eden.eden_emulator/files/")?.label,
        )
        assertEquals(
            "Android 应用数据",
            RootSourcePresets.find("/storage/emulated/0/Android/data")?.label,
        )
        assertEquals(
            "Sudachi",
            RootSourcePresets.find("/data/user/0/org.sudachi.sudachi_emu/files")?.label,
        )
        assertNull(RootSourcePresets.find("/data/user/0/custom.emulator/files"))
    }

    @Test
    fun sourceOptionsDefaultToAllSelectedAndKeepCategoryMetadata() {
        val options = SaveSourceOptions.build(null, null, emptySet())

        assertTrue(options.all(SaveSourceOption::selected))
        assertEquals(
            GameSaveTypes.PC,
            options.first { it.label == "GameHub PC" }.saveType,
        )
        assertEquals(
            GameSaveTypes.SWITCH,
            options.first { it.label == "Eden Switch" }.saveType,
        )
    }

    @Test
    fun sourceOptionsPersistDisabledRootsWithoutRemovingTheirChoice() {
        val eden = SourceSelection.Root("/data/user/0/dev.eden.eden_emulator/files")
        val options = SaveSourceOptions.build(null, null, setOf(eden.idSeed))

        assertFalse(options.first { it.label == "Eden Switch" }.selected)
        assertTrue(options.any { it.label == "GameHub PC" && it.selected })
    }
}
