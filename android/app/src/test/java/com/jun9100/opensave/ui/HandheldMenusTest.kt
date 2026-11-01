package com.jun9100.opensave.ui

import com.jun9100.opensave.LibraryStatusFilter
import com.jun9100.opensave.PrimaryBackup
import com.jun9100.opensave.SnapshotRecord
import com.jun9100.opensave.ui.batch.BatchMenuActions
import com.jun9100.opensave.ui.batch.BatchScope
import com.jun9100.opensave.ui.batch.batchCommands
import com.jun9100.opensave.ui.dialogs.MenuEntry
import com.jun9100.opensave.ui.settings.TextSettingSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandheldMenusTest {
    @Test
    fun libraryPanelPutsTheMainActionsFirstAndTheFiltersInASubPanel() {
        var filtersOpened = false
        var syncOpened = false
        val entries = libraryCommands(
            busy = false,
            selectionMode = false,
            selectedCount = 0,
            filterSummary = "Switch 游戏 · 有变更",
            syncAttention = 2,
            onScan = {},
            selection = LibrarySelectionActions(),
            batch = batch(hasReport = true),
            onOpenFilters = { filtersOpened = true },
            onOpenSyncTasks = { syncOpened = true },
        )
        assertEquals(
            listOf("备份有变更 (3)", "同步与任务", "最近的批量报告", "上传待传 (2)", "检查云端更新 (5)", "进入选择模式", "筛选…", "扫描游戏"),
            entries.map { it.label },
        )
        assertEquals("2 款需要确认", entries[1].detail)
        assertEquals("Switch 游戏 · 有变更", entries.single { it.label == "筛选…" }.detail)
        entries.single { it.label == "筛选…" }.onClick()
        entries[1].onClick()
        assertTrue(filtersOpened)
        assertTrue(syncOpened)
        assertTrue(entries.size <= 10)
    }

    @Test
    fun libraryPanelLeavesOutAReportThatDoesNotExist() {
        val entries = libraryCommands(
            busy = false,
            selectionMode = false,
            selectedCount = 0,
            filterSummary = "全部",
            syncAttention = 0,
            onScan = {},
            selection = LibrarySelectionActions(),
            batch = batch(hasReport = false),
            onOpenFilters = {},
            onOpenSyncTasks = {},
        )
        assertTrue(entries.none { it.label == "最近的批量报告" })
        assertEquals("冲突、云端较新和批量报告", entries.single { it.label == "同步与任务" }.detail)
    }

    @Test
    fun filterPanelListsTypesThenStatusesWithTheCurrentOnesMarked() {
        var chosen: LibraryStatusFilter? = null
        val entries = libraryFilterCommands(
            busy = false,
            typeOptions = listOf("all" to "全部", "switch" to "Switch 游戏"),
            selectedTypeId = "switch",
            selectedStatus = LibraryStatusFilter.CHANGED,
            onTypeSelected = {},
            onStatusSelected = { chosen = it },
        )
        assertEquals(
            listOf("类型：全部", "类型：Switch 游戏") + LibraryStatusFilter.entries.map { "状态：${it.label}" },
            entries.map { it.label },
        )
        assertTrue(entries.single { it.label == "类型：Switch 游戏" }.checked)
        assertTrue(entries.single { it.label == "状态：有变更" }.checked)
        entries.last().onClick()
        assertEquals(LibraryStatusFilter.entries.last(), chosen)
        // One type only: nothing to choose there.
        val single = libraryFilterCommands(false, listOf("all" to "全部"), "all", LibraryStatusFilter.ALL, {}, {})
        assertTrue(single.none { it.label.startsWith("类型：") })
    }

    @Test
    fun libraryPanelInSelectionModeOffersSelectAllClearAndExitEvenWhileBusy() {
        var selectedAll = false
        var exited = false
        val entries = libraryCommands(
            busy = true,
            selectionMode = true,
            selectedCount = 3,
            filterSummary = "全部",
            syncAttention = 0,
            onScan = {},
            selection = LibrarySelectionActions(onSelectAll = { selectedAll = true }, onExit = { exited = true }),
            batch = emptyList(),
            onOpenFilters = {},
            onOpenSyncTasks = {},
        )
        val labels = entries.map { it.label }
        assertEquals(listOf("同步与任务", "全选当前筛选", "清除选择", "退出选择模式"), labels.take(4))
        assertEquals("已选 3", entries[1].detail)
        // Leaving selection mode and opening "同步与任务" are not operations.
        assertEquals(setOf("退出选择模式", "同步与任务"), entries.filter { it.enabled }.map { it.label }.toSet())
        entries[1].onClick()
        entries[3].onClick()
        assertTrue(selectedAll)
        assertTrue(exited)
    }

    @Test
    fun libraryPanelPutsCancelFirstWhileABatchRunsInSelectionMode() {
        var cancelled = false
        val entries = libraryCommands(
            busy = true,
            selectionMode = true,
            selectedCount = 5,
            filterSummary = "全部",
            syncAttention = 0,
            onScan = {},
            selection = LibrarySelectionActions(),
            batch = listOf(
                MenuEntry("取消当前批量任务") { cancelled = true },
                MenuEntry("备份所选 (5)", enabled = false) {},
            ),
            onOpenFilters = {},
            onOpenSyncTasks = {},
        )
        // The dialog focuses the first enabled entry: it must be cancel, not "退出选择模式".
        val first = entries.first { it.enabled }
        assertEquals("取消当前批量任务", first.label)
        first.onClick()
        assertTrue(cancelled)
        assertEquals(1, entries.count { it.label == "取消当前批量任务" })
        assertEquals("备份所选 (5)", entries[1].label)
    }

    @Test
    fun settingsAndSourcesPanelsDisableOperationsWhileBusy() {
        val settings = settingsCommands(busy = true, syncAttention = 1, onOpenSyncTasks = {}, onCheckCloudUpdates = {}, onListCloud = {}, onRefresh = {})
        assertEquals(listOf("同步与任务", "检查跨设备更新", "列举云端快照", "刷新配置"), settings.map { it.label })
        assertEquals(listOf(true, false, false, false), settings.map { it.enabled })
        val sources = sourcesCommands(busy = true, onPickFolder = {}, onScan = {}, onRefresh = {})
        assertEquals(listOf("添加并授权目录", "重新扫描来源", "刷新来源"), sources.map { it.label })
        assertTrue(sources.none { it.enabled })
    }

    @Test
    fun syncPanelOffersCancelOnlyWhileABatchRuns() {
        val idle = syncCommands(busy = false, batchRunning = false, onCancelBatch = {}, onCheckCloudUpdates = {}, onOpenBatchReport = null, onBack = {})
        assertEquals(listOf("检查全部游戏的云端更新", "返回"), idle.map { it.label })
        val running = syncCommands(busy = true, batchRunning = true, onCancelBatch = {}, onCheckCloudUpdates = {}, onOpenBatchReport = {}, onBack = {})
        assertEquals("取消当前批量任务", running.first { it.enabled }.label)
        assertTrue(running.single { it.label == "最近的批量报告" }.enabled)
    }

    @Test
    fun gameMenuOffersRestoreOnlyWithALocalBackup() {
        val without = menu(latest = null)
        assertEquals(listOf("备份这款游戏", "加入批量选择", "查看版本 (0)", "查看存档位置 (2)"), without.map { it.label })

        var restored: SnapshotRecord? = null
        val record = record()
        val with = menu(latest = record, checked = true, onRestore = { restored = it })
        assertEquals("移出批量选择", with[1].label)
        assertEquals("恢复最近的本地版本…", with[2].label)
        with[2].onClick()
        assertEquals(record, restored)
    }

    @Test
    fun gameMenuExplainsWhyACandidateOnlyGameIsNotSelectable() {
        val entries = menu(latest = null, eligible = false)
        assertFalse(entries[1].enabled)
        assertEquals("仅待确认位置，不参与批量备份", entries[1].detail)
        val busy = menu(latest = record(), busy = true)
        assertEquals(listOf(false, false, false, true, true), busy.map { it.enabled })
    }

    @Test
    fun gameMenuTogglesAutomaticBackupOfOneGame() {
        var toggled = false
        val on = gameMenuEntries(
            busy = true,
            batchEligible = true,
            batchChecked = false,
            latestLocal = null,
            versionCount = 0,
            locationCount = 1,
            onBackup = {},
            onToggleBatch = {},
            onRestoreLatest = {},
            onShowVersions = {},
            onShowLocations = {},
            autoBackupIncluded = true,
            autoBackupScheduled = false,
            onToggleAutoBackup = { toggled = true },
        )
        val entry = on.single { it.label == "关闭这款游戏的自动备份" }
        // A local preference, not an operation: available while busy.
        assertTrue(entry.enabled)
        assertEquals("自动备份总开关未开启（设置）", entry.detail)
        entry.onClick()
        assertTrue(toggled)
        assertTrue(menu(latest = null).none { it.label.contains("自动备份") })
    }

    @Test
    fun settingsPanelOffersTheLastBatchReport() {
        val entries = settingsCommands(
            busy = true,
            syncAttention = 0,
            onOpenSyncTasks = {},
            onCheckCloudUpdates = {},
            onListCloud = {},
            onRefresh = {},
            onOpenBatchReport = {},
        )
        assertTrue(entries.single { it.label == "最近的批量报告" }.enabled)
    }

    @Test
    fun textSummaryNeverShowsTheSecret() {
        assertEquals("未设置 · 例如 http://nas:8788", TextSettingSummary.display("", "http://nas:8788", secret = false))
        assertEquals("未设置", TextSettingSummary.display(" ", "", secret = true))
        assertEquals("http://nas:8788", TextSettingSummary.display("http://nas:8788", "", secret = false))
        val masked = TextSettingSummary.display("super-secret-key", "", secret = true)
        assertFalse(masked.contains("super"))
        assertEquals(masked, TextSettingSummary.display("k", "", secret = true))
    }

    private fun batch(hasReport: Boolean) = batchCommands(
        busy = false,
        batchRunning = false,
        scope = BatchScope(label = "全部游戏", keys = listOf("a", "b", "c", "d", "e"), pendingUpload = 2, restorable = 0, conflicts = 0),
        primary = PrimaryBackup(label = "备份有变更 (3)", keys = setOf("a", "b", "c"), fromSelection = false),
        cloudReady = true,
        hasReport = hasReport,
        actions = BatchMenuActions(),
    ).filterNot { it.label.startsWith("安全恢复") }

    private fun record() = SnapshotRecord(
        id = "1",
        sourceId = "eden",
        remoteName = "r",
        localPath = "/tmp/r.zip",
        createdAtMillis = 1_793_000_000_000,
        sizeBytes = 10,
        sha256 = "x",
        manifestHash = "y",
        gameKey = "g",
    )

    private fun menu(
        latest: SnapshotRecord?,
        checked: Boolean = false,
        eligible: Boolean = true,
        busy: Boolean = false,
        onRestore: (SnapshotRecord) -> Unit = {},
    ) = gameMenuEntries(
        busy = busy,
        batchEligible = eligible,
        batchChecked = checked,
        latestLocal = latest,
        versionCount = if (latest == null) 0 else 1,
        locationCount = 2,
        onBackup = {},
        onToggleBatch = {},
        onRestoreLatest = onRestore,
        onShowVersions = {},
        onShowLocations = {},
    )
}
