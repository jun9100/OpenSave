package com.jun9100.opensave.ui.batch

import com.jun9100.opensave.PrimaryBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchCommandsTest {
    private val scope = BatchScope(label = "已选 4 款", keys = listOf("a", "b", "c", "d"), pendingUpload = 2, restorable = 1, conflicts = 1)
    private val primary = PrimaryBackup("备份所选 (4)", setOf("a", "b", "c", "d"), fromSelection = true)

    @Test
    fun listsTheFourOperationsWithTheirCountsAndTheReport() {
        val entries = batchCommands(
            busy = false,
            batchRunning = false,
            scope = scope,
            primary = primary,
            cloudReady = true,
            hasReport = true,
            actions = BatchMenuActions(),
        )
        assertEquals(
            listOf("备份所选 (4)", "上传待传 (2)", "检查云端更新 (4)", "安全恢复云端较新 (1)…", "最近的批量报告"),
            entries.map { it.label },
        )
        assertTrue(entries.all { it.enabled })
        assertEquals("冲突 1 款需逐个确认，不会处理", entries[3].detail)
    }

    @Test
    fun cloudOperationsNeedGameSaveAndNothingStartsWhileBusy() {
        val offline = batchCommands(false, false, scope, primary, cloudReady = false, hasReport = false, actions = BatchMenuActions())
        assertEquals(listOf("备份所选 (4)"), offline.filter { it.enabled }.map { it.label })
        assertTrue(offline[1].detail.orEmpty().contains("启用 SaveVault 云端"))

        var cancelled = false
        val running = batchCommands(
            busy = true,
            batchRunning = true,
            scope = scope,
            primary = primary,
            cloudReady = true,
            hasReport = true,
            actions = BatchMenuActions(onCancelBatch = { cancelled = true }),
        )
        assertEquals("取消当前批量任务", running.first().label)
        assertEquals(setOf("取消当前批量任务", "最近的批量报告"), running.filter { it.enabled }.map { it.label }.toSet())
        running.first().onClick()
        assertTrue(cancelled)
    }

    @Test
    fun emptyCountsDisableTheirEntries() {
        val entries = batchCommands(
            busy = false,
            batchRunning = false,
            scope = scope.copy(pendingUpload = 0, restorable = 0, conflicts = 0),
            primary = PrimaryBackup("备份有变更 (0)", emptySet(), fromSelection = false),
            cloudReady = true,
            hasReport = false,
            actions = BatchMenuActions(),
        )
        assertEquals(listOf("检查云端更新 (4)"), entries.filter { it.enabled }.map { it.label })
        assertFalse(entries.last().enabled)
    }

    @Test
    fun longPlanListsAreShortened() {
        assertEquals("A、B", BatchPlanText.names(listOf("A", "B")))
        val many = (1..15).map { "G$it" }
        assertEquals((1..12).joinToString("、") { "G$it" } + " 等 15 款", BatchPlanText.names(many))
    }
}
