package com.jun9100.opensave

/**
 * The groups of the 来源 page (plan §3.1). The folders the user granted come
 * first, then the built-in sources that can be scanned; sources that need a
 * Root this device does not grant and sources the last scan proved missing
 * are folded away and cannot be checked.
 */
enum class SourceGroup(val title: String, val foldedByDefault: Boolean = false) {
    AUTHORIZED("已授权目录"),
    AVAILABLE("内置来源 · 可用"),
    NEEDS_ROOT("需要 Root", foldedByDefault = true),
    NOT_INSTALLED("未安装", foldedByDefault = true),
    MISSING("不存在", foldedByDefault = true),
}

/** One source row: what to show and whether A may toggle it. */
data class SourceRow(
    val option: SaveSourceOption,
    val group: SourceGroup,
    /** The last scan's access, or null when the source was not scanned (switched off, or not yet). */
    val scan: SourceScanResult?,
    /** The check mark: the staged choice, else the stored one; never on a source that cannot be checked. */
    val checked: Boolean,
    val checkable: Boolean,
    /** The staged choice differs from the stored one. */
    val changed: Boolean,
)

object SourceGroups {
    fun rows(
        options: List<SaveSourceOption>,
        scans: List<SourceScanResult>,
        rootChecked: Boolean,
        rootAvailable: Boolean,
        draft: SourceDraft = SourceDraft(),
        /** Installed packages among the presets' apps; null when not known, which folds nothing. */
        installedPackages: Set<String>? = null,
    ): List<SourceRow> {
        val scanBySourceId = scans.associateBy(SourceScanResult::sourceId)
        return options.map { option ->
            val scan = scanBySourceId[option.sourceId]
            val notInstalled = installedPackages != null && (option.source as? SourceSelection.Root)
                ?.let { RootSourcePresets.find(it.path)?.packageName }
                ?.let { it !in installedPackages } == true
            val unreachable = notInstalled || SourceOptionPolicy.unreachable(option, scan?.access, rootChecked, rootAvailable)
            val group = when {
                option.source is SourceSelection.Saf -> SourceGroup.AUTHORIZED
                notInstalled -> SourceGroup.NOT_INSTALLED
                option.requiredAccess == SourceAccessCapability.ROOT && rootChecked && !rootAvailable -> SourceGroup.NEEDS_ROOT
                unreachable -> SourceGroup.MISSING
                else -> SourceGroup.AVAILABLE
            }
            val wanted = draft.wanted(option)
            SourceRow(
                option = option,
                group = group,
                scan = scan,
                checked = wanted && !unreachable,
                checkable = !unreachable,
                changed = wanted != option.selected,
            )
        }
    }

    /** The app a source belongs to: its preset's, else the part of its label before " · ". */
    fun appOf(option: SaveSourceOption): String =
        (option.source as? SourceSelection.Root)?.let { RootSourcePresets.find(it.path)?.app }
            ?: option.label.substringBefore(" · ")

    /** [rows] by app, apps in the order they first appear, the whole-storage sweep last. */
    fun byApp(rows: List<SourceRow>): List<Pair<String, List<SourceRow>>> = rows
        .groupBy { appOf(it.option) }
        .toList()
        .sortedBy { (_, members) ->
            members.any { (it.option.source as? SourceSelection.Root)?.path?.trimEnd('/') == "/storage/emulated/0" }
        }

    /** Every group in page order, empty ones left out except the granted folders (their add action lives there). */
    fun grouped(rows: List<SourceRow>): List<Pair<SourceGroup, List<SourceRow>>> {
        val byGroup = rows.groupBy(SourceRow::group)
        return SourceGroup.entries.mapNotNull { group ->
            val members = byGroup[group].orEmpty()
            if (members.isEmpty() && group != SourceGroup.AUTHORIZED) null else group to members
        }
    }
}

/**
 * Source toggles staged on the 来源 page until "应用并重新扫描" (plan §3.1):
 * the wanted choice by [SaveSourceOption.id], only where it differs from the
 * stored one, so toggling twice leaves nothing to apply.
 */
data class SourceDraft(val changes: Map<String, Boolean> = emptyMap()) {
    fun wanted(option: SaveSourceOption): Boolean = changes[option.id] ?: option.selected

    /** Stages [enabled] for [row]; a source that cannot be checked is only ever staged off. */
    fun toggle(row: SourceRow, enabled: Boolean): SourceDraft {
        if (enabled && !row.checkable) return this
        return set(row.option, enabled)
    }

    /** Stages [enabled] for every checkable row of [rows] ("全选" / "全不选" of a group). */
    fun setAll(rows: List<SourceRow>, enabled: Boolean): SourceDraft =
        rows.filter { it.checkable }.fold(this) { draft, row -> draft.set(row.option, enabled) }

    /** The staged changes that still apply to a listed source and differ from what is stored. */
    fun pending(options: List<SaveSourceOption>): Map<String, Boolean> {
        val byId = options.associateBy(SaveSourceOption::id)
        return changes.filter { (id, enabled) -> byId[id]?.let { it.selected != enabled } == true }
    }

    fun pendingCount(options: List<SaveSourceOption>): Int = pending(options).size

    private fun set(option: SaveSourceOption, enabled: Boolean): SourceDraft =
        if (enabled == option.selected) copy(changes = changes - option.id) else copy(changes = changes + (option.id to enabled))
}
