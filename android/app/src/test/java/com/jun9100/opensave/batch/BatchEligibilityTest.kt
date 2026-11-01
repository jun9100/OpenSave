package com.jun9100.opensave.batch

import com.jun9100.opensave.GameSyncStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class BatchEligibilityTest {
    private val statuses = mapOf(
        "newer" to GameSyncStatus.CLOUD_NEWER,
        "conflict" to GameSyncStatus.CONFLICT,
        "pending" to GameSyncStatus.NOT_UPLOADED,
        "synced" to GameSyncStatus.SYNCED,
        "changed" to GameSyncStatus.LOCAL_CHANGED,
        "newer2" to GameSyncStatus.CLOUD_NEWER,
    )
    private val scope = listOf("synced", "newer", "conflict", "pending", "changed", "newer2", "unknown")

    @Test
    fun safeRestoreTakesOnlyStrictlyNewerGamesAndListsConflictsApart() {
        val plan = BatchEligibility.safeRestore(scope, statuses)
        assertEquals(listOf("newer", "newer2"), plan.restore)
        assertEquals(listOf("conflict"), plan.conflicts)
    }

    @Test
    fun safeRestoreStaysInsideTheScope() {
        val plan = BatchEligibility.safeRestore(listOf("newer2", "synced"), statuses)
        assertEquals(listOf("newer2"), plan.restore)
        assertEquals(emptyList<String>(), plan.conflicts)
    }

    @Test
    fun pendingUploadTakesGamesWhoseNewestBackupIsNotUploaded() {
        assertEquals(listOf("pending"), BatchEligibility.pendingUpload(scope, statuses))
    }
}
