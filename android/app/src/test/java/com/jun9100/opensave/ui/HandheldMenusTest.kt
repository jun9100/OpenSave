package com.jun9100.opensave.ui

import com.jun9100.opensave.SnapshotRecord
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
            selectedCount = 2,
            eligibleCount = 5,
            onScan = {},
            onToggleAll = {},
            onCheckCloudUpdates = {},
            onTypeSelected = {},
            onOpenManagement = {},
        )
        assertEquals(
            listOf("扫描游戏", "全选当前筛选", "检查跨设备更新", "筛选：全部", "筛选：Switch 游戏", "前往 备份与设置"),
            entries.map { it.label },
        )
        assertEquals("已选 2/5", entries[1].detail)
        assertTrue(entries.single { it.label == "筛选：Switch 游戏" }.checked)
        assertTrue(entries.all { it.enabled })
    }

    @Test
    fun libraryPanelWhileBusyKeepsOnlyNavigationAndDropsEmptyGroups() {
        var toggled = false
        val entries = libraryCommands(
            busy = true,
            typeOptions = listOf("all" to "全部"),
            selectedTypeId = "all",
            selectedCount = 3,
            eligibleCount = 3,
            onScan = {},
            onToggleAll = { toggled = true },
            onCheckCloudUpdates = {},
            onTypeSelected = {},
            onOpenManagement = {},
        )
        assertEquals(listOf("扫描游戏", "取消全选", "检查跨设备更新", "前往 备份与设置"), entries.map { it.label })
        assertEquals(listOf(false, false, false, true), entries.map { it.enabled })
        entries[1].onClick()
        assertTrue(toggled)
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
