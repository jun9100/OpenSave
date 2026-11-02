package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceGroupsTest {
    private fun option(path: String, selected: Boolean = true) = SaveSourceOption(
        source = SourceSelection.Root(path),
        label = path.substringAfterLast('/'),
        saveType = GameSaveTypes.SWITCH,
        requiredAccess = SourceAccessCapability.ROOT,
        selected = selected,
    )

    private val eden = option("/data/user/0/eden")
    private val yuzu = option("/data/user/0/yuzu")
    private val gone = option("/data/user/0/gone")
    private val off = option("/data/user/0/off", selected = false)
    private val options = listOf(eden, yuzu, gone, off)
    private val scans = listOf(
        SourceScanResult(eden.source, "eden", access = SourceAccessStatus.rootOnly()),
        SourceScanResult(yuzu.source, "yuzu", access = SourceAccessStatus.rootOnly()),
        SourceScanResult(gone.source, "gone", access = SourceAccessStatus.unavailable(SourceAccessCapability.ROOT, "目录不存在")),
    )

    @Test
    fun missingSourcesAreFoldedAndCannotBeChecked() {
        val rows = SourceGroups.rows(options, scans, rootChecked = true, rootAvailable = true)
        val byPath = rows.associateBy { (it.option.source as SourceSelection.Root).path }

        assertEquals(SourceGroup.AVAILABLE, byPath.getValue(eden.source.displayPath).group)
        assertEquals(SourceGroup.AVAILABLE, byPath.getValue(off.source.displayPath).group)
        val missing = byPath.getValue(gone.source.displayPath)
        assertEquals(SourceGroup.MISSING, missing.group)
        assertFalse(missing.checkable)
        assertFalse(missing.checked)
        assertTrue(SourceGroup.MISSING.foldedByDefault)
        assertEquals(
            listOf(SourceGroup.AUTHORIZED, SourceGroup.AVAILABLE, SourceGroup.MISSING),
            SourceGroups.grouped(rows).map { it.first },
        )
    }

    @Test
    fun withoutRootEveryRootSourceNeedsRoot() {
        val refused = scans.map { it.copy(access = SourceAccessStatus.unavailable(SourceAccessCapability.ROOT, "Root 未授权")) }
        val rows = SourceGroups.rows(options, refused, rootChecked = true, rootAvailable = false)

        assertTrue(rows.all { it.group == SourceGroup.NEEDS_ROOT && !it.checkable && !it.checked })
        // Before su answered nothing is blocked yet.
        val unchecked = SourceGroups.rows(options, emptyList(), rootChecked = false, rootAvailable = false)
        assertTrue(unchecked.all { it.group == SourceGroup.AVAILABLE && it.checkable })
    }

    @Test
    fun togglesAreStagedUntilApplied() {
        val rows = SourceGroups.rows(options, scans, rootChecked = true, rootAvailable = true)
        val edenRow = rows.first { it.option == eden }
        val offRow = rows.first { it.option == off }

        var draft = SourceDraft().toggle(edenRow, enabled = false).toggle(offRow, enabled = true)
        assertEquals(mapOf(eden.id to false, off.id to true), draft.pending(options))
        assertEquals(2, draft.pendingCount(options))
        val staged = SourceGroups.rows(options, scans, rootChecked = true, rootAvailable = true, draft = draft)
        assertFalse(staged.first { it.option == eden }.checked)
        assertTrue(staged.first { it.option == eden }.changed)

        // Toggling back leaves nothing to apply.
        draft = draft.toggle(edenRow, enabled = true)
        assertEquals(1, draft.pendingCount(options))
        // Once the stored choice matches (applied elsewhere), the change is no longer pending.
        assertEquals(0, draft.pendingCount(listOf(eden, yuzu, gone, off.copy(selected = true))))
    }

    @Test
    fun aMissingSourceIsNeverStagedOn() {
        val rows = SourceGroups.rows(options.map { if (it == gone) it.copy(selected = false) else it }, scans, rootChecked = true, rootAvailable = true)
        val missing = rows.first { it.group == SourceGroup.MISSING }

        assertEquals(SourceDraft(), SourceDraft().toggle(missing, enabled = true))
        val all = SourceDraft().setAll(rows, enabled = true)
        assertFalse(missing.option.id in all.changes)
        assertTrue(off.id in all.changes)
        val none = SourceDraft().setAll(rows, enabled = false)
        assertEquals(setOf(eden.id, yuzu.id), none.changes.keys)
    }

    @Test
    fun presetsOfAppsThatAreNotInstalledAreFoldedAndGroupedByApp() {
        fun preset(label: String) = RootSourcePresets.all.first { it.label == label }.let { preset ->
            SaveSourceOption(
                source = SourceSelection.Root(preset.path),
                label = preset.label,
                saveType = GameSaveTypes.forSource(SourceSelection.Root(preset.path)),
                requiredAccess = SourceAccessCapability.ROOT,
                selected = true,
            )
        }
        val edenPrivate = preset("Eden Switch · 私有目录")
        val edenData = preset("Eden Switch · Android/data")
        val gameHub = preset("GameHub PC · 私有目录")
        val sweep = preset("共享存储 · 全盘补漏（较慢）")
        val rows = SourceGroups.rows(
            listOf(sweep, edenPrivate, gameHub, edenData),
            emptyList(),
            rootChecked = true,
            rootAvailable = true,
            installedPackages = setOf("dev.eden.eden_emulator"),
        )
        val gameHubRow = rows.first { it.option == gameHub }
        assertEquals(SourceGroup.NOT_INSTALLED, gameHubRow.group)
        assertFalse(gameHubRow.checkable)

        val available = rows.filter { it.group == SourceGroup.AVAILABLE }
        val sections = SourceGroups.byApp(available)
        assertEquals(listOf("Eden Switch", "共享存储"), sections.map { it.first })
        assertEquals(listOf(edenPrivate, edenData), sections.first().second.map { it.option })
    }
}
