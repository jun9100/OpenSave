package com.jun9100.opensave

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotRepositoryTest {
    @Test
    fun recordsSurviveReloadAndKeepSpecialCharacters() {
        val root = createTempDir(prefix = "opensave-index-")
        try {
            val record = SnapshotRecord(
                id = "snap_1",
                sourceId = "android-source",
                remoteName = "game__main__snap_1.zip",
                localPath = "game__main__snap_1.zip",
                createdAtMillis = 1,
                sizeBytes = 42,
                sha256 = "a".repeat(64),
                manifestHash = "b".repeat(64),
                comment = "恢复前\t安全副本",
                gameKey = "steam:123456",
                gameName = "示例游戏",
            )
            val repository = SnapshotRepository(root)
            repository.add(record)
            repository.markUploaded(record.id)

            val reloaded = SnapshotRepository(File(root.absolutePath))
            val actual = reloaded.list().single()
            assertEquals(record.copy(uploaded = true), actual)
            assertTrue(reloaded.fileFor(actual).path.endsWith(actual.localPath))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readsLegacyTenColumnSnapshotRecords() {
        val root = createTempDir(prefix = "opensave-legacy-index-")
        try {
            val values = listOf("snap_legacy", "source", "legacy.zip", "legacy.zip", "1", "42", "a", "b", "false", "")
            val encoded = values.joinToString("\t") { value ->
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
            }
            File(root, "index.tsv").writeText(encoded)

            val record = SnapshotRepository(root).list().single()

            assertEquals(null, record.gameKey)
            assertEquals("", record.gameName)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun snapshotFilesCannotEscapeTheRepository() {
        val root = createTempDir(prefix = "opensave-index-path-")
        try {
            SnapshotRepository(root).fileFor(
                SnapshotRecord("snap", "source", "../outside.zip", "../outside.zip", 1, 0, "", ""),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
