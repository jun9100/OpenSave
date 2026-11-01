package com.jun9100.opensave.ui

import com.jun9100.opensave.LibraryStatusFilter
import com.jun9100.opensave.SnapshotRecord
import com.jun9100.opensave.ui.dialogs.MenuEntry
import com.jun9100.opensave.ui.settings.TextSettingSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandheldMenusTest {
    @Test
    fun libraryPanelOffersScanSelectionCloudFiltersAndThePageSwitch() {
        val entries = libraryCommands(
            busy = false,
            typeOptions = listOf("all" to "全部", "switch" to "Switch 游戏"),
            selectedTypeId = "switch",
            selectedStatus = LibraryStatusFilter.CHANGED,
            selectionMode = false,
            selectedCount = 0,
            onScan = {},
            selection = LibrarySelectionActions(),
            batch = listOf(MenuEntry("上传待传 (2)") {}),
            onTypeSelected = {},
            onStatusSelected = {},
            onOpenManagement = {},
        )
        assertEquals(
            listOf("扫描游戏", "进入选择模式", "上传待传 (2)", "筛选：全部", "筛选：Switch 游戏") +
                LibraryStatusFilter.entries.map { "状态：${it.label}" } +
                "前往 备份与设置",
            entries.map { it.label },
        )
        assertTrue(entries.single { it.label == "筛选：Switch 游戏" }.checked)
        assertTrue(entries.single { it.label == "状态：有变更" }.checked)
        assertTrue(entries.all { it.enabled })
    }

    @Test
    fun libraryPanelInSelectionModeOffersSelectAllClearAndExitEvenWhileBusy() {
        var selectedAll = false
        var exited = false
        val entries = libraryCommands(
            busy = true,
            typeOptions = listOf("all" to "全部"),
            selectedTypeId = "all",
            selectedStatus = LibraryStatusFilter.ALL,
            selectionMode = true,
            selectedCount = 3,
            onScan = {},
            selection = LibrarySelectionActions(onSelectAll = { selectedAll = true }, onExit = { exited = true }),
            batch = emptyList(),
            onTypeSelected = {},
            onStatusSelected = {},
            onOpenManagement = {},
        )
        val labels = entries.map { it.label }
        assertEquals(listOf("扫描游戏", "全选当前筛选", "清除选择", "退出选择模式"), labels.take(4))
        assertEquals("已选 3", entries[1].detail)
        // Leaving selection mode and switching pages are not operations.
        assertEquals(setOf("退出选择模式", "前往 备份与设置"), entries.filter { it.enabled }.map { it.label }.toSet())
        entries[1].onClick()
        entries[3].onClick()
        assertTrue(selectedAll)
        assertTrue(exited)
    }

    @Test
    fun managementPanelDisablesOperationsWhileBusy() {
        val entries = managementCommands(busy = true, onRefresh = {}, onListCloud = {}, onCheckCloudUpdates = {}, onPickFolder = {}, onOpenLibrary = {})
        assertEquals(listOf("刷新来源", "列举云端快照", "检查跨设备更新", "添加并授权目录", "前往 游戏库"), entries.map { it.label })
        assertEquals(listOf(false, false, false, false, true), entries.map { it.enabled })
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
        assertEquals("自动备份总开关未开启（备份与设置）", entry.detail)
        entry.onClick()
        assertTrue(toggled)
        assertTrue(menu(latest = null).none { it.label.contains("自动备份") })
    }

    @Test
    fun managementPanelOffersTheLastBatchReport() {
        val entries = managementCommands(
            busy = true,
            onRefresh = {},
            onListCloud = {},
            onCheckCloudUpdates = {},
            onPickFolder = {},
            onOpenLibrary = {},
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
