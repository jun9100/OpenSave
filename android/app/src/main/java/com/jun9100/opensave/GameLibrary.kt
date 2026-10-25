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
) {
    val sourceId: String
        get() = SnapshotNaming.sourceId(source.idSeed)
}

data class SourceGameEntry(
    val source: SourceSelection,
    val sourceId: String,
    val sourceLabel: String,
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
                presentation = GameCatalog.present(representative(sortedLocations)),
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
                    SourceGameEntry(source.source, source.sourceId, source.label, game)
                }
            }.orEmpty()
        }
        .sortedWith(
            compareBy<SourceGameEntry> { it.game.presentation.primaryName.lowercase(Locale.ROOT) }
                .thenBy { it.sourceLabel.lowercase(Locale.ROOT) }
                .thenBy { it.game.gameKey },
        )

    fun batchBackupEligibleEntries(entries: List<SourceGameEntry>): List<SourceGameEntry> =
        entries.filter { canBatchBackup(it.game) }

    fun canBatchBackup(game: DetectedGame): Boolean = batchBackupLocations(game).isNotEmpty()

    fun batchBackupLocations(game: DetectedGame): List<DetectedSave> =
        game.saves.filter { !it.candidate && it.kind != "android-app" }

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
