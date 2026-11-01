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

    /** What "安全恢复云端较新" would restore; the toolbar only shows it while there is one (see [LibraryView.toolbarStatusFilters]). */
    CLOUD_NEWER("云端较新"),
    ;

    fun matches(status: GameSyncStatus?): Boolean = when (this) {
        ALL -> true
        CHANGED -> status == GameSyncStatus.LOCAL_CHANGED
        NOT_UPLOADED -> status == GameSyncStatus.NOT_UPLOADED
        NEVER_BACKED_UP -> status == GameSyncStatus.NEVER_BACKED_UP
        CONFLICT -> status == GameSyncStatus.CONFLICT
        CLOUD_NEWER -> status == GameSyncStatus.CLOUD_NEWER
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

    /** "同步与任务" -> the page it was opened from. */
    PARENT,

    /** Nothing of ours to pop: let the system handle it. */
    SYSTEM,
}

/** Why the game list is empty; each reason has its own text and way out. */
enum class LibraryEmptyState {
    /** The first scan has not finished yet. */
    SCANNING,

    /** No source could be read: nothing granted and no Root. */
    NO_ACCESS,

    /** Sources were read, but none holds a confirmed save location. */
    NOTHING_FOUND,

    /** There are games, but the type or status filter hides them all ("清除筛选"). */
    FILTERED_OUT,
}

/** Pure view logic of the game library, kept out of Compose so it can be unit tested. */
object LibraryView {
    /** Null while the list shows games. */
    fun emptyState(scanned: Boolean, readableSources: Int, gameCount: Int, visibleCount: Int): LibraryEmptyState? = when {
        visibleCount > 0 -> null
        gameCount > 0 -> LibraryEmptyState.FILTERED_OUT
        !scanned -> LibraryEmptyState.SCANNING
        readableSources == 0 -> LibraryEmptyState.NO_ACCESS
        else -> LibraryEmptyState.NOTHING_FOUND
    }

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
    /**
     * The status chips of the landscape toolbar: every filter, except
     * "云端较新" while no game is (it only appears after a cloud check) and
     * it is not the active one. The command panel always lists all of them.
     */
    fun toolbarStatusFilters(counts: Map<LibraryStatusFilter, Int>, selected: LibraryStatusFilter): List<LibraryStatusFilter> =
        LibraryStatusFilter.entries.filter { filter ->
            filter != LibraryStatusFilter.CLOUD_NEWER || filter == selected || (counts[filter] ?: 0) > 0
        }

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
    /**
     * The status strip line: a single operation waiting for the auto-backup
     * worker says so instead of looking stuck (plan §5.5).
     */
    fun progressLine(state: SourceUiState): String? =
        if (state.busy && state.batch == null && state.lockOwner == StorageOperationLock.AUTO_BACKUP) {
            "等待自动备份完成…"
        } else {
            state.progress
        }

    fun needsFolderGrant(state: SourceUiState): Boolean =
        state.rootChecked && !state.rootAvailable && !state.hasPersistedPermission

    fun backTarget(page: OpenSavePage, gameDetailOpen: Boolean, landscape: Boolean): BackTarget = when {
        !page.topLevel -> BackTarget.PARENT
        page != OpenSavePage.LIBRARY -> BackTarget.LIBRARY
        // The landscape detail is a pane next to the list, not a page on top of it.
        gameDetailOpen && !landscape -> BackTarget.CLOSE_DETAIL
        else -> BackTarget.SYSTEM
    }

    /**
     * The identity a library row stands for: the same game found in several
     * sources (Eden's private, Android/data and public folders; an app's
     * private and Android/data folders) is one row. Only real identities
     * merge (Switch TitleID, Android package, PSP code, Steam AppID); a game
     * known only by its path stays one row per source.
     */
    fun mergeKey(entry: SourceGameEntry): String =
        entry.game.gameKey.takeUnless { it.startsWith("path:") } ?: entry.libraryKey

    /** Entries grouped into rows, in the order of their first member. */
    fun groupRows(entries: List<SourceGameEntry>): List<LibraryRowGroup> = entries
        .groupBy(::mergeKey)
        .map { (key, members) -> LibraryRowGroup(key, members) }

    /** The member a row shows: the one being looked at, else the first that batch backup takes, else the first. */
    fun rowEntry(group: LibraryRowGroup, selectedKey: String?): SourceGameEntry =
        group.members.firstOrNull { it.libraryKey == selectedKey }
            ?: group.members.firstOrNull { GameLibrary.canBatchBackup(it.game) }
            ?: group.members.first()

    /** The most pressing status among a row's save locations. */
    fun groupStatus(group: LibraryRowGroup, statuses: Map<String, GameSyncStatus>): GameSyncStatus? =
        group.members.mapNotNull { statuses[it.libraryKey] }.minByOrNull(::statusPriority)

    private fun statusPriority(status: GameSyncStatus): Int = when (status) {
        GameSyncStatus.CONFLICT -> 0
        GameSyncStatus.CLOUD_NEWER -> 1
        GameSyncStatus.LOCAL_CHANGED -> 2
        GameSyncStatus.NEVER_BACKED_UP -> 3
        GameSyncStatus.NOT_UPLOADED -> 4
        GameSyncStatus.LOCAL_ONLY -> 5
        GameSyncStatus.SYNCED -> 6
    }

    /** Short name of a save location for the detail's location switcher: the folder kind of its source. */
    fun locationLabel(entry: SourceGameEntry): String =
        entry.sourceLabel.substringAfterLast(" · ").takeIf { it != entry.sourceLabel } ?: entry.sourceLabel

    /**
     * An Android app that Android does not mark as a game and the user has not
     * confirmed: its data was found, but it stays off the library and is
     * listed on the 来源 page instead.
     */
    fun isUnconfirmedApp(entry: SourceGameEntry): Boolean =
        entry.game.saves.isNotEmpty() && entry.game.saves.all { it.kind == "android-app" }

    /** The library's rows: every game but [isUnconfirmedApp] ones. */
    fun libraryEntries(entries: List<SourceGameEntry>): List<SourceGameEntry> = entries.filterNot(::isUnconfirmedApp)

    /** Unconfirmed apps once each, by package, for the 来源 page. */
    fun unconfirmedApps(entries: List<SourceGameEntry>): List<PendingAndroidApp> = entries
        .filter(::isUnconfirmedApp)
        .groupBy { it.game.presentation.androidPackageName ?: it.game.gameKey.removePrefix("android:") }
        .map { (packageName, forApp) ->
            PendingAndroidApp(packageName, forApp.first().game.presentation.primaryName, forApp.sumOf { it.game.locationCount })
        }
        .sortedBy { it.name.lowercase() }
}

data class PendingAndroidApp(
    val packageName: String,
    val name: String,
    val locationCount: Int,
)

/** One library row: a game and every save location of it the scan found. */
data class LibraryRowGroup(
    val key: String,
    val members: List<SourceGameEntry>,
)
