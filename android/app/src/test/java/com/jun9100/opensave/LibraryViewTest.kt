package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryViewTest {
    private val source = SourceSelection.Root("/sdcard/Eden")
    private val sourceId = SnapshotNaming.sourceId(source.idSeed)

    private val games = GameLibrary.sourceGames(
        listOf(
            SourceScanResult(
                source = source,
                label = "Eden",
                scan = SaveScanReport(
                    rootPath = source.path,
                    scannedDirectoryCount = 3,
                    detected = listOf(
                        DetectedSave(kind = "gamehub-pc", relativePath = "a", note = "", displayName = "Test Game A", gameKey = "pc:test-a"),
                        DetectedSave(kind = "gamehub-pc", relativePath = "b", note = "", displayName = "Test Game B", gameKey = "pc:test-b"),
                        DetectedSave(kind = "ppsspp", relativePath = "c", note = "", displayName = "Test Game C", gameKey = "psp:test-c"),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun typeOptionsStartWithAllAndListEachTypeOnce() {
        val options = LibraryView.typeOptions(games)
        assertEquals(LibraryView.ALL_TYPES to "全部", options.first())
        assertEquals(options.size, options.map { it.first }.distinct().size)
        assertEquals(games.map { it.saveType.id }.distinct().size + 1, options.size)
    }

    @Test
    fun filterThatDisappearedFallsBackToAll() {
        val options = LibraryView.typeOptions(games)
        assertEquals(LibraryView.ALL_TYPES, LibraryView.effectiveTypeId("emulator-dolphin", options))
        val pcType = games.first { it.game.gameKey == "pc:test-a" }.saveType.id
        assertEquals(pcType, LibraryView.effectiveTypeId(pcType, options))
        assertEquals(games.size, LibraryView.filter(games, LibraryView.ALL_TYPES).size)
        assertEquals(games.count { it.saveType.id == pcType }, LibraryView.filter(games, pcType).size)
    }

    @Test
    fun detailEntryFallsBackToFirstRowOnlyInLandscape() {
        val second = games[1]
        assertEquals(second, LibraryView.detailEntry(games, second.libraryKey, fallbackToFirst = false))
        assertEquals(games.first(), LibraryView.detailEntry(games, "gone::key", fallbackToFirst = true))
        assertNull(LibraryView.detailEntry(games, "gone::key", fallbackToFirst = false))
        assertNull(LibraryView.detailEntry(emptyList(), null, fallbackToFirst = true))
    }

    @Test
    fun historyIndexUsesNewestRecordPerLibraryKey() {
        val records = listOf(
            record("1", gameKey = "pc:test-a", createdAt = 100, uploaded = true),
            record("2", gameKey = "pc:test-a", createdAt = 300, uploaded = false),
            record("3", gameKey = "pc:test-b", createdAt = 200, uploaded = true),
            record("4", gameKey = null, createdAt = 400, uploaded = true),
        )
        val index = LibraryView.historyIndex(records)
        assertEquals(LibraryRowHistory(localCount = 2, lastBackupAtMillis = 300, latestUploaded = false), index["$sourceId::pc:test-a"])
        assertEquals(LibraryRowHistory(localCount = 1, lastBackupAtMillis = 200, latestUploaded = true), index["$sourceId::pc:test-b"])
        assertEquals(2, index.size)
    }

    @Test
    fun rowSecondaryShowsSourceLastBackupAndUploadState() {
        val time: (Long) -> String = { "T$it" }
        assertEquals("Eden · 从未备份", LibraryView.rowSecondary("Eden", null, batchEligible = true, formatTime = time))
        assertEquals(
            "Eden · T300 · 本地 2 · 未上传",
            LibraryView.rowSecondary("Eden", LibraryRowHistory(2, 300, false), batchEligible = true, formatTime = time),
        )
        assertEquals(
            "Eden · 仅待确认位置 · T5 · 本地 1 · 已上传",
            LibraryView.rowSecondary("Eden", LibraryRowHistory(1, 5, true), batchEligible = false, formatTime = time),
        )
    }

    @Test
    fun backClosesPortraitDetailThenReturnsToLibraryThenLeaves() {
        assertEquals(BackTarget.CLOSE_DETAIL, LibraryView.backTarget(OpenSavePage.LIBRARY, gameDetailOpen = true, landscape = false))
        assertEquals(BackTarget.SYSTEM, LibraryView.backTarget(OpenSavePage.LIBRARY, gameDetailOpen = true, landscape = true))
        assertEquals(BackTarget.SYSTEM, LibraryView.backTarget(OpenSavePage.LIBRARY, gameDetailOpen = false, landscape = false))
        assertEquals(BackTarget.LIBRARY, LibraryView.backTarget(OpenSavePage.MANAGEMENT, gameDetailOpen = true, landscape = false))
        assertEquals(BackTarget.LIBRARY, LibraryView.backTarget(OpenSavePage.MANAGEMENT, gameDetailOpen = false, landscape = true))
    }

    private fun record(id: String, gameKey: String?, createdAt: Long, uploaded: Boolean) = SnapshotRecord(
        id = id,
        sourceId = sourceId,
        remoteName = "",
        localPath = "",
        createdAtMillis = createdAt,
        sizeBytes = 1,
        sha256 = "",
        manifestHash = "",
        uploaded = uploaded,
        gameKey = gameKey,
    )
}
