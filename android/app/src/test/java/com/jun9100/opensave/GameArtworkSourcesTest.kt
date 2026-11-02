package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameArtworkSourcesTest {
    private val search = """
        {"total":4,"items":[
          {"type":"app","name":"Half-Life 2","id":220},
          {"type":"app","name":"Half-Life","id":70},
          {"type":"app","name":"Half-Life: Alyx","id":546560},
          {"type":"sub","name":"Beholder","id":9}
        ]}
    """.trimIndent()

    @Test
    fun onlyAnExactNameMatchGivesASteamAppId() {
        assertEquals("70", GameArtworkSources.steamAppIdForExactName(search, "Half-Life"))
        assertEquals("220", GameArtworkSources.steamAppIdForExactName(search, "half life 2™"))
        // Fuzzy neighbours and non-app results never count.
        assertNull(GameArtworkSources.steamAppIdForExactName(search, "Half-Life 3"))
        assertNull(GameArtworkSources.steamAppIdForExactName(search, "Beholder"))
        assertNull(GameArtworkSources.steamAppIdForExactName("not json", "Half-Life"))
    }

    @Test
    fun emulatorFoldersUseTheEmulatorsIcon() {
        assertEquals("com.retroarch", GameArtworkSources.emulatorPackages("retroarch").first())
        assertEquals(emptyList<String>(), GameArtworkSources.emulatorPackages("gamehub-pc"))
    }

    @Test
    fun pspGamesAndNamedPcGamesCarryTheirArtworkHints() {
        val psp = GameCatalog.present(
            DetectedSave(kind = "ppsspp", relativePath = "PSP/SAVEDATA/ULUS10041DATA", note = "", displayName = "PSP ULUS10041", gameKey = "ppsspp:ULUS10041"),
        )
        assertEquals("ULUS10041", psp.pspGameId)
        assertEquals("ppsspp-ulus10041", psp.coverCacheKey)
        assertEquals(listOf("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold"), psp.emulatorPackages)

        val pc = GameCatalog.present(
            DetectedSave(kind = "gamehub-pc", relativePath = "drive_c/Half-Life/save", note = "", displayName = "Half-Life", gameKey = "pc:half-life"),
        )
        assertEquals("Half-Life", pc.steamSearchName)
        assertNull(pc.coverCacheKey)
    }
}

class ScanPruningTest {
    @Test
    fun cachesAndLinuxSystemFoldersAreNotWalked() {
        org.junit.Assert.assertTrue(ScanPruning.skip("files", "cache"))
        org.junit.Assert.assertTrue(ScanPruning.skip("linuxfs", "usr"))
        // A save folder named like a system one elsewhere, and the Linux home, are still walked.
        org.junit.Assert.assertFalse(ScanPruning.skip("Game", "lib"))
        org.junit.Assert.assertFalse(ScanPruning.skip("linuxfs", "root"))
    }
}
