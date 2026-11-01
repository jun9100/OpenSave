package com.jun9100.opensave

/** Why a game is in "同步与任务"; the wording matches the row badges of [GameSyncStatus]. */
enum class SyncItemKind(val label: String) {
    CONFLICT("冲突需确认"),
    CLOUD_NEWER("云端较新"),
}

/** One game of "同步与任务" that needs a decision (plan §3.1). */
data class SyncInboxItem(
    val entry: SourceGameEntry,
    val kind: SyncItemKind,
    /** The cloud check's own explanation ([GameSaveNativeResolver] / [GameSaveHeadSync] messages) or why it is listed. */
    val reason: String,
    /** This session's verdict; null when only a remembered verdict or a conflict copy says so. */
    val live: GameSaveCloudUpdate? = null,
) {
    val key: String get() = entry.libraryKey
    val deviceName: String get() = live?.deviceName.orEmpty()
    val receivedAtMillis: Long get() = live?.receivedAtMillis ?: 0L
}

/**
 * The pending items of "同步与任务" and the one-line library banner, derived
 * from the statuses so both always agree with the row badges. Pure, unit tested.
 */
object SyncInbox {
    /**
     * Games whose status is a conflict or a newer cloud save, conflicts first,
     * then by name. [cloudActions] are the effective verdicts
     * ([GameStatusResolver.effectiveCloudActions]); [dismissed] are the keys
     * the user put off with "稍后" in this session.
     */
    fun items(
        entries: List<SourceGameEntry>,
        statuses: Map<String, GameSyncStatus>,
        live: List<GameSaveCloudUpdate>,
        cloudActions: Map<String, GameSaveRemoteAction>,
        dismissed: Set<String> = emptySet(),
    ): List<SyncInboxItem> {
        val liveByKey = live.groupBy { it.entry.libraryKey }
            .mapValues { (_, updates) -> updates.minBy { priority(it.action) } }
        return entries.mapNotNull { entry ->
            val key = entry.libraryKey
            if (key in dismissed) return@mapNotNull null
            val kind = when (statuses[key]) {
                GameSyncStatus.CONFLICT -> SyncItemKind.CONFLICT
                GameSyncStatus.CLOUD_NEWER -> SyncItemKind.CLOUD_NEWER
                else -> return@mapNotNull null
            }
            val update = liveByKey[key]
            SyncInboxItem(entry, kind, reason(kind, update, cloudActions[key]), update)
        }.sortedWith(compareBy<SyncInboxItem> { it.kind.ordinal }.thenBy { it.entry.game.presentation.primaryName })
    }

    /** [items] of everything [state] knows: the scanned games with their statuses and verdicts. */
    fun fromState(state: SourceUiState): List<SyncInboxItem> {
        val games = GameLibrary.sourceGames(state.sourceScans)
        val actions = GameStatusResolver.effectiveCloudActions(state.cloudUpdates, state.rememberedCloudVerdicts, state.snapshots)
        val statuses = GameStatusResolver.resolveAll(games, state.snapshots, state.saveSignatures, actions, state.gameSaveEnabled)
        return items(games, statuses, state.cloudUpdates, actions, state.syncDismissed)
    }

    /** "2 款冲突需确认 · 1 款云端较新"; null when nothing needs attention, so the banner takes no room. */
    fun bannerText(items: List<SyncInboxItem>): String? {
        val conflicts = items.count { it.kind == SyncItemKind.CONFLICT }
        val newer = items.count { it.kind == SyncItemKind.CLOUD_NEWER }
        return listOfNotNull(
            conflicts.takeIf { it > 0 }?.let { "$it 款冲突需确认" },
            newer.takeIf { it > 0 }?.let { "$it 款云端较新" },
        ).joinToString(" · ").ifEmpty { null }
    }

    private fun reason(kind: SyncItemKind, live: GameSaveCloudUpdate?, action: GameSaveRemoteAction?): String {
        if (live != null) {
            val message = live.message.ifBlank {
                if (live.action == GameSaveRemoteAction.AUTO_RESTORE) "云端有延续当前存档的更新" else "云端存档需要确认"
            }
            // A safe-restore verdict assumed the saves it inspected; they changed since.
            return if (kind == SyncItemKind.CONFLICT && live.action == GameSaveRemoteAction.AUTO_RESTORE) {
                "$message；但本机存档之后也有变化"
            } else {
                message
            }
        }
        return when (action) {
            GameSaveRemoteAction.REVIEW_REQUIRED -> "上次检查发现需要确认的云端版本；处理前会重新检查云端"
            GameSaveRemoteAction.AUTO_RESTORE -> if (kind == SyncItemKind.CONFLICT) {
                "上次检查发现云端较新，但本机存档之后也有变化"
            } else {
                "上次检查发现云端有延续当前存档的更新"
            }
            else -> "本机最新版本是冲突副本，云端当前版本不是它"
        }
    }

    private fun priority(action: GameSaveRemoteAction): Int = when (action) {
        GameSaveRemoteAction.REVIEW_REQUIRED -> 0
        GameSaveRemoteAction.AUTO_RESTORE -> 1
        GameSaveRemoteAction.WAIT_FOR_REMOTE -> 2
        GameSaveRemoteAction.UP_TO_DATE -> 3
    }
}
