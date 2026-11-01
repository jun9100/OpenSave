package com.jun9100.opensave

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentHashTest {
    private val save = DetectedSave(
        kind = "gamehub-pc",
        relativePath = "Games/Example/Save",
        note = "identified",
        displayName = "Example",
        gameKey = "pc:example",
    )
    private val game = DetectedGame("pc:example", GameCatalog.present(save), listOf(save))

    @Test
    fun sameContentHashesTheSameRegardlessOfOrderMtimeAndGameName() {
        val first = createTempDir(prefix = "opensave-content-a-")
        val second = createTempDir(prefix = "opensave-content-b-")
        try {
            // Files written in a different order, with different mtimes and a
            // different display name in the manifest (as single vs batch paths do).
            writeWorkspace(first, "示例游戏", listOf("b.dat" to "two", "a.dat" to "one", "sub/c.dat" to "three"))
            writeWorkspace(second, "Example", listOf("sub/c.dat" to "three", "a.dat" to "one", "b.dat" to "two"))
            File(second, "location-0000/a.dat").setLastModified(1_000L)
            File(first, "location-0000/a.dat").setLastModified(9_000_000L)

            val a = AutoBackupPolicy.fingerprint(first)
            val b = AutoBackupPolicy.fingerprint(second)

            assertTrue(a.contentHash.isNotBlank())
            assertEquals(a.contentHash, b.contentHash)
            // The head-protocol hash still covers the manifest and is untouched.
            assertNotEquals(a.manifestHash, b.manifestHash)
        } finally {
            first.deleteRecursively()
            second.deleteRecursively()
        }
    }

    @Test
    fun changedDataChangesTheHash() {
        val root = createTempDir(prefix = "opensave-content-change-")
        try {
            writeWorkspace(root, "Example", listOf("a.dat" to "one"))
            val before = AutoBackupPolicy.fingerprint(root)
            File(root, "location-0000/a.dat").writeText("uno")
            val after = AutoBackupPolicy.fingerprint(root)
            File(root, "location-0000/a.dat").writeText("one")
            val restored = AutoBackupPolicy.fingerprint(root)

            assertNotEquals(before.contentHash, after.contentHash)
            assertEquals(before.contentHash, restored.contentHash)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun archiveSlotNamesDoNotMatterButLocationPathsDo() {
        val files = listOf(
            FileRecord("location-0000/", -1L, ""),
            FileRecord("location-0000/a.dat", 3, "h1"),
        )
        val desktopFiles = listOf(
            FileRecord("desktop-switch-save/a.dat", 3, "h1"),
            FileRecord("desktop-switch-save/", -1L, ""),
        )
        val location = GameSnapshotLocation("yuzu-switch", "nand/save/0100", "location-0000", false)

        val captured = ContentHash.of(files, listOf(location))
        val restored = ContentHash.of(desktopFiles, listOf(location.copy(archivePath = "desktop-switch-save")))
        val elsewhere = ContentHash.of(files, listOf(location.copy(relativePath = "nand/save/0200")))

        assertEquals(captured, restored)
        assertNotEquals(captured, elsewhere)
        // Distinct from the legacy algorithm over the same records.
        assertNotEquals(Hashing.manifestHash(files), captured)
    }

    @Test
    fun unreadableManifestLeavesTheContentHashBlank() {
        val root = createTempDir(prefix = "opensave-content-legacy-")
        try {
            File(root, "location-0000").mkdirs()
            File(root, "location-0000/a.dat").writeText("one")
            File(root, GameSnapshotManifest.FILE_NAME).writeText("manifest")

            assertEquals("", AutoBackupPolicy.fingerprint(root).contentHash)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeWorkspace(root: File, gameName: String, files: List<Pair<String, String>>) {
        files.forEach { (path, contents) ->
            File(root, "location-0000/$path").apply { parentFile?.mkdirs() }.writeText(contents)
        }
        GameSnapshotManifest.from(game, gameName = gameName).write(File(root, GameSnapshotManifest.FILE_NAME))
    }
}
