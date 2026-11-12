package com.jun9100.opensave

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 冷启动快照：编码往返、指纹复用判定、两条 find/stat 输出格式的解析，
 * 以及「全盘补漏」的剪枝名单。
 */
class ScanSnapshotTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun report(vararg saves: DetectedSave) = SaveScanReport(
        rootPath = "/storage/emulated/0/Android/data/dev.eden.eden_emulator.nightly/files",
        scannedDirectoryCount = 61,
        detected = saves.toList(),
        unreadableDirectoryCount = 2,
        truncated = false,
    )

    private fun switchSave(id: String) = DetectedSave(
        kind = "switch",
        relativePath = "nand/user/save/0000000000000000/$id",
        titleId = id,
        note = "Switch 存档",
        displayName = "十三机兵",
        gameKey = "switch:$id",
    )

    @Test
    fun `a snapshot survives a round trip through json`() {
        val source = SourceSelection.Root("/storage/emulated/0/Android/data/dev.eden.eden_emulator.nightly/files")
        val snapshot = LibraryScanSnapshot(
            savedAt = 1_700_000_000_000L,
            sources = listOf(
                SourceScanSnapshot(
                    result = SourceScanResult(
                        source = source,
                        label = "Eden Nightly · Android/data",
                        scan = report(switchSave("01008D7016438000"), switchSave("010060A00B53C000").copy(candidate = true)),
                        access = SourceAccessStatus.allFilesAccessible(),
                    ),
                    fingerprint = listOf("/a\t1", "/a/b\t2"),
                ),
            ),
        )

        val decoded = ScanSnapshotCodec.decode(ScanSnapshotCodec.encode(snapshot))

        assertNotNull(decoded)
        requireNotNull(decoded)
        assertEquals(1_700_000_000_000L, decoded.savedAt)
        val entry = decoded.sources.single()
        assertEquals(source, entry.result.source)
        assertEquals("Eden Nightly · Android/data", entry.result.label)
        assertEquals(listOf("/a\t1", "/a/b\t2"), entry.fingerprint)
        val scan = requireNotNull(entry.result.scan)
        assertEquals("nand/user/save/0000000000000000/01008D7016438000", scan.detected.first().relativePath)
        assertEquals("十三机兵", scan.detected.first().displayName)
        assertFalse(scan.detected.first().candidate)
        assertTrue(scan.detected[1].candidate)
        assertEquals(2, scan.unreadableDirectoryCount)
        assertTrue(entry.result.access.readable)
    }

    @Test
    fun `a skipped source keeps its reason and an unknown source kind is dropped`() {
        // SAF 来源在 JVM 单测里造不出来（`android.net.Uri.parse` 没有实现），
        // 这里用手写 JSON 覆盖解码侧的鲁棒性：缺字段、认不出的 kind 都不能炸。
        val json = JSONObject().apply {
            put("version", ScanSnapshotCodec.VERSION)
            put("savedAt", 7L)
            put(
                "sources",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("source", JSONObject().put("kind", "root").put("path", "/storage/emulated/0/PSP"))
                            put("label", "PPSSPP")
                            put("skippedReason", "授权失效")
                            put(
                                "access",
                                JSONObject().apply {
                                    put("capability", "UNAVAILABLE")
                                    put("required", "ROOT")
                                    put("readable", false)
                                    put("writable", false)
                                    put("reason", "授权失效")
                                },
                            )
                        },
                    )
                    put(JSONObject().put("source", JSONObject().put("kind", "telepathy")))
                    put(JSONObject().put("label", "没有 source"))
                },
            )
        }.toString()

        val decoded = requireNotNull(ScanSnapshotCodec.decode(json))
        val result = decoded.sources.single().result
        assertEquals("/storage/emulated/0/PSP", (result.source as SourceSelection.Root).path)
        assertEquals("授权失效", result.skippedReason)
        assertEquals("授权失效", result.access.reason)
        assertFalse(result.access.readable)
        assertNull(result.scan)
        assertNull(decoded.sources.single().fingerprint)
    }

    @Test
    fun `a snapshot from another schema version is ignored`() {
        val text = ScanSnapshotCodec.encode(LibraryScanSnapshot(1L, listOf())).replace("\"version\":1", "\"version\":99")
        assertNull(ScanSnapshotCodec.decode(text))
        assertNull(ScanSnapshotCodec.decode("not json"))
        assertNull(ScanSnapshotCodec.decode("{\"version\":1,\"sources\":[]}"))
    }

    @Test
    fun `the store keeps the last snapshot around and clears it`() {
        val directory = temporary.newFolder("scan")
        val store = ScanSnapshotStore(directory)
        assertNull(store.load())

        store.save(
            LibraryScanSnapshot(
                savedAt = 42L,
                sources = listOf(
                    SourceScanSnapshot(
                        result = SourceScanResult(
                            source = SourceSelection.Root("/storage/emulated/0/PSP"),
                            label = "PPSSPP",
                            scan = report(),
                        ),
                        fingerprint = listOf("/x\t1"),
                    ),
                ),
            ),
        )

        val loaded = requireNotNull(store.load())
        assertEquals(42L, loaded.savedAt)
        assertEquals("/storage/emulated/0/PSP", (loaded.sources.single().result.source as SourceSelection.Root).path)

        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `a fingerprint only skips the scan when it is present and equal`() {
        assertTrue(ScanReuse.canSkip(listOf("a\t1"), listOf("a\t1")))
        assertFalse(ScanReuse.canSkip(listOf("a\t1"), listOf("a\t2")))
        assertFalse(ScanReuse.canSkip(null, listOf("a\t1")))
        // 空指纹算不出来（SAF、无权限）：放过去就等于永不重扫。
        assertFalse(ScanReuse.canSkip(emptyList(), emptyList()))
        assertFalse(ScanReuse.canSkip(listOf("a\t1"), emptyList()))
    }

    @Test
    fun `both find and stat stamp formats are parsed`() {
        assertEquals("/a\t1700000000000", DirStamps.parseLine("1700000000.000000000 /a"))
        assertEquals("/a\t1700000000000", DirStamps.parseLine("1700000000 /a"))
        assertEquals("/with space/b\t1700000000123", DirStamps.parseLine("1700000000.123 /with space/b"))
        assertEquals("/a\t1700000000000", DirStamps.parseLine("  1700000000 /a  "))
        assertNull(DirStamps.parseLine(""))
        assertNull(DirStamps.parseLine("/a"))
        assertNull(DirStamps.parseLine("abc /a"))
    }

    @Test
    fun `only the shared storage root prunes subtrees`() {
        assertTrue(AllFilesPathPolicy.isSharedStorageRoot("/storage/emulated/0"))
        assertTrue(AllFilesPathPolicy.isSharedStorageRoot("/storage/emulated/0/"))
        assertFalse(AllFilesPathPolicy.isSharedStorageRoot("/storage/emulated/0/Android/data/com.winlator/files"))
    }

    @Test
    fun `a directory holding game images is recognised by the image extension`() {
        // 名字救不了：`switch2-games/Fire Emblem꞉ Fortune's Weave` 有 23,077 个目录、
        // 名字毫无特征，是解包出来的游戏本体；旁边躺着 28.9 GB 的 `.nup`。
        for (name in listOf("game.nup", "Xenoblade.nsp", "a.XCI", "disc.iso", "x.cso", "y.chd", "z.rvz")) {
            assertTrue("应该认出：$name", AllFilesPathPolicy.holdsGameImage(name))
        }
        for (name in listOf("SAVEDATA", "0000000000000000", "save.dat", "profile", ".nomedia", "noext", "trailing.")) {
            assertFalse("不该认出：$name", AllFilesPathPolicy.holdsGameImage(name))
        }
    }

    @Test
    fun `rom trees media and caches are skipped but save layouts are not`() {
        for (name in listOf("04007EF01F8AE000-work", "DCIM", "Pictures", "cache", "code_cache", ".thumbnails", "obb")) {
            assertTrue("应该跳过：$name", AllFilesPathPolicy.skipWholeDeviceSubtree(name))
        }
        // 存档本身的布局绝不能进黑名单：Eden 的 save 根、PPSSPP 的 SAVEDATA、
        // 以及游戏目录（里面放着存档时也得能扫到）。
        for (name in listOf("nand", "save", "0000000000000000", "01008D7016438000", "SAVEDATA", "switch1-games")) {
            assertFalse("不该跳过：$name", AllFilesPathPolicy.skipWholeDeviceSubtree(name))
        }
    }
}
