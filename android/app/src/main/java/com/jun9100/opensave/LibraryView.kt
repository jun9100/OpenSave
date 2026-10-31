package com.jun9100.opensave

/** What the most recent local versions of one library row look like (plan §3.4, phase 1 data only). */
data class LibraryRowHistory(
    val localCount: Int = 0,
    val lastBackupAtMillis: Long? = null,
    /** Whether the newest local version reached game-save; null when nothing was backed up. */
    val latestUploaded: Boolean? = null,
)

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
     * Second line of a compact row: source, last backup time and
     * `本地 N · 已上传/未上传`. Rows that batch backup skips say why.
     */
    fun rowSecondary(
        sourceLabel: String,
        history: LibraryRowHistory?,
        batchEligible: Boolean,
        formatTime: (Long) -> String,
    ): String = buildString {
        append(sourceLabel)
        if (!batchEligible) append(" · 仅待确认位置")
        val lastBackup = history?.takeIf { it.localCount > 0 }?.lastBackupAtMillis
        if (history == null || lastBackup == null) {
            append(" · 从未备份")
        } else {
            append(" · ").append(formatTime(lastBackup))
            append(" · 本地 ").append(history.localCount)
            append(if (history.latestUploaded == true) " · 已上传" else " · 未上传")
        }
    }

    fun backTarget(page: OpenSavePage, gameDetailOpen: Boolean, landscape: Boolean): BackTarget = when {
        page != OpenSavePage.LIBRARY -> BackTarget.LIBRARY
        // The landscape detail is a pane next to the list, not a page on top of it.
        gameDetailOpen && !landscape -> BackTarget.CLOSE_DETAIL
        else -> BackTarget.SYSTEM
    }
}
