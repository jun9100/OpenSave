package com.jun9100.opensave

/**
 * Reads the [SaveSignature] of games' default backup locations
 * ([GameLibrary.batchBackupLocations], the set every backup path shares), one
 * listing per source. A game with no such location, or one any location of
 * which could not be listed, has no signature: unknown is never reported as a change.
 */
class SaveSignatureReader(
    private val storage: SafStorage,
    private val rootStorage: RootStorage,
) {
    /** Encoded signatures by [SourceGameEntry.libraryKey]; unknown games are left out. */
    suspend fun read(entries: List<SourceGameEntry>): Map<String, String> = buildMap {
        entries.groupBy { it.source }.forEach { (source, games) ->
            putAll(readSource(source, games.map { it.libraryKey to it.game }))
        }
    }

    /** The encoded signature of one game, or null when it cannot be read. */
    suspend fun read(source: SourceSelection, game: DetectedGame): String? =
        readSource(source, listOf("" to game))[""]

    private suspend fun readSource(
        source: SourceSelection,
        games: List<Pair<String, DetectedGame>>,
    ): Map<String, String> {
        val locations = games.associate { (key, game) ->
            key to GameLibrary.batchBackupLocations(game).map(DetectedSave::relativePath)
        }
        val paths = locations.values.flatten().distinct()
        if (paths.isEmpty()) return emptyMap()
        val byPath = when (source) {
            is SourceSelection.Saf -> storage.saveSignatures(source.uri, paths)
            is SourceSelection.Root -> rootStorage.saveSignatures(source.path, paths).getOrNull() ?: return emptyMap()
        }
        return locations.mapNotNull { (key, gamePaths) ->
            SaveSignature.ofLocations(gamePaths.map { byPath[it] })?.let { key to it.encode() }
        }.toMap()
    }
}
