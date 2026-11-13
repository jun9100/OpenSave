package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLibraryTest {
    @Test
    fun dropsTheSameSaveDirectoryScannedByTwoOverlappingSources() {
        // 真机事故：Eden 的两个来源根不同（`<pkg>` 与 `<pkg>/files`），同一个
        // `files/nand/user/save/…/<TITLE_ID>` 被算成两个存档位置。
        val pkg = "/storage/emulated/0/Android/data/dev.eden.eden_emulator.nightly"
        val save = "nand/user/save/0000000000000000/7CA93EA2/01008D7016438000"
        val wide = SourceScanResult(
            source = SourceSelection.Root(pkg),
            label = "Eden Nightly · Android/data",
        )
        val narrow = SourceScanResult(
            source = SourceSelection.Root("$pkg/files"),
            label = "Android 游戏：Eden Nightly · Android/data",
        )
        val entries = GameLibrary.sourceGames(
            listOf(
                wide.copy(scan = report(wide, "files/$save")),
                narrow.copy(scan = report(narrow, save)),
            ),
        )

        assertEquals(1, entries.size)
        assertEquals("$pkg/files", (entries.single().source as SourceSelection.Root).path)

        // 真的不同的位置不能被吞掉。
        val both = GameLibrary.sourceGames(
            listOf(
                narrow.copy(scan = report(narrow, save)),
                narrow.copy(scan = report(narrow, "$save-second")),
            ),
        )
        assertEquals(2, both.size)
    }

    private fun report(source: SourceScanResult, relative: String): SaveScanReport = SaveScanReport(
        rootPath = source.source.displayPath,
        scannedDirectoryCount = 1,
        detected = listOf(
            DetectedSave(
                kind = "yuzu-switch",
                relativePath = relative,
                titleId = "01008D7016438000",
                note = "probe",
                displayName = "Switch 01008D7016438000",
                gameKey = "switch:01008D7016438000",
            ),
        ),
    )

    @Test
    fun mergesMultipleLocationsWithTheSameGameKey() {
        val games = GameLibrary.group(
            listOf(
                DetectedSave(
                    kind = "gamehub-pc",
                    relativePath = "userdata/1794680/remote",
                    note = "steam",
                    displayName = "Steam AppID 1794680",
                    gameKey = "steam:1794680",
                ),
                DetectedSave(
                    kind = "gamehub-pc",
                    relativePath = "userdata/1794680/local",
                    note = "local",
                    displayName = "Steam AppID 1794680",
                    gameKey = "steam:1794680",
                    candidate = true,
                ),
                DetectedSave(
                    kind = "gamehub-pc",
                    relativePath = "other/hades/save",
                    note = "other",
                    displayName = "Hades",
                    gameKey = "pc:hades",
                ),
            ),
        )

        assertEquals(2, games.size)
        val vampireSurvivors = games.single { it.gameKey == "steam:1794680" }
        assertEquals("吸血鬼幸存者", vampireSurvivors.presentation.primaryName)
        assertEquals(2, vampireSurvivors.locationCount)
        assertEquals(1, vampireSurvivors.candidateCount)
        assertFalse(vampireSurvivors.isCandidateOnly)
    }

    @Test
    fun keepsUnknownLocationsSeparateWhenThereIsNoGameKey() {
        val games = GameLibrary.group(
            listOf(
                DetectedSave("gamehub-pc", "a/save", note = "a"),
                DetectedSave("gamehub-pc", "b/save", note = "b"),
            ),
        )

        assertEquals(2, games.size)
        assertTrue(games.all { it.locationCount == 1 })
    }

    @Test
    fun mergesKnownPcNameWithSteamAppIdCandidate() {
        val games = GameLibrary.group(
            listOf(
                DetectedSave(
                    kind = "gamehub-pc",
                    relativePath = "pc/The Life and Suffering of Sir Brante/Save",
                    note = "name",
                    displayName = "The Life and Suffering of Sir Brante",
                    gameKey = "pc:the life and suffering of sir brante",
                ),
                DetectedSave(
                    kind = "gamehub-pc",
                    relativePath = "userdata/1272160",
                    note = "app id",
                    displayName = "Steam AppID 1272160",
                    gameKey = "steam:1272160",
                    candidate = true,
                ),
            ),
        )

        assertEquals(1, games.size)
        assertEquals("布兰特爵士的生平和苦难", games.single().presentation.primaryName)
        assertEquals("1272160", games.single().presentation.steamAppId)
        assertEquals(2, games.single().locationCount)
    }

    @Test
    fun batchBackupIncludesOnlyGamesWithRecognizedLocations() {
        val games = GameLibrary.group(
            listOf(
                DetectedSave(
                    kind = "yuzu-switch",
                    relativePath = "nand/user/save/0100ABCDEF123456",
                    note = "switch",
                    gameKey = "switch:0100ABCDEF123456",
                ),
                DetectedSave(
                    kind = "gamehub-pc",
                    relativePath = "userdata/123456",
                    note = "candidate",
                    gameKey = "steam:123456",
                    candidate = true,
                ),
            ),
        )

        val eligible = GameLibrary.batchBackupEligible(games)

        assertEquals(1, eligible.size)
        assertEquals("switch:0100ABCDEF123456", eligible.single().gameKey)
        assertEquals(1, GameLibrary.batchBackupLocations(eligible.single()).size)
    }

    @Test
    fun aggregatesSourcesWithoutMergingSameGameAcrossOrigins() {
        val save = DetectedSave(
            kind = "yuzu-switch",
            relativePath = "nand/user/save/0100ABCDEF123456",
            note = "switch",
            gameKey = "switch:0100ABCDEF123456",
        )
        val entries = GameLibrary.sourceGames(
            listOf(
                SourceScanResult(
                    SourceSelection.Root("/data/user/0/dev.eden.eden_emulator/files"),
                    "Eden Switch",
                    SaveScanReport("/eden", 5, listOf(save)),
                    access = SourceAccessStatus.rootOnly(),
                ),
                SourceScanResult(
                    SourceSelection.Root("/storage/emulated/0/Android/data"),
                    "Android 应用数据",
                    SaveScanReport("/android-data", 8, listOf(save)),
                    access = SourceAccessStatus.rootOnly(),
                ),
            ),
        )

        assertEquals(2, entries.size)
        assertEquals(2, entries.map { it.libraryKey }.distinct().size)
        assertEquals(2, GameLibrary.batchBackupEligibleEntries(entries).size)
        assertEquals("Eden Switch", entries.first { it.sourceLabel == "Eden Switch" }.sourceLabel)
        assertTrue(entries.all { it.sourceAccess.capability == SourceAccessCapability.ROOT })
    }

    @Test
    fun sourceAggregationExcludesCandidatesFromDefaultBatchSelection() {
        val entries = GameLibrary.sourceGames(
            listOf(
                SourceScanResult(
                    SourceSelection.Root("/storage/emulated/0/Android/data"),
                    "Android 应用数据",
                    SaveScanReport(
                        "/android-data",
                        4,
                        listOf(DetectedSave("android-app", "com.example.game", note = "app data", candidate = true)),
                    ),
                ),
            ),
        )

        assertTrue(entries.isNotEmpty())
        assertTrue(GameLibrary.batchBackupEligibleEntries(entries).isEmpty())
    }

    @Test
    fun backupLocationsShareOneDefaultAcrossPaths() {
        val saves = listOf(
            DetectedSave(kind = "gamehub-pc", relativePath = "Games/A/Save", note = "save", gameKey = "pc:a"),
            DetectedSave(kind = "gamehub-pc", relativePath = "Games/A/Maybe", note = "candidate", gameKey = "pc:a", candidate = true),
            DetectedSave(kind = "android-app", relativePath = "Android/data/com.a", note = "app", gameKey = "pc:a"),
            DetectedSave(kind = "gamehub-pc", relativePath = ".", note = "root", gameKey = "pc:a"),
        )
        val game = DetectedGame("pc:a", GameCatalog.present(saves.first()), saves)

        assertEquals(listOf("Games/A/Save"), GameLibrary.batchBackupLocations(game).map { it.relativePath })
        assertEquals(
            listOf("Games/A/Save", "Games/A/Maybe", "Android/data/com.a"),
            GameLibrary.backupLocations(game, includeCandidates = true, includeAndroidApp = true).map { it.relativePath },
        )
    }
}
