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
}
