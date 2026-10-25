package com.jun9100.opensave

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSaveCloudProtocolTest {
    @Test
    fun writesDesktopCompatibleHeadArchive() {
        val root = createTempDirectory("opensave-head-").toFile()
        try {
            val snapshot = "snap_1730000000000"
            val archive = "switch-0100000000010000__main__${snapshot}.zip"
            val head = GameSaveCloudHead(
                deviceId = "android-device-123",
                deviceName = "Odin2",
                gameId = "switch-0100000000010000",
                snapshot = snapshot,
                file = archive,
                chain = listOf("snap_1729999999000", snapshot),
                savedAt = "2026-10-24T00:00:00Z",
            )
            val file = File(root, GameSaveCloudProtocol.headFileName(head.gameId, head.deviceId, 1730000000001))

            GameSaveCloudProtocol.writeHead(file, head)

            assertEquals(
                "switch-0100000000010000__head-androiddevice123-1730000000001.zip",
                file.name,
            )
            assertEquals(head, GameSaveCloudProtocol.readHead(file))
            assertEquals(head.gameId, GameSaveCloudProtocol.parseHeadFileName(file.name))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun parsesOnlySafePortableSnapshotNames() {
        val parsed = GameSaveCloudProtocol.parseArchiveName(
            "switch-0100000000010000__main__snap_1730000000000.zip",
        )

        requireNotNull(parsed)
        assertEquals("switch-0100000000010000", parsed.gameId)
        assertEquals("main", parsed.branch)
        assertEquals("snap_1730000000000", parsed.snapshotId)
        assertEquals(1730000000000L, parsed.timestampMillis)
        assertEquals(null, GameSaveCloudProtocol.parseArchiveName("switch-game__main__manual.zip"))
        assertEquals(null, GameSaveCloudProtocol.parseArchiveName("../switch-game__main__snap_1.zip"))
        assertEquals("other", GameSaveCloudProtocol.parseArchiveName("switch-game__other__snap_1.zip")?.branch)
        assertEquals(null, GameSaveCloudProtocol.parseArchiveName("switch-game__feature/test__snap_1.zip"))
    }

    @Test
    fun retainsOnlyTheNewestBoundedChain() {
        val chain = (1..GameSaveCloudProtocol.MAX_HEAD_CHAIN)
            .map { "snap_$it" }
        val next = GameSaveCloudProtocol.appendChain(chain, "snap_65")

        assertEquals(GameSaveCloudProtocol.MAX_HEAD_CHAIN, next.size)
        assertFalse(next.contains("snap_1"))
        assertEquals("snap_65", next.last())
        assertEquals(next, GameSaveCloudProtocol.appendChain(next, "snap_65"))
        assertTrue(GameSaveCloudProtocol.deviceKey("A_B-C 12").contains("abc12"))
    }
}
