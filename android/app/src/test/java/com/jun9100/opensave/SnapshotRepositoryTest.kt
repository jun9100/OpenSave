package com.jun9100.opensave

import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
                saveType = GameSaveTypes.PPSSPP.id,
                cloudGameId = "android-emu-1234567890abcdef",
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

    @Test
    fun oneProcessWideInstancePerDirectory() {
        val root = createTempDir(prefix = "opensave-index-shared-")
        try {
            val first = SnapshotRepository.shared(root)
            val second = SnapshotRepository.shared(File(root, "../${root.name}"))
            assertSame(first, second)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writesLeaveOnlyTheIndexAndRemoveAbandonedTemporaryFiles() {
        val root = createTempDir(prefix = "opensave-index-atomic-")
        try {
            // A temporary index from a process killed mid-write must never be read or kept.
            File(root, ".index-123.part").writeText("partial")
            val repository = SnapshotRepository(root)
            repository.add(record("snap_1", createdAt = 1))
            repository.add(record("snap_2", createdAt = 2))
            repository.markUploaded("snap_1")

            assertEquals(listOf("index.tsv"), root.list()!!.sorted())
            assertEquals(listOf("snap_2", "snap_1"), repository.list().map(SnapshotRecord::id))
            assertTrue(repository.list().single { it.id == "snap_1" }.uploaded)
            assertFalse(File(root, ".index-123.part").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun separateInstancesForTheSameDirectoryNeverLoseWrites() {
        val root = createTempDir(prefix = "opensave-index-concurrent-")
        try {
            val repositories = listOf(SnapshotRepository(root), SnapshotRepository(File(root.absolutePath)))
            val start = CountDownLatch(1)
            val writers = repositories.mapIndexed { index, repository ->
                thread {
                    start.await()
                    repeat(25) { item ->
                        repository.add(record("snap_${index}_$item", createdAt = (index * 100 + item).toLong()))
                    }
                }
            }
            start.countDown()
            writers.forEach(Thread::join)

            assertEquals(50, SnapshotRepository(root).list().size)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun record(id: String, createdAt: Long) = SnapshotRecord(
        id = id,
        sourceId = "source",
        remoteName = "$id.zip",
        localPath = "$id.zip",
        createdAtMillis = createdAt,
        sizeBytes = 1,
        sha256 = "a",
        manifestHash = "b",
    )
}
