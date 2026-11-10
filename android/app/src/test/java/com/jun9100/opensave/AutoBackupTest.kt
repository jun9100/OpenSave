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

    @Test
    fun everyAutoBackupRunLeavesOneActivityLine() {
        val quiet = AutoBackupPolicy.runEntry(AutoBackupTally(games = 4, unchanged = 4), atMillis = 1)
        assertEquals("自动备份：无变化 4 款", quiet.text)
        assertEquals(com.jun9100.opensave.activity.ActivityLevel.INFO, quiet.level)

        val busy = AutoBackupPolicy.runEntry(
            AutoBackupTally(
                games = 5,
                uploaded = 2,
                unchanged = 1,
                deferred = listOf("塞尔达" to "云端有冲突"),
                failed = listOf("马力欧" to "HTTP 502"),
                scanFailures = listOf("PPSSPP：目录授权已失效"),
            ),
            atMillis = 2,
        )
        assertEquals("自动备份：已上传 2 款，无变化 1 款，待确认 1 款，失败 1 款", busy.text)
        assertEquals(com.jun9100.opensave.activity.ActivityLevel.WARNING, busy.level)
        assertEquals("马力欧：HTTP 502；PPSSPP：目录授权已失效", busy.detail)

        assertEquals("自动备份：没有可备份的游戏", AutoBackupPolicy.runEntry(AutoBackupTally()).text)
        assertEquals(
            "自动备份已跳过：有批量任务正在运行",
            AutoBackupPolicy.skippedEntry("有批量任务正在运行").text,
        )
        assertEquals(com.jun9100.opensave.activity.ActivityLevel.ERROR, AutoBackupPolicy.failedEntry("超时", retrying = true).level)
    }

    @Test
    fun stoppedCloudScopesAreCountedWithoutBecomingFailures() {
        val stopped = AutoBackupPolicy.runEntry(
            AutoBackupTally(games = 3, uploaded = 1, unchanged = 1, cloudStopped = 1),
            atMillis = 1,
        )
        assertEquals("自动备份：已上传 1 款，无变化 1 款；1 款游戏的云端存档已被删除或合并，已跳过", stopped.text)
        assertTrue(stopped.detail.isBlank())

        val onlyStopped = AutoBackupPolicy.runEntry(AutoBackupTally(games = 2, cloudStopped = 2), atMillis = 2)
        assertEquals("自动备份：没有处理任何游戏；2 款游戏的云端存档已被删除或合并，已跳过", onlyStopped.text)

        val none = AutoBackupPolicy.runEntry(AutoBackupTally(games = 1, uploaded = 1), atMillis = 3)
        assertEquals("自动备份：已上传 1 款", none.text)
    }
}
