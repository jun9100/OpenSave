package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun historyIndexIgnoresRestoreSafetyCopies() {
        val records = listOf(
            record("1", gameKey = "pc:test-a", createdAt = 100, uploaded = true),
            record("2", gameKey = "pc:test-a", createdAt = 300, uploaded = false, comment = "恢复前安全副本"),
            record("3", gameKey = "pc:test-b", createdAt = 200, uploaded = false, comment = "跨设备恢复前安全副本"),
        )
        val index = LibraryView.historyIndex(records)
        assertEquals(LibraryRowHistory(localCount = 1, lastBackupAtMillis = 100, latestUploaded = true), index["$sourceId::pc:test-a"])
        assertNull(index["$sourceId::pc:test-b"])
    }

    @Test
    fun rowSecondaryShowsSourceAndLastBackup() {
        val time: (Long) -> String = { "T$it" }
        assertEquals("从未备份 · Eden", LibraryView.rowSecondary("Eden", null, batchEligible = true, formatTime = time))
        // The upload state is the row's status badge now.
        assertEquals(
            "T300 · 本地 2 · Eden",
            LibraryView.rowSecondary("Eden", LibraryRowHistory(2, 300, false), batchEligible = true, formatTime = time),
        )
        assertEquals(
            "仅待确认位置 · T5 · 本地 1 · Eden",
            LibraryView.rowSecondary("Eden", LibraryRowHistory(1, 5, true), batchEligible = false, formatTime = time),
        )
    }

    @Test
    fun statusFilterCombinesWithTypeAndCountsEachChip() {
        val (a, b, c) = games.map { it.libraryKey }
        val statuses = mapOf(
            a to GameSyncStatus.LOCAL_CHANGED,
            b to GameSyncStatus.NEVER_BACKED_UP,
            c to GameSyncStatus.LOCAL_CHANGED,
        )
        val pcType = games.first { it.game.gameKey == "pc:test-a" }.saveType.id
        val pcGames = LibraryView.filter(games, pcType)
        val changed = LibraryView.filterByStatus(pcGames, statuses, LibraryStatusFilter.CHANGED)
        assertEquals(pcGames.filter { statuses[it.libraryKey] == GameSyncStatus.LOCAL_CHANGED }, changed)
        assertEquals(games, LibraryView.filterByStatus(games, statuses, LibraryStatusFilter.ALL))
        assertEquals(emptyList<SourceGameEntry>(), LibraryView.filterByStatus(games, statuses, LibraryStatusFilter.CONFLICT))

        val counts = LibraryView.statusCounts(games, statuses)
        assertEquals(3, counts[LibraryStatusFilter.ALL])
        assertEquals(2, counts[LibraryStatusFilter.CHANGED])
        assertEquals(1, counts[LibraryStatusFilter.NEVER_BACKED_UP])
        assertEquals(0, counts[LibraryStatusFilter.NOT_UPLOADED])
        // A game without a status yet is only under "全部状态".
        assertEquals(1, LibraryView.filterByStatus(games, mapOf(a to GameSyncStatus.LOCAL_CHANGED), LibraryStatusFilter.CHANGED).size)
    }

    @Test
    fun cloudNewerFilterMatchesAndItsChipOnlyShowsWhenUseful() {
        val (a, b) = games.map { it.libraryKey }
        val statuses = mapOf(a to GameSyncStatus.CLOUD_NEWER, b to GameSyncStatus.SYNCED)
        assertEquals(listOf(a), LibraryView.filterByStatus(games, statuses, LibraryStatusFilter.CLOUD_NEWER).map { it.libraryKey })
        val counts = LibraryView.statusCounts(games, statuses)
        assertEquals(1, counts[LibraryStatusFilter.CLOUD_NEWER])
        assertEquals(LibraryStatusFilter.entries, LibraryView.toolbarStatusFilters(counts, LibraryStatusFilter.ALL))

        val none = LibraryView.statusCounts(games, emptyMap())
        assertEquals(
            LibraryStatusFilter.entries - LibraryStatusFilter.CLOUD_NEWER,
            LibraryView.toolbarStatusFilters(none, LibraryStatusFilter.ALL),
        )
        // The active filter never disappears from under the focus.
        assertTrue(LibraryStatusFilter.CLOUD_NEWER in LibraryView.toolbarStatusFilters(none, LibraryStatusFilter.CLOUD_NEWER))
    }

    @Test
    fun changedKeysAreNeverBackedUpOrLocallyChanged() {
        val statuses = mapOf(
            "a" to GameSyncStatus.LOCAL_CHANGED,
            "b" to GameSyncStatus.NEVER_BACKED_UP,
            "c" to GameSyncStatus.SYNCED,
            "d" to GameSyncStatus.CONFLICT,
            "e" to GameSyncStatus.NOT_UPLOADED,
        )
        assertEquals(listOf("a", "b"), LibraryView.changedKeys(listOf("a", "b", "c", "d", "e", "f"), statuses))
    }

    @Test
    fun primaryBackupIsTheSelectionElseTheChangedGames() {
        val changed = LibraryView.primaryBackup(emptySet(), listOf("a", "b"), selectionMode = false)
        assertEquals(PrimaryBackup("备份有变更 (2)", setOf("a", "b"), fromSelection = false), changed)
        assertTrue(changed.enabled)

        val selected = LibraryView.primaryBackup(setOf("c"), listOf("a", "b"), selectionMode = false)
        assertEquals(PrimaryBackup("备份所选 (1)", setOf("c"), fromSelection = true), selected)

        // Selection mode with nothing checked offers an empty "备份所选", never the changed games.
        val empty = LibraryView.primaryBackup(emptySet(), listOf("a"), selectionMode = true)
        assertEquals("备份所选 (0)", empty.label)
        assertFalse(empty.enabled)
        assertFalse(LibraryView.primaryBackup(emptySet(), emptyList(), selectionMode = false).enabled)
    }

    @Test
    fun retainedSnapshotsKeepEveryGamesNewestVersions() {
        // Newest first, as the repository lists them: game a has many recent versions.
        val records = (10 downTo 1).map { record("a$it", gameKey = "pc:test-a", createdAt = 100L + it, uploaded = true) } +
            record("b1", gameKey = "pc:test-b", createdAt = 50, uploaded = true) +
            record("s2", gameKey = null, createdAt = 40, uploaded = true) +
            record("s1", gameKey = null, createdAt = 30, uploaded = true)
        val kept = LibraryView.retainedSnapshots(records, perGame = 3, other = 1)
        assertEquals(listOf("a10", "a9", "a8", "b1", "s2"), kept.map { it.id })
    }

    @Test
    fun folderGrantGuideOnlyWithoutRootAndWithoutGrantedFolder() {
        assertEquals(false, LibraryView.needsFolderGrant(SourceUiState()))
        assertEquals(true, LibraryView.needsFolderGrant(SourceUiState(rootChecked = true)))
        assertEquals(false, LibraryView.needsFolderGrant(SourceUiState(rootChecked = true, rootAvailable = true)))
        assertEquals(false, LibraryView.needsFolderGrant(SourceUiState(rootChecked = true, hasPersistedPermission = true)))
    }

    @Test
    fun progressLineSaysWhenAnOperationWaitsForAutoBackup() {
        val waiting = SourceUiState(busy = true, progress = "正在备份 Hades", lockOwner = StorageOperationLock.AUTO_BACKUP)
        assertEquals("等待自动备份完成…", LibraryView.progressLine(waiting))
        assertEquals("正在备份 Hades", LibraryView.progressLine(waiting.copy(lockOwner = StorageOperationLock.APP)))
        assertEquals(null, LibraryView.progressLine(SourceUiState(lockOwner = StorageOperationLock.AUTO_BACKUP)))
    }

    @Test
    fun backClosesPortraitDetailThenReturnsToLibraryThenLeaves() {
        assertEquals(BackTarget.CLOSE_DETAIL, LibraryView.backTarget(OpenSavePage.LIBRARY, gameDetailOpen = true, landscape = false))
        assertEquals(BackTarget.SYSTEM, LibraryView.backTarget(OpenSavePage.LIBRARY, gameDetailOpen = true, landscape = true))
        assertEquals(BackTarget.SYSTEM, LibraryView.backTarget(OpenSavePage.LIBRARY, gameDetailOpen = false, landscape = false))
        assertEquals(BackTarget.LIBRARY, LibraryView.backTarget(OpenSavePage.SETTINGS, gameDetailOpen = true, landscape = false))
        assertEquals(BackTarget.LIBRARY, LibraryView.backTarget(OpenSavePage.SOURCES, gameDetailOpen = false, landscape = true))
        assertEquals(BackTarget.PARENT, LibraryView.backTarget(OpenSavePage.SYNC, gameDetailOpen = false, landscape = true))
    }

    private fun record(id: String, gameKey: String?, createdAt: Long, uploaded: Boolean, comment: String = "") = SnapshotRecord(
        id = id,
        sourceId = sourceId,
        remoteName = "",
        localPath = "",
        createdAtMillis = createdAt,
        sizeBytes = 1,
        sha256 = "",
        manifestHash = "",
        uploaded = uploaded,
        comment = comment,
        gameKey = gameKey,
    )

    @Test
    fun emptyLibrarySaysWhy() {
        assertEquals(LibraryEmptyState.SCANNING, LibraryView.emptyState(scanned = false, readableSources = 0, gameCount = 0, visibleCount = 0))
        assertEquals(LibraryEmptyState.NO_ACCESS, LibraryView.emptyState(scanned = true, readableSources = 0, gameCount = 0, visibleCount = 0))
        assertEquals(
            LibraryEmptyState.GRANTS_UNAVAILABLE,
            LibraryView.emptyState(scanned = true, readableSources = 0, gameCount = 0, visibleCount = 0, unavailableGrants = 1),
        )
        // One readable source is enough: the broken grant shows on the Sources page.
        assertEquals(
            LibraryEmptyState.NOTHING_FOUND,
            LibraryView.emptyState(scanned = true, readableSources = 1, gameCount = 0, visibleCount = 0, unavailableGrants = 1),
        )
        assertEquals(LibraryEmptyState.NOTHING_FOUND, LibraryView.emptyState(scanned = true, readableSources = 2, gameCount = 0, visibleCount = 0))
        assertEquals(LibraryEmptyState.FILTERED_OUT, LibraryView.emptyState(scanned = true, readableSources = 2, gameCount = 3, visibleCount = 0))
        // A rescan that is still running keeps the games it already shows.
        assertNull(LibraryView.emptyState(scanned = false, readableSources = 1, gameCount = 3, visibleCount = 3))
    }

    @Test
    fun theSameGameInSeveralSourcesIsOneRowAndPathOnlyGamesStaySeparate() {
        fun scan(path: String, label: String, vararg saves: DetectedSave) = SourceScanResult(
            source = SourceSelection.Root(path),
            label = label,
            scan = SaveScanReport(rootPath = path, scannedDirectoryCount = saves.size, detected = saves.toList()),
        )
        val switchSave = { rel: String -> DetectedSave(kind = "yuzu-switch", relativePath = rel, titleId = "0100AA50140CA000", note = "", gameKey = "switch:0100AA50140CA000") }
        val pathOnly = { rel: String -> DetectedSave(kind = "emulator", relativePath = rel, note = "", displayName = "Same") }
        val entries = GameLibrary.sourceGames(
            listOf(
                scan("/storage/emulated/0/eden", "Eden Switch · 公共目录 eden", switchSave("nand/user/save/0/A/0100AA50140CA000"), pathOnly("x/saves")),
                scan("/data/user/0/dev.eden.eden_emulator/files", "Eden Switch · 私有目录", switchSave("nand/user/save/0/B/0100AA50140CA000"), pathOnly("x/saves")),
            ),
        )
        val rows = LibraryView.groupRows(entries)
        val switchRow = rows.single { it.key == "switch:0100AA50140CA000" }
        assertEquals(2, switchRow.members.size)
        assertEquals(2, rows.count { row -> row.members.size == 1 && row.members.single().game.gameKey.startsWith("path:") })
        assertEquals(switchRow.members[1], LibraryView.rowEntry(switchRow, switchRow.members[1].libraryKey))
        assertEquals("私有目录", LibraryView.locationLabel(switchRow.members.first { it.sourceLabel.endsWith("私有目录") }))

        val statuses = mapOf(
            switchRow.members[0].libraryKey to GameSyncStatus.SYNCED,
            switchRow.members[1].libraryKey to GameSyncStatus.CONFLICT,
        )
        assertEquals(GameSyncStatus.CONFLICT, LibraryView.groupStatus(switchRow, statuses))
    }

    @Test
    fun appsAndroidDoesNotCallGamesStayOutOfTheLibrary() {
        val path = "/storage/emulated/0"
        val entries = GameLibrary.sourceGames(
            listOf(
                SourceScanResult(
                    source = SourceSelection.Root(path),
                    label = "共享存储",
                    scan = SaveScanReport(
                        rootPath = path,
                        scannedDirectoryCount = 2,
                        detected = listOf(
                            DetectedSave(kind = "android-app", relativePath = "Android/data/com.google.android.youtube/files", note = "", displayName = "YouTube", gameKey = "android:com.google.android.youtube", candidate = true),
                            DetectedSave(kind = "android-game", relativePath = "Android/data/com.poncle.vampiresurvivors/files", note = "", displayName = "Vampire Survivors", gameKey = "android:com.poncle.vampiresurvivors"),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(listOf("android:com.poncle.vampiresurvivors"), LibraryView.libraryEntries(entries).map { it.game.gameKey })
        assertEquals(listOf("com.google.android.youtube"), LibraryView.unconfirmedApps(entries).map { it.packageName })
    }
}
