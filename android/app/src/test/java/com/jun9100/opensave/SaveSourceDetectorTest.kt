package com.jun9100.opensave

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveSourceDetectorTest {
    @Test
    fun detectsYuzuAndRyujinxTitleIdsWithoutScanningOutsideWorkspace() {
        val root = createTempDir(prefix = "opensave-detect-")
        try {
            File(root, "nand/user/save/account/profile/0100ABCDEF123456/save.dat").apply {
                parentFile.mkdirs()
                writeText("switch")
            }
            File(root, "bis/user/save/profile/0100FEDCBA654321/data.bin").apply {
                parentFile.mkdirs()
                writeText("ryujinx")
            }
            File(root, "unrelated/0100ABCDEF123456/file.bin").apply {
                parentFile.mkdirs()
                writeText("not a save layout")
            }

            val detected = SaveSourceDetector.detect(root)

            assertEquals(2, detected.count { it.titleId != null })
            assertTrue(detected.any { it.kind == "yuzu-switch" && it.titleId == "0100ABCDEF123456" })
            assertTrue(detected.any { it.kind == "ryujinx-switch" && it.titleId == "0100FEDCBA654321" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun detectsEdenSaveUnderItsAndroidPackageRoot() {
        val root = "/data/user/0/dev.eden.eden_emulator/files"
        val report = SaveSourceDetector.scanDirectories(
            rootPath = root,
            directoryPaths = listOf(
                root,
                "$root/nand/user/save/0000000000000000/1830C6EC7919A4B903473439602D8654/01008D7016438000",
            ),
        )

        assertEquals(1, report.gameCount)
        assertEquals("yuzu-switch", report.detected.single().kind)
        assertEquals("01008D7016438000", report.detected.single().titleId)
    }

    @Test
    fun detectsNativeAndroidGamePersistentDirectoriesWithoutTreatingCachesAsSaves() {
        val root = "/data/user/0/com.poncle.vampiresurvivors"
        val report = SaveSourceDetector.scanDirectories(
            rootPath = root,
            directoryPaths = listOf(
                root,
                "$root/files",
                "$root/shared_prefs",
                "$root/cache",
                "$root/code_cache",
            ),
            androidGames = mapOf(
                "com.poncle.vampiresurvivors" to AndroidGameMetadata(
                    "com.poncle.vampiresurvivors",
                    "Vampire Survivors",
                ),
            ),
        )

        assertEquals(1, report.gameCount)
        assertEquals(2, report.detected.size)
        assertTrue(report.detected.all { it.kind == "android-game" && it.gameKey == "android:com.poncle.vampiresurvivors" })
        assertFalse(report.detected.any { it.candidate })
        assertTrue(report.detected.none { it.relativePath == "cache" || it.relativePath == "code_cache" })
    }

    @Test
    fun detectsFlutterPersistentDirectoryButExcludesWebViewData() {
        val root = "/data/user/0/example.flutter.game"
        val report = SaveSourceDetector.scanDirectories(
            rootPath = root,
            directoryPaths = listOf(
                root,
                "$root/app_flutter",
                "$root/app_webview",
                "$root/cache",
            ),
            androidGames = mapOf(
                "example.flutter.game" to AndroidGameMetadata(
                    "example.flutter.game",
                    "Example Flutter Game",
                ),
            ),
        )

        assertEquals(listOf("app_flutter"), report.detected.map(DetectedSave::relativePath))
        assertTrue(report.detected.all { it.kind == "android-game" && !it.candidate })
    }

    @Test
    fun exposesCustomAndroidGameDataAsAnExplicitCandidate() {
        val root = "/data/user/0/example.native.game"
        val report = SaveSourceDetector.scanDirectories(
            rootPath = root,
            directoryPaths = listOf(
                root,
                "$root/game_data",
                "$root/app_webview",
                "$root/cache",
            ),
            androidGames = mapOf(
                "example.native.game" to AndroidGameMetadata(
                    "example.native.game",
                    "Example Native Game",
                ),
            ),
        )

        assertEquals(listOf("game_data"), report.detected.map(DetectedSave::relativePath))
        assertTrue(report.detected.single().candidate)
        assertEquals("android-game", report.detected.single().kind)
    }

    @Test
    fun candidateAppDoesNotExposeItsScanRootAsABackupLocation() {
        val root = "/data/user/0/example.unknown.game"
        val report = SaveSourceDetector.scanDirectories(
            rootPath = root,
            directoryPaths = listOf(root, "$root/files"),
            androidGames = mapOf(
                "example.unknown.game" to AndroidGameMetadata(
                    "example.unknown.game",
                    "Example Unknown Game",
                    AndroidGameRecognition.CANDIDATE,
                ),
            ),
        )

        assertEquals(listOf("files"), report.detected.map(DetectedSave::relativePath))
        assertTrue(report.detected.single().candidate)
    }

    @Test
    fun detectsEdenTitleIdsAndGameHubPcSaveLocations() {
        val root = "/data/user/0/com.xiaoji.egggame/files"
        val directories = listOf(
            root,
            "$root/nand/user/save/0000000000000000/1830C6EC7919A4B903473439602D8654/01008D7016438000",
            "$root/usr/home/virtual_containers/96267/drive_c/users/steamuser/AppData/Local/Wandering_Sword/Saved",
            "$root/usr/home/virtual_containers/96267/drive_c/users/steamuser/AppData/Local/Wandering_Sword/Saved/76561198131641081/SaveGames",
            "$root/usr/home/virtual_containers/157182/drive_c/Program Files (x86)/Steam/userdata/171375353/3265700/ac/Nosebleed Interactive/Vampire Crawlers/Save",
            "$root/usr/home/virtual_containers/157182/drive_c/Program Files (x86)/Steam/userdata/171375353/3265700",
        )

        val report = SaveSourceDetector.scanDirectories(root, directories)

        assertEquals(1, report.detected.count { it.titleId == "01008D7016438000" })
        assertEquals(2, report.gameHubPcCount)
        assertTrue(report.detected.any { it.displayName == "Wandering_Sword" })
        assertTrue(report.detected.any { it.displayName == "Vampire Crawlers" })
        assertTrue(report.detected.none { it.relativePath.endsWith("/Saved") })
        assertEquals(3, report.gameCount)
    }

    @Test
    fun treatsSteamAppIdContainersAsDefaultPcSaveLocations() {
        val root = "/data/user/0/com.xiaoji.egggame/files"
        val report = SaveSourceDetector.scanDirectories(
            root,
            listOf(
                root,
                "$root/usr/home/virtual_containers/157182/drive_c/Program Files (x86)/Steam/userdata/171375353/1794680",
            ),
        )

        val save = report.detected.single()
        assertEquals("Steam AppID 1794680", save.displayName)
        assertEquals("steam:1794680", save.gameKey)
        assertFalse(save.candidate)
        assertEquals(1, report.gameCount)
    }

    @Test
    fun separatesDuckStationAndAetherSx2SaveRoots() {
        val root = "/storage/emulated/0/custom-saves"
        val report = SaveSourceDetector.scanDirectories(
            root,
            listOf(
                root,
                "$root/DuckStation/memcards",
                "$root/NetherSX2/memcards",
            ),
        )

        assertTrue(report.detected.any { it.kind == "duckstation" && it.relativePath == "DuckStation/memcards" })
        assertTrue(report.detected.any { it.kind == "aethersx2" && it.relativePath == "NetherSX2/memcards" })
    }

    @Test
    fun ignoresSwitchAccountAndSystemSaveDirectories() {
        val root = "/storage/emulated/0/Android/data/dev.eden.eden_emulator/files"
        val directories = listOf(
            root,
            "$root/nand/user/save/0000000000000000",
            "$root/nand/system/save/8000000000000010",
            "$root/nand/system/save/bcat/0100000000010000",
        )

        val report = SaveSourceDetector.scanDirectories(root, directories)

        assertTrue(report.detected.isEmpty())
        assertEquals(0, report.gameCount)
    }

    @Test
    fun detectsSharedGameHubPcFolderWithoutTreatingInstallRootAsSave() {
        val root = "/storage/emulated/0/pc"
        val directories = listOf(
            root,
            "$root/Raging Loop",
            "$root/Raging Loop/SteamEmu",
            "$root/Raging Loop/SteamEmu/Saves",
        )

        val report = SaveSourceDetector.scanDirectories(root, directories)

        assertEquals(1, report.gameHubPcCount)
        assertEquals("Raging Loop", report.detected.single().displayName)
        assertEquals("Raging Loop/SteamEmu/Saves", report.detected.single().relativePath)
    }

    @Test
    fun mergesNamedSteamAndWineLocationsAndFiltersGameHubNoise() {
        val root = "/data/user/0/com.xiaoji.egggame/files"
        val directories = listOf(
            root,
            "$root/usr/home/virtual_containers/96267/drive_c/Program Files (x86)/Steam/userdata/171375353/1876890",
            "$root/usr/home/virtual_containers/96267/drive_c/Program Files (x86)/Steam/userdata/171375353/1876890/ac/Wandering_Sword/Saved",
            "$root/usr/home/virtual_containers/96267/drive_c/Program Files (x86)/Steam/userdata/171375353/1876890/ac/Wandering_Sword/Saved/76561198131641081/SaveGames",
            "$root/usr/home/virtual_containers/96267/drive_c/users/steamuser/AppData/Local/Wandering_Sword/Saved/76561198131641081/SaveGames",
            "$root/usr/home/virtual_containers/157182/drive_c/Program Files (x86)/Steam/userdata/171375353/3265700",
            "$root/usr/home/virtual_containers/157182/drive_c/Program Files (x86)/Steam/userdata/171375353/3265700/ac/Nosebleed Interactive/Vampire Crawlers/Save",
            "$root/usr/home/virtual_containers/157182/drive_c/users/steamuser/AppData/LocalLow/Nosebleed Interactive/Vampire Crawlers/Save",
            "$root/usr/home/virtual_containers/local/drive_c/users/steamuser/AppData/LocalLow/Nosebleed Interactive/Vampire Crawlers/Save",
            "$root/usr/home/virtual_containers/96267/drive_c/users/steamuser/AppData/Local/UnrealEngine/4.26/Saved",
            "$root/Steam/steamapps/common/Wandering Sword/Saved",
            "$root/usr/home/virtual_containers/157182/drive_c/Program Files (x86)/Steam/userdata/171375353/3265700/ugc/ignored/Save",
        )

        val report = SaveSourceDetector.scanDirectories(root, directories)
        val gameHub = report.detected.filter { it.kind == "gamehub-pc" }

        assertEquals(5, report.gameHubPcCount)
        assertEquals(2, report.gameCount)
        assertEquals(0, report.candidateCount)
        assertEquals(2, gameHub.mapNotNull { it.gameKey }.toSet().size)
        assertEquals(5, gameHub.count { it.displayName == "Wandering_Sword" || it.displayName == "Vampire Crawlers" })
        assertTrue(report.detected.none { it.relativePath.contains("UnrealEngine") })
        assertTrue(report.detected.none { it.relativePath.contains("steamapps") })
        assertTrue(report.detected.none { it.relativePath.contains("ugc") })
    }

    @Test
    fun treatsSteamRemoteAndSavedGamesFoldersAsAutomaticBackupLocations() {
        val root = "/data/user/0/com.xiaoji.egggame/files"
        val directories = listOf(
            root,
            "$root/usr/home/containers/0/drive_c/users/steamuser/Saved Games",
            "$root/usr/home/containers/1/drive_c/users/steamuser/Saved Games",
            "$root/usr/home/containers/2/drive_c/users/steamuser/Saved Games",
            "$root/usr/home/virtual_containers/10543/drive_c/Program Files (x86)/Steam/userdata/171375353/1245620",
            "$root/usr/home/virtual_containers/10543/drive_c/Program Files (x86)/Steam/userdata/171375353/1245620/remote",
        )

        val report = SaveSourceDetector.scanDirectories(root, directories)

        assertEquals(0, report.candidateCount)
        assertEquals(4, report.gameCount)
        assertEquals(4, report.gameHubPcCount)
        assertEquals(3, report.detected.count { it.displayName == "PC Saved Games 容器" })
        assertTrue(report.detected.any { it.displayName == "Steam AppID 1245620" && !it.candidate })
    }

    @Test
    fun ignoresSteamBootstrapRemoteAndRecognizesSharedPcSaveRoot() {
        val root = "/storage/emulated/0/pc-game"
        val report = SaveSourceDetector.scanDirectories(
            root,
            listOf(
                root,
                "$root/saves",
                "$root/Steam/userdata/171375353/7/remote",
            ),
        )

        assertEquals(1, report.gameHubPcCount)
        assertEquals(1, report.gameCount)
        assertEquals("PC 共享存档", report.detected.single().displayName)
        assertEquals("saves", report.detected.single().relativePath)
        assertTrue(report.detected.none { it.relativePath.endsWith("/7/remote") })
    }

    @Test
    fun detectsDroidDeckSteamCompatibilityAndNativeSteamSaves() {
        val root = "/data/user/0/com.droiddeck.launcher/files"
        val steam = "$root/linuxfs/root/.local/share/Steam"
        val report = SaveSourceDetector.scanDirectories(
            root,
            listOf(
                root,
                "$steam/steamapps/compatdata/1245620/pfx/drive_c/users/steamuser/AppData/Roaming/EldenRing/76561198131641081",
                "$steam/steamapps/compatdata/1876890/pfx/drive_c/users/steamuser/Local Settings/Application Data/Wandering_Sword/Saved/76561198131641081/SaveGames",
                "$steam/steamapps/common/Portal/portal/save",
                "$steam/steamapps/common/Proton Experimental (ARM64)/files/share/default_pfx_arm64/drive_c/users/steamuser/Saved Games",
                "$steam/steamapps/compatdata/0/pfx/drive_c/users/steamuser/Saved Games",
            ),
        )

        assertEquals(3, report.gameHubPcCount)
        assertEquals(3, report.gameCount)
        assertTrue(report.detected.any { it.displayName == "Elden Ring" && it.gameKey == "steam:1245620" })
        assertTrue(report.detected.any { it.displayName == "Wandering_Sword" })
        assertTrue(report.detected.any { it.displayName == "Portal" })
        assertTrue(report.detected.none { it.relativePath.contains("Proton Experimental") || it.relativePath.contains("compatdata/0") })
    }

    @Test
    fun detectsDedicatedBloodborneSaveWithoutIncludingGameFilesDirectory() {
        val root = "/data/user/0/com.ducvd89.bloodborne"
        val report = SaveSourceDetector.scanDirectories(
            root,
            listOf(
                root,
                "$root/files",
                "$root/shared_prefs",
                "$root/files/bbport/user/savedata",
            ),
            androidGames = mapOf(
                "com.ducvd89.bloodborne" to AndroidGameMetadata(
                    "com.ducvd89.bloodborne",
                    "Bloodborne",
                ),
            ),
        )

        assertEquals(1, report.gameCount)
        assertEquals(1, report.detected.size)
        assertEquals("files/bbport/user/savedata", report.detected.single().relativePath)
        assertFalse(report.detected.single().candidate)
    }

    @Test
    fun broadSharedStorageSkipsDedicatedAndroidSourcesAndKeepsUnknownEmulators() {
        val root = "/storage/emulated/0"
        val report = SaveSourceDetector.scanDirectories(
            root,
            listOf(
                root,
                "$root/eden/nand/user/save/0000000000000000/account/0100000000010000",
                "$root/Download/Eden-migration-final-20261019/nand/user/save/0000000000000000/account/0100000000010000",
                "$root/OpenSaveIntegrationTest/nand/user/save/0000000000000000/account/0100000000010000",
                "$root/Citra/saves",
                "$root/Android/data/com.poncle.vampiresurvivors/files",
            ),
            androidGames = mapOf(
                "com.poncle.vampiresurvivors" to AndroidGameMetadata(
                    "com.poncle.vampiresurvivors",
                    "Vampire Survivors",
                ),
            ),
        )

        assertTrue(report.detected.any { it.kind == "citra" && it.relativePath == "Citra/saves" })
        assertTrue(report.detected.none { it.gameKey == "android:com.poncle.vampiresurvivors" })
        assertTrue(report.detected.none { it.titleId == "0100000000010000" })
        assertTrue(report.detected.none { it.relativePath.contains("Eden-migration-final") || it.relativePath.contains("OpenSaveIntegrationTest") })
    }
}
