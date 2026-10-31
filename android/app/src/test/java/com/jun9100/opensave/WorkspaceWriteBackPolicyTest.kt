package com.jun9100.opensave

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWriteBackPolicyTest {
    private val saved = "/data/user/0/com.example.emulator/files"

    @Test
    fun anUnsavedRootPathIsNeverATarget() {
        assertTrue(WorkspaceWriteBackPolicy.rootTarget(saved, null).isFailure)
        assertTrue(WorkspaceWriteBackPolicy.rootTarget(saved, " ").isFailure)
    }

    @Test
    fun aDraftThatDiffersFromTheSavedPathIsRejected() {
        val result = WorkspaceWriteBackPolicy.rootTarget("/data/user/0/com.other/files", saved)
        assertTrue(result.isFailure)
        assertTrue(WorkspaceWriteBackPolicy.rootTarget("", saved).isFailure)
    }

    @Test
    fun theSavedPathIsUsedWhenTheDraftMatchesIt() {
        assertEquals(saved, WorkspaceWriteBackPolicy.rootTarget("$saved/", saved).getOrThrow())
        assertEquals(saved, WorkspaceWriteBackPolicy.rootTarget(saved, saved).getOrThrow())
    }

    @Test
    fun anEmptyOrMissingWorkspaceIsNeverWrittenBack() {
        val root = createTempDir(prefix = "opensave-write-back-")
        try {
            val workspace = File(root, "source-workspace")
            assertTrue(runCatching { WorkspaceWriteBackPolicy.requireWorkspace(workspace) }.isFailure)
            workspace.mkdirs()
            assertTrue(runCatching { WorkspaceWriteBackPolicy.requireWorkspace(workspace) }.isFailure)
            File(workspace, "save.dat").writeText("save")
            WorkspaceWriteBackPolicy.requireWorkspace(workspace)
        } finally {
            root.deleteRecursively()
        }
    }
}
