package com.jun9100.opensave

import java.io.File
import org.junit.Assert.assertEquals
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
    fun marksAnonymousSteamUserSavedGamesAndSteamAppIdsAsCandidates() {
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

        assertEquals(4, report.candidateCount)
        assertEquals(0, report.gameCount)
        assertEquals(4, report.gameHubPcCount)
        assertEquals(3, report.detected.count { it.displayName == "未命名 PC 存档" })
        assertTrue(report.detected.any { it.displayName == "Steam AppID 1245620" && it.candidate })
    }
}
