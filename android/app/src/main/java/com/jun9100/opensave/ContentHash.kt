package com.jun9100.opensave

/**
 * Deduplication hash of the save data captured for one game (plan §5.3).
 *
 * Unlike [Hashing.manifestHash] over the whole workspace, it leaves out
 * `opensave-game.json` (whose display name differs between capture paths) and
 * keys every file by the save location's own relative path instead of its
 * archive slot, so the same live save always hashes the same no matter which
 * path captured it, in what order files were copied or what their mtimes are.
 *
 * It is only used to decide whether a new snapshot is needed; the head
 * protocol keeps comparing [Hashing.manifestHash] unchanged.
 */
object ContentHash {
    const val VERSION = 2

    private const val DOMAIN = "opensave-content-v$VERSION"

    /**
     * [files] are workspace-relative records (directories end in `/` with size
     * -1), excluding the manifest; [locations] maps each archive slot back to
     * the save location it was copied from.
     */
    fun of(files: Iterable<FileRecord>, locations: List<GameSnapshotLocation>): String {
        val slots = locations.associate { it.archivePath to it.relativePath }
        val keyed = files.map { file ->
            val slot = file.relativePath.substringBefore('/')
            val rest = file.relativePath.substringAfter('/', missingDelimiterValue = "")
            val location = slots[slot]
            val path = when {
                location == null -> "?/${file.relativePath}"
                rest.isEmpty() && file.sizeBytes < 0 -> "$location/"
                rest.isEmpty() -> location
                else -> "$location/$rest"
            }
            file.copy(relativePath = path)
        }
        return Hashing.sha256("$DOMAIN\n${Hashing.manifestHash(keyed)}")
    }
}
