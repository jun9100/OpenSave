package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSaveTypesTest {
    @Test
    fun classifiesPcSwitchAndEachEmulatorFamily() {
        assertEquals(GameSaveTypes.PC, GameSaveTypes.forSave(DetectedSave("gamehub-pc", "save", note = "pc")))
        assertEquals(GameSaveTypes.SWITCH, GameSaveTypes.forSave(DetectedSave("yuzu-switch", "save", note = "switch")))
        assertEquals(GameSaveTypes.PPSSPP, GameSaveTypes.forSave(DetectedSave("ppsspp", "save", note = "psp")))
        assertEquals(GameSaveTypes.RETROARCH, GameSaveTypes.forSave(DetectedSave("retroarch", "save", note = "retro")))
        assertEquals(GameSaveTypes.DOLPHIN, GameSaveTypes.forSave(DetectedSave("dolphin", "save", note = "dolphin")))
        assertEquals(GameSaveTypes.GENERIC, GameSaveTypes.forSave(DetectedSave("emulator", "save", note = "generic")))
    }

    @Test
    fun validServiceTypesRemainExtensibleButRejectUnsafeNames() {
        assertTrue(GameSaveTypes.isValid("emulator-duckstation"))
        assertTrue(GameSaveTypes.isValid("emulator-aethersx2"))
        assertFalse(GameSaveTypes.isValid("emulator-../../outside"))
        assertFalse(GameSaveTypes.isValid("pc/other"))
    }

    @Test
    fun singleSwitchTitleUsesStableDesktopCompatibleCloudName() {
        val sourceId = "android-source"
        val game = DetectedGame(
            gameKey = "switch:0100000000010000",
            presentation = GameCatalog.present(
                DetectedSave(
                    kind = "yuzu-switch",
                    relativePath = "nand/user/save/0000000000000000/0100000000010000",
                    titleId = "0100000000010000",
                    note = "switch",
                    gameKey = "switch:0100000000010000",
                ),
            ),
            saves = listOf(
                DetectedSave(
                    kind = "yuzu-switch",
                    relativePath = "nand/user/save/0000000000000000/0100000000010000",
                    titleId = "0100000000010000",
                    note = "switch",
                    gameKey = "switch:0100000000010000",
                ),
            ),
        )

        val identity = GameCloudNaming.identity(sourceId, game)

        assertTrue(identity.desktopCompatible)
        assertEquals(GameSaveTypes.SWITCH, identity.saveType)
        assertEquals("switch-0100000000010000", identity.gameId)
        assertEquals("switch-0100000000010000__main__snap_7.zip", GameCloudNaming.remoteName(identity, 7))
    }

    @Test
    fun multiLocationOrPcGamesStayInAndroidSpecificNamespace() {
        val game = DetectedGame(
            gameKey = "pc:example",
            presentation = GameCatalog.present(
                DetectedSave("gamehub-pc", "pc/Example/Save", note = "pc", gameKey = "pc:example"),
            ),
            saves = listOf(DetectedSave("gamehub-pc", "pc/Example/Save", note = "pc", gameKey = "pc:example")),
        )

        val identity = GameCloudNaming.identity("android-source", game)

        assertFalse(identity.desktopCompatible)
        assertEquals(GameSaveTypes.PC, identity.saveType)
        assertTrue(identity.gameId.startsWith("android-pc-"))
    }
}
