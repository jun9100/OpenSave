package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledGamesTest {
    // 取自 baa6f38c 上 Eden 的真实配置：两个游戏目录都是 SAF tree URI。
    private val edenConfig = """
        [UI]
        Paths\gamedirs\size=2
        Paths\gamedirs\1\path="content://com.android.externalstorage.documents/tree/primary%3Aswitch1-games"
        Paths\gamedirs\1\deep_scan\default=true
        Paths\gamedirs\1\deep_scan=false
        Paths\gamedirs\2\path="content://com.android.externalstorage.documents/tree/primary%3Aswitch2-games"
        Paths\gamedirs\2\deep_scan\default=true
    """.trimIndent()

    @Test
    fun `eden game dirs come back as absolute paths`() {
        assertEquals(
            listOf("/storage/emulated/0/switch1-games", "/storage/emulated/0/switch2-games"),
            InstalledGames.gameDirsFromEdenConfig(edenConfig),
        )
    }

    @Test
    fun `eden game dirs ignore the size line and unreachable forms`() {
        val config = """
            Paths\gamedirs\size=1
            Paths\gamedirs\1\path=/sdcard/my games
            Paths\gamedirs\1\deep_scan=false
        """.trimIndent()
        assertEquals(listOf("/sdcard/my games"), InstalledGames.gameDirsFromEdenConfig(config))
        assertEquals(emptyList<String>(), InstalledGames.gameDirsFromEdenConfig(""))
        // 认不出的形态直接丢掉，而不是当成相对路径塞进存储层。
        assertEquals(
            emptyList<String>(),
            InstalledGames.gameDirsFromEdenConfig("""Paths\gamedirs\1\path="has no scheme""""),
        )
    }

    @Test
    fun `tree uris decode primary and sdcard volumes`() {
        assertEquals(
            "/storage/emulated/0/switch1-games",
            InstalledGames.pathOfTreeUri(
                "content://com.android.externalstorage.documents/tree/primary%3Aswitch1-games",
            ),
        )
        assertEquals(
            "/storage/emulated/0/switch1-games",
            InstalledGames.pathOfTreeUri(
                "content://com.android.externalstorage.documents/tree/primary%3Aswitch1-games/document/primary%3Aswitch1-games",
            ),
        )
        assertEquals(
            "/storage/1A2B-3C4D/roms/switch",
            InstalledGames.pathOfTreeUri(
                "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3Aroms%2Fswitch",
            ),
        )
        // 百分号转义里的 + 是字面量，不是空格。
        assertEquals(
            "/storage/emulated/0/Hades+",
            InstalledGames.pathOfTreeUri(
                "content://com.android.externalstorage.documents/tree/primary%3AHades+",
            ),
        )
        assertNull(InstalledGames.pathOfTreeUri("/storage/emulated/0/switch1-games"))
        assertNull(InstalledGames.pathOfTreeUri("content://x/tree/no-colon"))
    }

    @Test
    fun `game files prove a title is installed and the log proves the rest`() {
        val evidence = InstallEvidence(
            switchNames = listOf(
                "13 Sentinels Aegis Rim [01008D7016438000] [v1.00] (MOD11.0.0).xci",
                "Luigis Mansion 3[v1.3.0][0100DCA0064A6000][US][v262144](1G+1U+2D).xci",
                // 文件名里没有 title id 的镜像，只能靠日志。
                "SUPER MARIO ODYSSEY [1.3.0].xci",
                "04007EF01F8AE000-work",
            ),
            switchText = "Loaded game: title_id=0100000000010000",
        )
        assertTrue(evidence.provesSwitch("01008D7016438000"))
        assertTrue(evidence.provesSwitch("0100dca0064a6000"))
        assertTrue(evidence.provesSwitch("04007EF01F8AE000"))
        assertTrue(evidence.provesSwitch("0100000000010000"))
        // 这三款云端的游戏本机根本没装。
        assertFalse(evidence.provesSwitch("010060A00B53C000"))
        assertFalse(evidence.provesSwitch("0100152000022000"))
        assertFalse(evidence.provesSwitch("0100BA001821A000"))
    }

    @Test
    fun `psp evidence reads iso names but ignores the pulled SAVEDATA folder`() {
        val evidence = InstallEvidence(
            pspNames = listOf("ULUS10041.iso", "NPJH00123"),
            pspText = "GamePath0 = /storage/emulated/0/PSP/ISO/Tekken.iso",
        )
        assertTrue(evidence.provesPsp("ULUS10041"))
        assertTrue(evidence.provesPsp("NPJH00123"))
        // 只有 SAVEDATA/<id> 目录存在不算证据：那是下发自己建的。
        val pulled = InstallEvidence(pspNames = listOf("SAVEDATA", "SYSTEM"))
        assertFalse(pulled.provesPsp("ULUS10041"))
        // 只有目录名、没有任何 id：这份证据是"空"的，下发会整体跳过而不是照着建。
        assertFalse(pulled.provesPsp("NPJH00123") && pulled.pspNames.isNotEmpty())
    }

    @Test
    fun `short or blank ids never match everything`() {
        val evidence = InstallEvidence(switchNames = listOf("a", "b"))
        assertFalse(evidence.provesSwitch(""))
        assertFalse(evidence.provesSwitch("0100"))
        assertFalse(InstalledGames.namesContain(listOf("anything"), ""))
        assertTrue(InstallEvidence.EMPTY.isEmpty)
    }

    @Test
    fun `ryujinx game dirs are read out of its json config`() {
        val config = """
            {
              "GameDirs": [
                { "Path": "/storage/emulated/0/switch1-games" },
                { "Path": "/storage/emulated/0/switch2-games" }
              ],
              "Other": [ "not-a-dir" ]
            }
        """.trimIndent()
        assertEquals(
            listOf("/storage/emulated/0/switch1-games", "/storage/emulated/0/switch2-games"),
            InstalledGames.gameDirsFromRyujinxConfig(config),
        )
        assertEquals(emptyList<String>(), InstalledGames.gameDirsFromRyujinxConfig("{}"))
        assertEquals(emptyList<String>(), InstalledGames.gameDirsFromRyujinxConfig("not json"))
    }
}
