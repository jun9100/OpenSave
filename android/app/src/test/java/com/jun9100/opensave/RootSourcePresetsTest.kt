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
            "GameHub PC · 私有目录",
            RootSourcePresets.find("/data/user/0/com.xiaoji.egggame/files")?.label,
        )
        assertEquals(
            "Eden Switch · 私有目录",
            RootSourcePresets.find("/data/user/0/dev.eden.eden_emulator/files/")?.label,
        )
        assertEquals(
            "Eden Switch · Android/data",
            RootSourcePresets.find("/storage/emulated/0/Android/data/dev.eden.eden_emulator/files")?.label,
        )
        assertEquals(
            "Eden Switch · 公共目录 eden",
            RootSourcePresets.find("/storage/emulated/0/eden")?.label,
        )
        assertEquals(
            "PC 游戏 · 公共目录 pc-game",
            RootSourcePresets.find("/storage/emulated/0/pc-game")?.label,
        )
        assertEquals(
            "DroidDeck PC · 私有目录",
            RootSourcePresets.find("/data/user/0/com.droiddeck.launcher/files")?.label,
        )
        assertEquals(
            "Winlator 6.1 · 私有目录",
            RootSourcePresets.find("/data/user/0/com.ludashi.benchmark/files")?.label,
        )
        assertEquals(
            "Sudachi",
            RootSourcePresets.find("/data/user/0/org.sudachi.sudachi_emu/files")?.label,
        )
        assertEquals(
            "AetherSX2 / NetherSX2",
            RootSourcePresets.find("/storage/emulated/0/AetherSX2")?.label,
        )
        assertNull(RootSourcePresets.find("/data/user/0/custom.emulator/files"))
    }

    @Test
    fun edenSwitch2ForksAreSwitchSourcesFoldedUnderTheirOwnApp() {
        val eglantine = RootSourcePresets.find("/data/user/0/dev.eglantine.eglantine/files")
        assertEquals("Eglantine Switch 2 · 私有目录", eglantine?.label)
        assertEquals("dev.eglantine.eglantine", eglantine?.packageName)
        assertEquals("Eglantine Switch 2", eglantine?.app)
        val sx2a = RootSourcePresets.find("/storage/emulated/0/Android/data/dev.eden.nx2/files")
        assertEquals("Eden SX2A Switch 2 · Android/data", sx2a?.label)
        assertEquals("dev.eden.nx2", sx2a?.packageName)

        for (path in listOf(
            "/data/user/0/dev.eglantine.eglantine/files",
            "/storage/emulated/0/Android/data/dev.eglantine.eglantine/files",
            "/data/user/0/dev.eden.nx2/files",
            "/storage/emulated/0/Android/data/dev.eden.nx2/files",
        )) {
            assertEquals(path, GameSaveTypes.SWITCH, GameSaveTypes.forSource(SourceSelection.Root(path)))
        }
    }

    @Test
    fun sourceOptionsDefaultToAllSelectedAndKeepCategoryMetadata() {
        val options = SaveSourceOptions.build(emptyList(), null, emptySet())

        assertTrue(options.all(SaveSourceOption::selected))
        assertEquals(
            GameSaveTypes.PC,
            options.first { it.label == "GameHub PC · 私有目录" }.saveType,
        )
        assertEquals(
            GameSaveTypes.SWITCH,
            options.first { it.label == "Eden Switch · 私有目录" }.saveType,
        )
        assertEquals(
            GameSaveTypes.SWITCH,
            options.first { it.label == "Eden Switch · 公共目录 eden" }.saveType,
        )
        assertEquals(
            GameSaveTypes.AETHERSX2,
            options.first { it.label == "AetherSX2 / NetherSX2" }.saveType,
        )
        assertEquals(
            GameSaveTypes.PC,
            options.first { it.label == "PC 游戏 · 公共目录 pc-game" }.saveType,
        )
        assertEquals(
            GameSaveTypes.PC,
            options.first { it.label == "DroidDeck PC · 私有目录" }.saveType,
        )
    }

    @Test
    fun sourceOptionsPersistDisabledRootsWithoutRemovingTheirChoice() {
        val eden = SourceSelection.Root("/data/user/0/dev.eden.eden_emulator/files")
        val options = SaveSourceOptions.build(emptyList(), null, setOf(eden.idSeed))

        assertFalse(options.first { it.label == "Eden Switch · 私有目录" }.selected)
        assertTrue(options.any { it.label == "GameHub PC · 私有目录" && it.selected })
    }

    @Test
    fun unclassifiedApplicationSourceRequiresExplicitEnablement() {
        val preset = RootSourcePreset(
            label = "未归类应用（待确认）：Example Game",
            path = "/data/user/0/example.game",
            enabledByDefault = false,
        )
        val source = SourceSelection.Root(preset.path)

        val defaultOptions = SaveSourceOptions.build(
            authorizedTrees = emptyList(),
            rootPath = null,
            disabledSourceIds = emptySet(),
            rootPresets = listOf(preset),
        )
        val enabledOptions = SaveSourceOptions.build(
            authorizedTrees = emptyList(),
            rootPath = null,
            disabledSourceIds = emptySet(),
            explicitlyEnabledSourceIds = setOf(source.idSeed),
            rootPresets = listOf(preset),
        )

        assertFalse(defaultOptions.single().selected)
        assertTrue(enabledOptions.single().selected)
    }
}
