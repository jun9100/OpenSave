package com.jun9100.opensave

import java.util.Locale

/** A display-level game that can contain more than one detected save path. */
data class DetectedGame(
    val gameKey: String,
    val presentation: GamePresentation,
    val saves: List<DetectedSave>,
) {
    val locationCount: Int
        get() = saves.size

    val candidateCount: Int
        get() = saves.count { it.candidate }

    val isCandidateOnly: Boolean
        get() = saves.all { it.candidate || it.kind == "android-app" }
}

data class SourceScanResult(
    val source: SourceSelection,
    val label: String,
    val scan: SaveScanReport? = null,
    val skippedReason: String? = null,
    val access: SourceAccessStatus = SourceAccessStatus.pending(source.requiredAccessCapability()),
) {
    val sourceId: String
        get() = SnapshotNaming.sourceId(source.idSeed)
}

data class SourceGameEntry(
    val source: SourceSelection,
    val sourceId: String,
    val sourceLabel: String,
    val sourceAccess: SourceAccessStatus,
    val game: DetectedGame,
) {
    val libraryKey: String
        get() = "$sourceId::${game.gameKey}"

    val saveType: GameSaveType
        get() = GameSaveTypes.forGame(game)
}

object GameLibrary {
    fun group(saves: List<DetectedSave>): List<DetectedGame> = saves
        .groupBy(::stableGameKey)
        .map { (gameKey, locations) ->
            val sortedLocations = locations.sortedWith(
                compareBy<DetectedSave> { it.candidate }
                    .thenBy { it.relativePath.lowercase(Locale.ROOT) },
            )
            DetectedGame(
                gameKey = gameKey,
                presentation = GameCatalog.present(representative(sortedLocations)).let { presentation ->
                    // The user's own name wins over every catalog or learned one.
                    GameNames.override(gameKey)?.let { presentation.copy(localizedName = it) } ?: presentation
                },
                saves = sortedLocations,
            )
        }
        .sortedWith(
            compareBy<DetectedGame> { it.presentation.primaryName.lowercase(Locale.ROOT) }
                .thenBy { it.gameKey },
        )

    fun batchBackupEligible(games: List<DetectedGame>): List<DetectedGame> =
        games.filter(::canBatchBackup)

    fun sourceGames(sources: List<SourceScanResult>): List<SourceGameEntry> = sources
        .flatMap { source ->
            source.scan?.let { scan ->
                group(scan.detected).map { game ->
                    SourceGameEntry(
                        source = source.source,
                        sourceId = source.sourceId,
                        sourceLabel = source.label,
                        sourceAccess = source.access,
                        game = game,
                    )
                }
            }.orEmpty()
        }
        .let(::dropCoveredLocations)
        // Candidate-only entries are not games yet; keep them after the real ones.
        .sortedWith(
            compareBy<SourceGameEntry> { it.game.isCandidateOnly }
                .thenBy { it.game.presentation.primaryName.lowercase(Locale.ROOT) }
                .thenBy { it.sourceLabel.lowercase(Locale.ROOT) }
                .thenBy { it.game.gameKey },
        )

    /**
     * 同一个存档目录被两个重叠来源各扫一次时，只留一份。
     *
     * 真机上 `Eden Nightly · Android/data`（根 `<pkg>`）和
     * `Android 游戏：Eden Nightly · Android/data`（根 `<pkg>/files`）扫的是同一棵树，
     * 于是 `files/nand/user/save/…/<TITLE_ID>` 与 `nand/user/save/…/<TITLE_ID>`
     * 其实是同一个目录，却被算成两个存档位置：库里的「2 个存档位置」、云端检查
     * 里同一款游戏出现两次，都是这么来的。
     *
     * 判重看绝对路径，根更长（更具体）的那份先占位——它显示的相对路径更短。
     */
    private fun dropCoveredLocations(entries: List<SourceGameEntry>): List<SourceGameEntry> {
        val kept = ArrayList<SourceGameEntry>(entries.size)
        val covered = HashMap<String, MutableSet<String>>()
        val ordered = entries.sortedWith(
            compareByDescending<SourceGameEntry> { entry ->
                (entry.source as? SourceSelection.Root)?.path?.length ?: 0
            },
        )
        for (entry in ordered) {
            val seen = covered.getOrPut(entry.game.gameKey) { HashSet() }
            val paths = entry.game.saves.mapNotNull { save -> absoluteSavePath(entry, save) }
            // 一个存档都定位不到就原样保留，宁可重复也不要吞掉条目。
            if (paths.isEmpty() || paths.any { it !in seen }) {
                kept += entry
                seen += paths
            }
        }
        return kept
    }

    /** 存档位置的绝对身份；SAF 拿不到真实路径，只能按 URI + 相对路径认。 */
    private fun absoluteSavePath(entry: SourceGameEntry, save: DetectedSave): String? = when (val source = entry.source) {
        is SourceSelection.Root -> {
            val root = source.path.trimEnd('/')
            val relative = save.relativePath.trim('/')
            when {
                relative.isEmpty() || relative == "." -> root
                else -> "$root/$relative"
            }
        }
        is SourceSelection.Saf -> "${source.uri}#${save.relativePath.trim('/')}"
    }

    fun batchBackupEligibleEntries(entries: List<SourceGameEntry>): List<SourceGameEntry> =
        entries.filter { canBatchBackup(it.game) }

    fun canBatchBackup(game: DetectedGame): Boolean = batchBackupLocations(game).isNotEmpty()

    fun batchBackupLocations(game: DetectedGame): List<DetectedSave> = backupLocations(game)

    /**
     * The one location set shared by single-game, batch and automatic backup
     * (plan §5.3), so unchanged saves hash the same on every path. Candidates
     * and Android app data are opt-in; the scan root itself is never a game location.
     */
    fun backupLocations(
        game: DetectedGame,
        includeCandidates: Boolean = false,
        includeAndroidApp: Boolean = false,
    ): List<DetectedSave> = game.saves.filter { save ->
        (includeCandidates || !save.candidate) &&
            (includeAndroidApp || save.kind != "android-app") &&
            save.relativePath != "."
    }

    private fun representative(saves: List<DetectedSave>): DetectedSave = saves.maxWithOrNull(
        compareBy<DetectedSave> { !it.candidate }
            .thenBy { it.gameKey != null }
            .thenBy { it.titleId != null }
            .thenBy { it.displayName != null },
    ) ?: error("game group cannot be empty")

    private fun stableGameKey(save: DetectedSave): String {
        val presentation = GameCatalog.present(save)
        presentation.steamAppId?.let { return "steam:$it" }
        presentation.androidPackageName?.let { return "android:$it" }
        save.gameKey?.takeIf { it.isNotBlank() }?.let { return it }
        return listOf(
            "path",
            save.kind,
            save.titleId.orEmpty(),
            save.displayName.orEmpty(),
            save.relativePath,
        ).joinToString(":")
    }
}
