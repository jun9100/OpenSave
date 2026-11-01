package com.jun9100.opensave

/** What the library knows about one game's saves (plan §5.2); only states OpenSave can tell honestly. */
enum class GameSyncStatus(
    /** Detail pane wording. */
    val label: String,
    /** Row badge wording. */
    val shortLabel: String,
    val symbol: String,
) {
    NEVER_BACKED_UP("从未备份", "未备份", "○"),

    /** The cheap signature of the save locations differs from the one stored with the newest backup. */
    LOCAL_CHANGED("本地有变更", "有变更", "◆"),

    /** Backed up here, and game-save is enabled, but the newest version never reached it. */
    NOT_UPLOADED("已备份·未上传", "未上传", "▲"),

    /** Backed up here; game-save is not enabled, so there is nothing to upload to. */
    LOCAL_ONLY("已备份", "已备份", "●"),

    /** The newest version reached game-save and nothing newer is known on either side. */
    SYNCED("已同步", "已同步", "●"),

    /** A cloud check found a newer save that can be restored safely. */
    CLOUD_NEWER("云端较新", "云端较新", "▼"),

    /** A cloud check found a version that needs a decision, or local saves changed since a newer cloud one was seen. */
    CONFLICT("冲突需确认", "冲突", "⚠"),
    ;

    /** Counted by "备份有变更": nothing backed up yet, or saves changed since the newest backup. */
    val needsBackup: Boolean get() = this == NEVER_BACKED_UP || this == LOCAL_CHANGED
}

/** Pure status derivation, unit tested without Android. */
object GameStatusResolver {
    /**
     * [latest] is the newest ordinary (non-safety) local snapshot of the game.
     * [currentSignature] is the encoded [SaveSignature] of its locations now,
     * null while unknown; a change is only claimed when both it and the one
     * stored on [latest] are known. [cloudAction] is the last cloud check's
     * verdict for the game (none when it was up to date or never checked).
     */
    fun resolve(
        latest: SnapshotRecord?,
        currentSignature: String?,
        cloudAction: GameSaveRemoteAction?,
        cloudEnabled: Boolean,
    ): GameSyncStatus {
        val localChanged = latest != null &&
            latest.saveSignature.isNotBlank() &&
            !currentSignature.isNullOrBlank() &&
            latest.saveSignature != currentSignature
        when (cloudAction) {
            GameSaveRemoteAction.REVIEW_REQUIRED -> return GameSyncStatus.CONFLICT
            // The safe-restore verdict assumed the local saves it inspected; they changed since.
            GameSaveRemoteAction.AUTO_RESTORE ->
                return if (localChanged) GameSyncStatus.CONFLICT else GameSyncStatus.CLOUD_NEWER
            GameSaveRemoteAction.WAIT_FOR_REMOTE, GameSaveRemoteAction.UP_TO_DATE, null -> Unit
        }
        return when {
            latest == null -> GameSyncStatus.NEVER_BACKED_UP
            localChanged -> GameSyncStatus.LOCAL_CHANGED
            // A conflict copy or an unadvanced legacy head is not the cloud's current save.
            latest.uploaded && latest.notCloudHead -> GameSyncStatus.CONFLICT
            latest.uploaded -> GameSyncStatus.SYNCED
            cloudEnabled -> GameSyncStatus.NOT_UPLOADED
            else -> GameSyncStatus.LOCAL_ONLY
        }
    }

    /**
     * Statuses of every [entries] row by library key. [signatures] and
     * [cloudActions] are keyed by library key as well; safety copies taken
     * before a restore are not backups and are ignored.
     */
    fun resolveAll(
        entries: List<SourceGameEntry>,
        snapshots: List<SnapshotRecord>,
        signatures: Map<String, String>,
        cloudActions: Map<String, GameSaveRemoteAction>,
        cloudEnabled: Boolean,
    ): Map<String, GameSyncStatus> {
        val latestByKey = snapshots
            .asSequence()
            .filter { it.gameKey != null && !it.isSafetyCopy }
            .groupBy { "${it.sourceId}::${it.gameKey}" }
            .mapValues { (_, records) -> records.maxBy(SnapshotRecord::createdAtMillis) }
        return entries.associate { entry ->
            val key = entry.libraryKey
            key to resolve(latestByKey[key], signatures[key], cloudActions[key], cloudEnabled)
        }
    }

    /** The most pressing cloud verdict per library key: a decision beats a safe restore beats waiting. */
    fun cloudActions(updates: List<GameSaveCloudUpdate>): Map<String, GameSaveRemoteAction> = updates
        .groupBy { it.entry.libraryKey }
        .mapValues { (_, forGame) -> forGame.map(GameSaveCloudUpdate::action).minBy(::cloudPriority) }

    /**
     * Cloud actions for the status: the live verdicts of this session, plus the
     * remembered ones from earlier sessions that still describe the newest
     * local snapshot. Live verdicts win.
     */
    fun effectiveCloudActions(
        live: List<GameSaveCloudUpdate>,
        remembered: Map<String, CloudVerdict>,
        snapshots: List<SnapshotRecord>,
    ): Map<String, GameSaveRemoteAction> {
        val latest = latestRecordIds(snapshots)
        val stillValid = remembered
            .filter { (key, verdict) -> verdict.action != GameSaveRemoteAction.UP_TO_DATE && latest[key].orEmpty() == verdict.recordId }
            .mapValues { it.value.action }
        return stillValid + cloudActions(live)
    }

    /** Verdicts worth remembering from [live]: everything but "up to date", tied to the newest local snapshot. */
    fun verdictsToRemember(
        live: List<GameSaveCloudUpdate>,
        snapshots: List<SnapshotRecord>,
    ): Map<String, CloudVerdict> {
        val latest = latestRecordIds(snapshots)
        return cloudActions(live)
            .filterValues { it != GameSaveRemoteAction.UP_TO_DATE }
            .mapValues { (key, action) -> CloudVerdict(action, latest[key].orEmpty()) }
    }

    /** The newest ordinary snapshot of [libraryKey] (`sourceId::gameKey`); empty when it has none. */
    fun latestRecordId(snapshots: List<SnapshotRecord>, libraryKey: String): String = latestRecordIds(snapshots)[libraryKey].orEmpty()

    private fun latestRecordIds(snapshots: List<SnapshotRecord>): Map<String, String> = snapshots
        .filter { it.gameKey != null && !it.isSafetyCopy }
        .groupBy { "${it.sourceId}::${it.gameKey}" }
        .mapValues { (_, records) -> records.maxBy(SnapshotRecord::createdAtMillis).id }

    private fun cloudPriority(action: GameSaveRemoteAction): Int = when (action) {
        GameSaveRemoteAction.REVIEW_REQUIRED -> 0
        GameSaveRemoteAction.AUTO_RESTORE -> 1
        GameSaveRemoteAction.WAIT_FOR_REMOTE -> 2
        GameSaveRemoteAction.UP_TO_DATE -> 3
    }
}
