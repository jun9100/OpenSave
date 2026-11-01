package com.jun9100.opensave

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun aHeldWorkspaceIsRecoveredBeforeLeftoversAreDeleted() {
        val root = createTempDir(prefix = "opensave-write-back-held-")
        try {
            val workspace = File(root, "source-workspace")
            val held = File(root, "${WorkspaceWriteBackPolicy.HELD_PREFIX}a").apply { mkdirs() }
            File(held, "save.dat").writeText("mine")
            val discarded = File(root, "${WorkspaceWriteBackPolicy.DISCARDED_PREFIX}b").apply { mkdirs() }
            File(discarded, "save.dat").writeText("source copy")

            WorkspaceWriteBackPolicy.recoverHeldWorkspace(workspace)

            assertEquals("mine", File(workspace, "save.dat").readText())
            assertFalse(held.exists())
            assertFalse(discarded.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun anUnlabelledLeftoverFromAnOlderBuildCountsAsHeld() {
        val root = createTempDir(prefix = "opensave-write-back-legacy-")
        try {
            val workspace = File(root, "source-workspace")
            val legacy = File(root, "${WorkspaceWriteBackPolicy.TEMPORARY_PREFIX}1234").apply { mkdirs() }
            File(legacy, "save.dat").writeText("mine")

            WorkspaceWriteBackPolicy.recoverHeldWorkspace(workspace)

            assertEquals("mine", File(workspace, "save.dat").readText())
            assertEquals(listOf("source-workspace"), root.list()?.toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun onlyDiscardedLeftoversNeverBecomeTheWorkspace() {
        val root = createTempDir(prefix = "opensave-write-back-discarded-")
        try {
            val workspace = File(root, "source-workspace")
            val discarded = File(root, "${WorkspaceWriteBackPolicy.DISCARDED_PREFIX}b").apply { mkdirs() }
            File(discarded, "save.dat").writeText("source copy")

            WorkspaceWriteBackPolicy.recoverHeldWorkspace(workspace)

            assertFalse(workspace.exists())
            // Nothing is deleted while no workspace exists.
            assertTrue(discarded.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
