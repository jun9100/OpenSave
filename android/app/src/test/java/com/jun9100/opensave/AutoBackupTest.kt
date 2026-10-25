package com.jun9100.opensave

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupTest {
    @Test
    fun automaticBackupUsesOnlyConfirmedNonAndroidLocations() {
        val scan = SaveScanReport(
            rootPath = "/authorized",
            scannedDirectoryCount = 4,
            detected = listOf(
                DetectedSave(
                    kind = "gamehub-pc",
                    relativePath = "Games/Example/Save",
                    note = "identified",
                    displayName = "Example",
                    gameKey = "pc:example",
                ),
                DetectedSave(
                    kind = "gamehub-pc",
                    relativePath = "Games/Unknown/Save",
                    note = "candidate",
                    displayName = "Unknown",
                    gameKey = "pc:unknown",
                    candidate = true,
                ),
                DetectedSave(
                    kind = "android-app",
                    relativePath = "Android/data/com.example.game",
                    note = "app data",
                    displayName = "com.example.game",
                    gameKey = "android:com.example.game",
                ),
                DetectedSave(
                    kind = "switch-title",
                    relativePath = ".",
                    note = "selected title root",
                    displayName = "Switch 0100000000000001",
                    gameKey = "switch:0100000000000001",
                ),
            ),
        )

        val games = AutoBackupPolicy.eligibleGames(scan)

        assertEquals(listOf("pc:example"), games.map { it.gameKey })
        assertTrue(games.single().saves.all { !it.candidate })
    }

    @Test
    fun fingerprintIsStableForUnchangedFilesAndChangesWithContents() {
        val root = createTempDir(prefix = "opensave-auto-backup-")
        try {
            val location = File(root, "location-0000").apply { mkdirs() }
            val save = File(location, "save.dat").apply { writeText("before") }
            File(root, GameSnapshotManifest.FILE_NAME).writeText("manifest")

            val initial = AutoBackupPolicy.fingerprint(root)
            save.setLastModified(1L)
            val sameContents = AutoBackupPolicy.fingerprint(root)
            save.writeText("after")
            val changedContents = AutoBackupPolicy.fingerprint(root)

            assertEquals(1, initial.dataFileCount)
            assertEquals(initial.manifestHash, sameContents.manifestHash)
            assertNotEquals(initial.manifestHash, changedContents.manifestHash)
        } finally {
            root.deleteRecursively()
        }
    }
}
