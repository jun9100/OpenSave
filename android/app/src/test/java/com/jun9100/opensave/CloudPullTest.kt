package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPullTest {
    private val eden = LocalSaveRoot(
        label = "Eden Switch · Android/data",
        path = "/storage/emulated/0/Android/data/dev.eden.eden_emulator.nightly/files",
        family = SaveRootFamily.YUZU_SWITCH,
    )
    private val ryujinx = LocalSaveRoot(
        label = "Ryujinx",
        path = "/storage/emulated/0/Android/data/org.ryujinx.ryujinx/files",
        family = SaveRootFamily.RYUJINX_SWITCH,
    )
    private val psp = LocalSaveRoot(
        label = "PPSSPP",
        path = "/storage/emulated/0/PSP",
        family = SaveRootFamily.PPSSPP,
    )

    @Test
    fun `switch title id parses only the cross-device prefix`() {
        assertEquals("0100F2C0115B6000", CloudPull.titleIdOf("switch-0100f2c0115b6000"))
        assertEquals("0100F2C0115B6000", CloudPull.titleIdOf("switch-0100F2C0115B6000"))
        // 云端按身份分组后的形态，以及作用域 id 带 scope: 前缀的形态。
        assertEquals("0100F2C0115B6000", CloudPull.titleIdOf("title:0100f2c0115b6000"))
        assertEquals("0100F2C0115B6000", CloudPull.titleIdOf("switch:0100F2C0115B6000"))
        assertEquals("0100F2C0115B6000", CloudPull.titleIdOf("scope:switch-0100F2C0115B6000"))
        // Android 设备本地作用域的 hash id 不跨设备，不能拿来推 title id。
        assertNull(CloudPull.titleIdOf("android-switch-0123456789abcdef"))
        assertNull(CloudPull.titleIdOf("switch-0100F2C0115B60"))
        assertNull(CloudPull.titleIdOf("pc/scope:logs"))
    }

    @Test
    fun `psp game id parses every prefix the cloud uses`() {
        assertEquals("ULUS10041", CloudPull.pspIdOf("ppsspp-ulus10041"))
        assertEquals("ULUS10041", CloudPull.pspIdOf("psp:ULUS10041"))
        assertEquals("ULUS10041", CloudPull.pspIdOf("scope:psp:ULUS10041"))
        assertEquals("ULUS10041", CloudPull.pspIdOf("emulator-ppsspp-ULUS10041"))
        assertEquals("ULUS10041", CloudPull.pspIdOf("psp-ULUS10041"))
        assertNull(CloudPull.pspIdOf("ppsspp-ULUS1004"))
        assertNull(CloudPull.pspIdOf("switch-0100F2C0115B6000"))
    }

    @Test
    fun `relative save path matches the layout each family writes`() {
        val title = "0100F2C0115B6000"
        assertEquals(
            "nand/user/save/0000000000000000/$title",
            CloudPull.relativePath(SaveRootFamily.YUZU_SWITCH, title),
        )
        assertEquals(
            "bis/user/save/0000000000000000/$title",
            CloudPull.relativePath(SaveRootFamily.RYUJINX_SWITCH, title),
        )
        assertEquals("SAVEDATA/ULUS10041", CloudPull.relativePath(SaveRootFamily.PPSSPP, "ulus10041"))
        // 家族与 id 类型不匹配时不产出路径。
        assertNull(CloudPull.relativePath(SaveRootFamily.PPSSPP, title))
        assertNull(CloudPull.relativePath(SaveRootFamily.YUZU_SWITCH, "ULUS10041"))
    }

    @Test
    fun `a cloud switch game missing locally becomes one candidate per root`() {
        val game = CloudPullGame(GameSaveTypes.SWITCH, "android-switch-abc", "title:0100f2c0115b6000", "Zelda")
        val candidates = CloudPull.candidates(listOf(eden, ryujinx, psp), listOf(game)) { _, _ -> false }

        assertEquals(2, candidates.size)
        assertEquals(
            listOf("switch:0100F2C0115B6000", "switch:0100F2C0115B6000"),
            candidates.map { it.gameKey },
        )
        assertEquals(
            listOf(
                "nand/user/save/0000000000000000/0100F2C0115B6000",
                "bis/user/save/0000000000000000/0100F2C0115B6000",
            ),
            candidates.map { it.relativePath },
        )
        assertTrue(candidates.all { it.kind != "ppsspp" })
    }

    @Test
    fun `a game that already has local saves is left to the normal restore flow`() {
        val game = CloudPullGame(GameSaveTypes.SWITCH, "android-switch-abc", "title:0100F2C0115B6000", "Zelda")
        val existing = "nand/user/save/0000000000000000/0100F2C0115B6000"
        val candidates = CloudPull.candidates(listOf(eden), listOf(game)) { rootPath, relative ->
            rootPath == eden.path && relative == existing
        }
        assertEquals(emptyList<CloudPullCandidate>(), candidates)
    }

    @Test
    fun `psp saves land in the SAVEDATA folder of the PSP root`() {
        val game = CloudPullGame(GameSaveTypes.PPSSPP, "scope:ppsspp-ULUS10041", "psp:ULUS10041", "Lumines")
        val candidates = CloudPull.candidates(listOf(eden, psp), listOf(game)) { _, _ -> false }

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals(psp.path, candidate.root.path)
        assertEquals("SAVEDATA/ULUS10041", candidate.relativePath)
        assertEquals("ppsspp", candidate.kind)
        assertEquals("path:ppsspp::PPSSPP:SAVEDATA/ULUS10041", candidate.gameKey)
    }

    @Test
    fun `local-scope ids are ignored while PC side switch archives are pulled`() {
        val games = listOf(
            CloudPullGame(GameSaveTypes.SWITCH, "android-switch-0123456789abcdef", "scope:android-switch-0123456789abcdef", "本机作用域"),
            // PC 客户端把 Switch 存档存在 pc 作用域里，但 id 仍是 switch-<TITLE_ID>，
            // 这类正是要下发到 Eden 的东西。
            CloudPullGame(GameSaveTypes.PC, "scope:switch-0100000000010000", "scope:switch-0100000000010000", "PC 端的 Switch 存档"),
            CloudPullGame(GameSaveTypes.GENERIC, "whatever", "whatever", "无关"),
        )
        val candidates = CloudPull.candidates(listOf(eden, psp), games) { _, _ -> false }
        assertEquals(1, candidates.size)
        assertEquals("switch:0100000000010000", candidates.single().gameKey)
        assertEquals(
            "nand/user/save/0000000000000000/0100000000010000",
            candidates.single().relativePath,
        )
    }

    @Test
    fun `candidates are ordered by name then path`() {
        val games = listOf(
            CloudPullGame(GameSaveTypes.SWITCH, "a", "title:0000000000000002", "B"),
            CloudPullGame(GameSaveTypes.SWITCH, "a", "title:0000000000000001", "A"),
        )
        val candidates = CloudPull.candidates(listOf(eden, ryujinx), games) { _, _ -> false }
        assertEquals(4, candidates.size)
        assertEquals("A", candidates[0].game.displayName)
        assertEquals("A", candidates[1].game.displayName)
        assertEquals("B", candidates[2].game.displayName)
    }
}
