package com.jun9100.opensave

/** What the most recent local versions of one library row look like (plan §3.4, phase 1 data only). */
data class LibraryRowHistory(
    val localCount: Int = 0,
    val lastBackupAtMillis: Long? = null,
    /** Whether the newest local version reached game-save; null when nothing was backed up. */
    val latestUploaded: Boolean? = null,
)

/** Status chips of the library toolbar; combined with the save type filter. */
enum class LibraryStatusFilter(val label: String) {
    ALL("全部状态"),
    CHANGED("有变更"),
    NOT_UPLOADED("未上传"),
    NEVER_BACKED_UP("从未备份"),
    CONFLICT("冲突"),
    ;

    fun matches(status: GameSyncStatus?): Boolean = when (this) {
        ALL -> true
        CHANGED -> status == GameSyncStatus.LOCAL_CHANGED
        NOT_UPLOADED -> status == GameSyncStatus.NOT_UPLOADED
        NEVER_BACKED_UP -> status == GameSyncStatus.NEVER_BACKED_UP
        CONFLICT -> status == GameSyncStatus.CONFLICT
    }
}

/** The toolbar's main button and Start (plan §5.1): the selection if any, else the games with changes. */
data class PrimaryBackup(
    val label: String,
    val keys: Set<String>,
    /** True for "备份所选", false for "备份有变更". */
    val fromSelection: Boolean,
) {
    val enabled: Boolean get() = keys.isNotEmpty()
}

/** Where the back key goes; see [LibraryView.backTarget]. */
enum class BackTarget {
    /** Portrait full-screen game detail -> game list. */
    CLOSE_DETAIL,

    /** A secondary top-level page -> game library. */
    LIBRARY,

    /** Nothing of ours to pop: let the system handle it. */
    SYSTEM,
}

/** Pure view logic of the game library, kept out of Compose so it can be unit tested. */
object LibraryView {
    const val ALL_TYPES = "all"

    /** "全部" first, then every save type present in [games], by label. */
    fun typeOptions(games: List<SourceGameEntry>): List<Pair<String, String>> =
        listOf(ALL_TYPES to "全部") + games
            .map(SourceGameEntry::saveType)
            .distinctBy(GameSaveType::id)
            .sortedBy(GameSaveType::label)
            .map { it.id to it.label }

    /** A filter whose type disappeared after a rescan falls back to "全部" instead of an empty list. */
    fun effectiveTypeId(typeId: String, options: List<Pair<String, String>>): String =
        typeId.takeIf { id -> options.any { it.first == id } } ?: ALL_TYPES

    fun filter(games: List<SourceGameEntry>, typeId: String): List<SourceGameEntry> =
        if (typeId == ALL_TYPES) games else games.filter { it.saveType.id == typeId }

    /** Rows of [games] whose status [filter] takes; a game without a status yet only shows under "全部状态". */
    fun filterByStatus(
        games: List<SourceGameEntry>,
        statuses: Map<String, GameSyncStatus>,
        filter: LibraryStatusFilter,
    ): List<SourceGameEntry> =
        if (filter == LibraryStatusFilter.ALL) games else games.filter { filter.matches(statuses[it.libraryKey]) }

    /** How many of [games] each status chip would show. */
    fun statusCounts(games: List<SourceGameEntry>, statuses: Map<String, GameSyncStatus>): Map<LibraryStatusFilter, Int> =
        LibraryStatusFilter.entries.associateWith { filter -> filterByStatus(games, statuses, filter).size }

    /** Eligible games "备份有变更" takes: never backed up, or saves changed since the newest backup. */
    fun changedKeys(eligibleKeys: List<String>, statuses: Map<String, GameSyncStatus>): List<String> =
        eligibleKeys.filter { statuses[it]?.needsBackup == true }

    /**
     * "备份所选 (N)" whenever something is selected or selection mode is on,
     * otherwise "备份有变更 (N)" over [changedKeys].
     */
    fun primaryBackup(selectedKeys: Set<String>, changedKeys: Collection<String>, selectionMode: Boolean): PrimaryBackup =
        if (selectionMode || selectedKeys.isNotEmpty()) {
            PrimaryBackup("备份所选 (${selectedKeys.size})", selectedKeys, fromSelection = true)
        } else {
            PrimaryBackup("备份有变更 (${changedKeys.size})", changedKeys.toSet(), fromSelection = false)
        }

    /**
     * The local versions kept in the UI state: up to [perGame] newest of every
     * game, so a game backed up long ago still has a status, plus [other]
     * newest whole-source snapshots. [records] are newest first.
     */
    fun retainedSnapshots(records: List<SnapshotRecord>, perGame: Int, other: Int): List<SnapshotRecord> {
        val (games, sources) = records.partition { it.gameKey != null }
        return (games.groupBy { "${it.sourceId}::${it.gameKey}" }.values.flatMap { it.take(perGame) } + sources.take(other))
            .sortedByDescending(SnapshotRecord::createdAtMillis)
    }

    /**
     * The game the detail pane shows. Landscape always shows one when the list is
     * not empty, falling back to the first visible row; portrait only shows the
     * game the user opened.
     */
    fun detailEntry(
        visibleGames: List<SourceGameEntry>,
        selectedGameKey: String?,
        fallbackToFirst: Boolean,
    ): SourceGameEntry? =
        visibleGames.firstOrNull { it.libraryKey == selectedGameKey }
            ?: visibleGames.firstOrNull().takeIf { fallbackToFirst }

    /**
     * Groups local versions by library key so each row does not rescan every
     * snapshot. Safety copies taken before a restore are not backups the user
     * made (and are never uploaded), so they count for nothing here.
     */
    fun historyIndex(snapshots: List<SnapshotRecord>): Map<String, LibraryRowHistory> = snapshots
        .filter { it.gameKey != null && !it.isSafetyCopy }
        .groupBy { "${it.sourceId}::${it.gameKey}" }
        .mapValues { (_, records) ->
            val latest = records.maxBy(SnapshotRecord::createdAtMillis)
            LibraryRowHistory(
                localCount = records.size,
                lastBackupAtMillis = latest.createdAtMillis,
                latestUploaded = latest.uploaded,
            )
        }

    /**
     * Second line of a compact row: last backup time and `本地 N`, then the
     * source; the sync state is the row's status badge. The time comes first
     * because the landscape list column is narrow and the ellipsis should cut
     * the long source label. Rows that batch backup skips say why.
     */
    fun rowSecondary(
        sourceLabel: String,
        history: LibraryRowHistory?,
        batchEligible: Boolean,
        formatTime: (Long) -> String,
    ): String = buildString {
        if (!batchEligible) append("仅待确认位置 · ")
        val lastBackup = history?.takeIf { it.localCount > 0 }?.lastBackupAtMillis
        if (history == null || lastBackup == null) {
            append("从未备份")
        } else {
            append(formatTime(lastBackup))
            append(" · 本地 ").append(history.localCount)
        }
        append(" · ").append(sourceLabel)
    }

    /** No root and no granted folder: nothing beyond built-in shared paths can be scanned. */
    fun needsFolderGrant(state: SourceUiState): Boolean =
        state.rootChecked && !state.rootAvailable && !state.hasPersistedPermission

    fun backTarget(page: OpenSavePage, gameDetailOpen: Boolean, landscape: Boolean): BackTarget = when {
        page != OpenSavePage.LIBRARY -> BackTarget.LIBRARY
        // The landscape detail is a pane next to the list, not a page on top of it.
        gameDetailOpen && !landscape -> BackTarget.CLOSE_DETAIL
        else -> BackTarget.SYSTEM
    }
}
