package com.jun9100.opensave

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotEngineTest {
    @Test
    fun snapshotRoundTripPreservesFilesAndEmptyDirectories() {
        val root = createTempDir(prefix = "opensave-snapshot-")
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "存档/save.dat").apply {
                parentFile.mkdirs()
                writeText("save-data")
            }
            File(source, "empty").mkdirs()
            val archive = File(root, "snapshot.zip")
            val restored = File(root, "restored")

            val build = SnapshotEngine().createSnapshot(source, archive).getOrThrow()
            SnapshotEngine().restoreSnapshot(archive, restored).getOrThrow()

            assertEquals(1, build.fileCount)
            assertEquals("save-data", File(restored, "存档/save.dat").readText())
            assertTrue(File(restored, "empty").isDirectory)
            assertTrue(build.archiveSha256.length == 64)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun restoreRejectsZipSlipBeforeWritingOutsideTarget() {
        val root = createTempDir(prefix = "opensave-zip-slip-")
        try {
            val archive = File(root, "malicious.zip")
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("../../outside.txt"))
                zip.write("bad".toByteArray())
                zip.closeEntry()
            }
            val target = File(root, "target")
            val result = SnapshotEngine().restoreSnapshot(archive, target)

            assertTrue(result.isFailure)
            assertFalse(File(root.parentFile, "outside.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
