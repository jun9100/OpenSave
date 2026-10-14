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
}
