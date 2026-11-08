package com.jun9100.opensave

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossDeviceRestoreTest {
    private val policy = CrossDeviceRestorePolicy

    @Test
    fun pcArchiveWithOneLocalLocationMapsAutomatically() = withTempDir { root ->
        File(root, "save.dat").writeText("save")
        val archive = policy.readArchive(root)
        assertEquals(CrossDeviceArchiveFormat.PC, archive.format)
        assertEquals(listOf(CrossDeviceArchivePart("", null)), archive.parts)

        val plan = policy.plan(archive, listOf(target("save", "gamehub-pc")))

        assertEquals(mapOf(0 to "save"), plan.mapping)
        assertTrue(plan.complete)
        assertTrue(plan.restoresAnything)
        assertTrue(plan.undecided.isEmpty())
    }

    @Test
    fun pcArchiveWithTwoLocalLocationsWaitsForAChoice() = withTempDir { root ->
        File(root, "save.dat").writeText("save")
        val plan = policy.plan(
            policy.readArchive(root),
            listOf(target("one", "gamehub-pc"), target("two", "gamehub-pc")),
        )

        assertFalse(plan.complete)
        assertEquals(listOf(0), plan.undecided)
        assertFalse(plan.restoresAnything)

        val chosen = policy.choose(plan, 0, "two")
        assertTrue(chosen.complete)
        assertEquals(mapOf(0 to "two"), chosen.mapping)
        assertTrue(chosen.restoresAnything)
    }

    @Test
    fun androidArchiveMatchesUniqueKindsOnly() {
        val archive = CrossDeviceArchive(
            CrossDeviceArchiveFormat.ANDROID,
            listOf(
                CrossDeviceArchivePart("location-0000", "switch-save"),
                CrossDeviceArchivePart("location-0001", "pc-save"),
            ),
        )
        val plan = policy.plan(
            archive,
            listOf(target("local/switch", "switch-save"), target("local/pc", "pc-save")),
        )

        assertEquals(
            mapOf(0 to "local/switch", 1 to "local/pc"),
            plan.mapping,
        )
        assertTrue(plan.complete)
        assertTrue(plan.undecided.isEmpty())
    }

    @Test
    fun duplicateArchiveKindIsNeverGuessed() {
        val archive = CrossDeviceArchive(
            CrossDeviceArchiveFormat.ANDROID,
            listOf(
                CrossDeviceArchivePart("location-0000", "pc-save"),
                CrossDeviceArchivePart("location-0001", "pc-save"),
            ),
        )
        val plan = policy.plan(archive, listOf(target("local/pc", "pc-save")))

        assertFalse(plan.complete)
        assertEquals(listOf(0, 1), plan.undecided)
        assertTrue(plan.mapping.isEmpty())
    }

    @Test
    fun unknownArchiveKindIsLeftForTheUser() {
        val archive = CrossDeviceArchive(
            CrossDeviceArchiveFormat.ANDROID,
            listOf(
                CrossDeviceArchivePart("location-0000", "pc-save"),
                CrossDeviceArchivePart("location-0001", "other-device-only"),
            ),
        )
        val plan = policy.plan(archive, listOf(target("local/pc", "pc-save")))

        assertEquals(mapOf(0 to "local/pc"), plan.mapping)
        assertEquals(listOf(1), plan.undecided)
        assertFalse(plan.complete)
    }

    @Test
    fun skippingEveryPartCompletesWithoutRestoringAnything() {
        val archive = CrossDeviceArchive(
            CrossDeviceArchiveFormat.ANDROID,
            listOf(
                CrossDeviceArchivePart("location-0000", "pc-save"),
                CrossDeviceArchivePart("location-0001", "switch-save"),
            ),
        )
        val plan = policy.plan(
            archive,
            listOf(target("local/pc", "pc-save"), target("local/switch", "switch-save")),
        )

        val firstSkipped = policy.choose(plan, 0, null)
        assertTrue(firstSkipped.complete)
        assertTrue(firstSkipped.restoresAnything)

        val allSkipped = policy.choose(firstSkipped, 1, null)
        assertTrue(allSkipped.complete)
        assertFalse(allSkipped.restoresAnything)
        assertEquals(mapOf(0 to null, 1 to null), allSkipped.mapping)
    }

    @Test
    fun choosingAnOccupiedTargetMakesTheOtherPartUndecided() {
        val archive = CrossDeviceArchive(
            CrossDeviceArchiveFormat.ANDROID,
            listOf(
                CrossDeviceArchivePart("location-0000", "pc-save"),
                CrossDeviceArchivePart("location-0001", "switch-save"),
            ),
        )
        val plan = policy.plan(
            archive,
            listOf(target("local/pc", "pc-save"), target("local/switch", "switch-save")),
        )

        val chosen = policy.choose(plan, 1, "local/pc")

        assertEquals(mapOf(1 to "local/pc"), chosen.mapping)
        assertEquals(listOf(0), chosen.undecided)
        assertFalse(chosen.complete)
    }

    @Test
    fun choosingAPathOutsideTheLocalTargetsFails() = withTempDir { root ->
        File(root, "save.dat").writeText("save")
        val plan = policy.plan(policy.readArchive(root), listOf(target("save", "gamehub-pc")))

        assertThrows(IllegalArgumentException::class.java) {
            policy.choose(plan, 0, "../elsewhere")
        }
    }

    @Test
    fun planningWithoutLocalTargetsFails() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            policy.plan(
                CrossDeviceArchive(CrossDeviceArchiveFormat.PC, listOf(CrossDeviceArchivePart("", null))),
                emptyList(),
            )
        }

        assertEquals("本机这款游戏没有可恢复的存档位置", error.message)
    }

    @Test
    fun targetsOfUsesBackupLocationsDeduplicatedAndSorted() {
        val game = DetectedGame(
            gameKey = "steam:123",
            presentation = GamePresentation("测试游戏", "Test Game", "Steam AppID 123", GameIconKey.PC),
            saves = listOf(
                save("z-last", "pc-save"),
                save("a-first", "pc-save"),
                save("a-first", "pc-save"),
                save("candidate", "pc-save", candidate = true),
                save("app-data", "android-app"),
            ),
        )

        assertEquals(
            listOf(
                CrossDeviceTarget("a-first", "pc-save"),
                CrossDeviceTarget("z-last", "pc-save"),
            ),
            policy.targetsOf(game),
        )
    }

    @Test
    fun androidManifestIsReadFromItsArchiveDirectories() = withTempDir { root ->
        assertTrue(File(root, "location-0000").mkdirs())
        assertTrue(File(root, "location-0001").mkdirs())
        manifest(
            GameSnapshotLocation("switch-save", "saves/switch", "location-0000", false),
            GameSnapshotLocation("pc-save", "saves/pc", "location-0001", false),
        ).write(File(root, GameSnapshotManifest.FILE_NAME))

        val archive = policy.readArchive(root)

        assertEquals(CrossDeviceArchiveFormat.ANDROID, archive.format)
        assertEquals(
            listOf(
                CrossDeviceArchivePart("location-0000", "switch-save"),
                CrossDeviceArchivePart("location-0001", "pc-save"),
            ),
            archive.parts,
        )
    }

    @Test
    fun androidManifestPointingAtMissingDirectoryFails() = withTempDir { root ->
        manifest(
            GameSnapshotLocation("pc-save", "saves/pc", "location-0000", false),
        ).write(File(root, GameSnapshotManifest.FILE_NAME))

        val error = assertThrows(IllegalArgumentException::class.java) {
            policy.readArchive(root)
        }

        assertEquals("其他设备的存档不完整", error.message)
    }

    @Test
    fun archiveWithoutManifestIsPcFormat() = withTempDir { root ->
        File(root, "orphan.sav").writeText("save")

        val archive = policy.readArchive(root)

        assertEquals(CrossDeviceArchiveFormat.PC, archive.format)
        assertEquals(listOf(CrossDeviceArchivePart("", null)), archive.parts)
    }

    @Test
    fun emptyArchiveFails() = withTempDir { root ->
        val error = assertThrows(IllegalArgumentException::class.java) {
            policy.readArchive(root)
        }

        assertEquals("其他设备的存档是空的", error.message)
    }

    private fun manifest(vararg locations: GameSnapshotLocation) = GameSnapshotManifest(
        gameKey = "steam:123",
        gameName = "Test Game",
        locations = locations.toList(),
    )

    private fun target(relativePath: String, kind: String) = CrossDeviceTarget(relativePath, kind)

    private fun save(relativePath: String, kind: String, candidate: Boolean = false) = DetectedSave(
        kind = kind,
        relativePath = relativePath,
        note = "test",
        gameKey = "steam:123",
        candidate = candidate,
    )

    private fun withTempDir(block: (File) -> Unit) {
        val root = createTempDirectory("cross-device-restore-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
