package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「所有文件访问」后端的路径规则。这些用例是纯 JVM 的：不需要设备，也不需要
 * 真的授予权限——[AllFilesPathPolicy] 只做规范化和边界检查，权限本身由
 * [AllFilesStorage] 的可注入判定读取。
 */
class AllFilesPathPolicyTest {
    @Test
    fun `canonicalize keeps primary storage paths and drops the trailing slash`() {
        assertEquals(
            "/storage/emulated/0/Android/data/dev.eden.eden_emulator/files",
            AllFilesPathPolicy.canonicalize("/storage/emulated/0/Android/data/dev.eden.eden_emulator/files/"),
        )
    }

    @Test
    fun `canonicalize resolves dot segments inside primary storage`() {
        assertEquals(
            "/storage/emulated/0/Android/data",
            AllFilesPathPolicy.canonicalize("/storage/emulated/0/Android/obb/../data"),
        )
    }

    @Test
    fun `canonicalize rejects paths outside primary storage`() {
        assertThrows(IllegalArgumentException::class.java) {
            AllFilesPathPolicy.canonicalize("/data/user/0/com.jun9100.opensave/files")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AllFilesPathPolicy.canonicalize("/storage/emulated/0/Android/data/../../../../etc")
        }
    }

    @Test
    fun `canonicalize rejects relative paths`() {
        assertThrows(IllegalArgumentException::class.java) {
            AllFilesPathPolicy.canonicalize("Android/data")
        }
    }

    @Test
    fun `canonicalize treats an empty path as primary storage`() {
        assertEquals(AllFilesPathPolicy.PRIMARY_ROOT, AllFilesPathPolicy.canonicalize(""))
    }

    @Test
    fun `requireInside joins a relative path under the scanned root`() {
        val target = AllFilesPathPolicy.requireInside(
            "/storage/emulated/0/Android/data/dev.eden.eden_emulator/files",
            "nand/user/save/0000000000000000/0100F2C0115B6000",
        )
        assertEquals(
            File("/storage/emulated/0/Android/data/dev.eden.eden_emulator/files/nand/user/save/0000000000000000/0100F2C0115B6000"),
            target,
        )
    }

    @Test
    fun `requireInside rejects escapes and absolute paths`() {
        val root = "/storage/emulated/0/Android/data/dev.eden.eden_emulator/files"
        assertThrows(IllegalArgumentException::class.java) {
            AllFilesPathPolicy.requireInside(root, "../keys")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AllFilesPathPolicy.requireInside(root, "/storage/emulated/0/other")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AllFilesPathPolicy.requireInside(root, "nand/../../keys")
        }
    }

    @Test
    fun `requireInside keeps nested paths under the root`() {
        val root = "/storage/emulated/0/Android/data/dev.eden.eden_emulator/files/nand"
        val target = AllFilesPathPolicy.requireInside(root, "user/save/0000000000000000/0100F2C0115B6000")
        assertTrue(target.path.startsWith("$root/user/save/"))
    }
}
