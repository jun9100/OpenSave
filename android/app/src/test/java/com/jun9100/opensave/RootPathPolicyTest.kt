package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPathPolicyTest {
    @Test
    fun canonicalizesAbsolutePathsAndKeepsNestedSaveDirectoriesAllowed() {
        val path = RootPathPolicy.canonicalize("/storage/emulated/0/Android/data/com.example/../com.example/files")

        assertEquals("/storage/emulated/0/Android/data/com.example/files", path)
    }

    @Test
    fun rejectsBroadOrProtectedRoots() {
        listOf("/", "/data", "/data/user/0", "/storage/emulated/0", "/system/bin").forEach { path ->
            assertTrue(runCatching { RootPathPolicy.canonicalize(path) }.isFailure)
        }
    }

    @Test
    fun rejectsRelativeAndNulPaths() {
        assertTrue(runCatching { RootPathPolicy.canonicalize("Android/data") }.isFailure)
        assertTrue(runCatching { RootPathPolicy.canonicalize("/data/local/tmp\u0000save") }.isFailure)
    }

    @Test
    fun shellQuotesSingleQuotesWithoutAllowingCommandInjection() {
        assertEquals("'save'\"'\"'file'", RootCommand.shellQuote("save'file"))
    }
}
