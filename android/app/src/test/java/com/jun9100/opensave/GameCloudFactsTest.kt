package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCloudFactsTest {
    private fun game(save: DetectedSave) = GameLibrary.group(listOf(save)).single()

    @Test
    fun switchPspAndAndroidGamesReportTheirIdentifiers() {
        val switch = GameCloudFacts.of(game(DetectedSave(
            kind = "yuzu-switch", relativePath = "nand/user/save/0/1/0100000000010000", titleId = "0100000000010000",
            note = "", displayName = "Switch 0100000000010000", gameKey = "switch:0100000000010000",
        )))
        assertEquals("0100000000010000", switch.titleId)
        assertTrue(switch.name.isNotBlank())

        val psp = GameCloudFacts.of(game(DetectedSave(
            kind = "ppsspp", relativePath = "PSP/SAVEDATA/ULUS10041DATA", note = "", displayName = "PSP ULUS10041", gameKey = "ppsspp:ULUS10041",
        )))
        assertEquals("ULUS10041", psp.pspGameId)

        val android = GameCloudFacts.of(game(DetectedSave(
            kind = "android-game", relativePath = "files", note = "", displayName = "com.poncle.vampiresurvivors",
            gameKey = "android:com.poncle.vampiresurvivors",
        )))
        assertEquals("com.poncle.vampiresurvivors", android.androidPackage)
    }

    @Test
    fun anUnnamedGameReportsNoName() {
        val unnamed = GameCloudFacts.of(game(DetectedSave(
            kind = "yuzu-switch", relativePath = "nand/user/save/0/1/01000000000BEEF0", titleId = "01000000000BEEF0",
            note = "", displayName = "Switch 01000000000BEEF0", gameKey = "switch:01000000000BEEF0",
        )))
        assertEquals("", unnamed.name)
        assertEquals("01000000000BEEF0", unnamed.titleId)
        assertTrue(GameCloudFacts().isEmpty)
    }
}
