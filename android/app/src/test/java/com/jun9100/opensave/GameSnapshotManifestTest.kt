package com.jun9100.opensave

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSnapshotManifestTest {
    @Test
    fun buildsStableLocationEntriesAndRoundTripsManifest() {
        val game = DetectedGame(
            gameKey = "steam:1794680",
            presentation = GamePresentation("吸血鬼幸存者", "Vampire Survivors", "Steam AppID 1794680", GameIconKey.PC),
            saves = listOf(
                save("userdata/1794680", candidate = true),
                save("userdata/1794680", candidate = false),
                save("pc/Vampire Survivors/Save", candidate = false),
            ),
        )
        val manifest = GameSnapshotManifest.from(game)
        val file = File.createTempFile("opensave-game", ".json")
        try {
            manifest.write(file)
            val restored = GameSnapshotManifest.read(file)
            assertTrue(restored.matches(game))
            assertEquals(2, restored.locations.size)
            assertFalse(restored.locations.first { it.relativePath == "userdata/1794680" }.candidate)
        } finally {
            file.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverlappingLocations() {
        GameSnapshotManifest.from(
            DetectedGame(
                "steam:123456",
                GamePresentation(null, "Example", "Steam AppID 123456", GameIconKey.PC),
                listOf(save("game"), save("game/save")),
            ),
        )
    }

    @Test
    fun subsetSnapshotMatchesOnlyPathsStillPresentForTheSameGame() {
        val game = DetectedGame(
            "steam:1794680",
            GamePresentation("吸血鬼幸存者", "Vampire Survivors", "Steam AppID 1794680", GameIconKey.PC),
            listOf(save("userdata/1794680"), save("pc/Vampire Survivors/Save")),
        )
        val manifest = GameSnapshotManifest.from(game, listOf(game.saves.first()))

        assertTrue(manifest.matches(game))
        assertFalse(manifest.matches(game.copy(gameKey = "steam:123456")))
        assertFalse(manifest.matches(game.copy(saves = game.saves.drop(1))))
    }

    private fun save(path: String, candidate: Boolean = false) = DetectedSave(
        kind = "gamehub-pc",
        relativePath = path,
        note = "test",
        displayName = path,
        gameKey = "steam:1794680",
        candidate = candidate,
    )
}
