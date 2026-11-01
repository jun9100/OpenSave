package com.jun9100.opensave.batch

import com.jun9100.opensave.AutoBackupPolicy
import com.jun9100.opensave.DetectedGame
import com.jun9100.opensave.GameIconKey
import com.jun9100.opensave.GamePresentation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BatchCenterTest {
    @Before
    @After
    fun reset() = BatchCenter.resetForTest()

    @Test
    fun onlyOneBatchRunsAtATime() {
        assertTrue(BatchCenter.tryBegin("first", BatchOperation.BACKUP, 3))
        assertFalse(BatchCenter.tryBegin("second", BatchOperation.UPLOAD_PENDING, 2))
        // The worker of the reserved batch may claim it again.
        assertTrue(BatchCenter.tryBegin("first", BatchOperation.BACKUP, 3))
        assertEquals("first", BatchCenter.running.value?.id)

        // Finishing another id does not release the reservation.
        BatchCenter.finish("second", null)
        assertTrue(BatchCenter.isRunning)

        val report = BatchReport("first", BatchOperation.BACKUP, 0, 1, emptyList())
        BatchCenter.finish("first", report)
        assertFalse(BatchCenter.isRunning)
        assertEquals(report, BatchCenter.lastFinished.value)
        assertTrue(BatchCenter.tryBegin("second", BatchOperation.UPLOAD_PENDING, 2))
    }

    @Test
    fun cancelIsRequestedOnTheRunningBatchOnly() {
        assertFalse(BatchCenter.requestCancel())
        BatchCenter.tryBegin("first", BatchOperation.CHECK_CLOUD, 4)
        assertFalse(BatchCenter.isCancelRequested("first"))
        assertTrue(BatchCenter.requestCancel())
        assertTrue(BatchCenter.isCancelRequested("first"))
        assertFalse(BatchCenter.isCancelRequested("other"))
    }

    @Test
    fun progressAndWaitUpdateTheRunningBatch() {
        BatchCenter.tryBegin("first", BatchOperation.BACKUP, 4)
        BatchCenter.waiting("first", "自动备份")
        assertEquals("自动备份", BatchCenter.running.value?.waitingFor)
        BatchCenter.progress("first", 2, "Hades")
        val progress = checkNotNull(BatchCenter.running.value)
        assertEquals(2, progress.done)
        assertEquals("Hades", progress.currentName)
        assertNull(progress.waitingFor)
        BatchCenter.progress("other", 3, "ignored")
        assertEquals(2, BatchCenter.running.value?.done)
    }

    @Test
    fun handedOverEntriesAreTakenOnceAndDroppedOnFinish() {
        BatchCenter.putEntries("first", emptyList())
        assertEquals(emptyList<Any>(), BatchCenter.takeEntries("first"))
        assertNull(BatchCenter.takeEntries("first"))
        BatchCenter.putEntries("second", emptyList())
        BatchCenter.finish("second", null)
        assertNull(BatchCenter.takeEntries("second"))
    }

    @Test
    fun autoBackupYieldsWhileABatchRuns() {
        assertFalse(AutoBackupPolicy.shouldYield(BatchCenter.isRunning))
        BatchCenter.tryBegin("first", BatchOperation.UPLOAD_PENDING, 1)
        assertTrue(AutoBackupPolicy.shouldYield(BatchCenter.isRunning))
        BatchCenter.finish("first", null)
        assertFalse(AutoBackupPolicy.shouldYield(BatchCenter.isRunning))
    }

    @Test
    fun autoBackupSkipsGamesSwitchedOffBySourceAndGame() {
        val games = listOf("pc:a", "pc:b").map { key ->
            DetectedGame(key, GamePresentation(localizedName = key, originalName = null, identity = null, iconKey = GameIconKey.PC), emptyList())
        }
        val excluded = AutoBackupPolicy.withIncluded(emptySet(), listOf("src::pc:a", "other::pc:b"), included = false)
        assertEquals(listOf("pc:b"), AutoBackupPolicy.includedGames("src", games, excluded).map { it.gameKey })
        val reincluded = AutoBackupPolicy.withIncluded(excluded, listOf("src::pc:a"), included = true)
        assertEquals(setOf("other::pc:b"), reincluded)
        assertEquals(games, AutoBackupPolicy.includedGames("src", games, reincluded))
    }
}
