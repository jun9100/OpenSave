package com.jun9100.opensave

/**
 * Cheap change hint for a game's save locations (plan §5.2): file count, total
 * size and newest modification time, read from directory listings without
 * opening a file. Equal signatures mean "probably unchanged"; whether a backup
 * is really new is still decided by the full [ContentHash].
 *
 * Times are whole seconds: SAF reports milliseconds, Root `stat` seconds and
 * `find -printf %T@` fractions, so one precision keeps a source comparable
 * with itself whichever listing produced it.
 */
data class SaveSignature(
    val fileCount: Int,
    val totalBytes: Long,
    val newestModifiedSeconds: Long,
) {
    fun encode(): String = "$VERSION:$fileCount:$totalBytes:$newestModifiedSeconds"

    operator fun plus(other: SaveSignature) = SaveSignature(
        fileCount = fileCount + other.fileCount,
        totalBytes = totalBytes + other.totalBytes,
        newestModifiedSeconds = maxOf(newestModifiedSeconds, other.newestModifiedSeconds),
    )

    companion object {
        const val VERSION = "s1"
        val EMPTY = SaveSignature(0, 0L, 0L)

        /** One file: [sizeBytes] (negative counts as 0) and its modification time in milliseconds. */
        fun ofFile(sizeBytes: Long, modifiedMillis: Long) = SaveSignature(
            fileCount = 1,
            totalBytes = sizeBytes.coerceAtLeast(0L),
            newestModifiedSeconds = Math.floorDiv(modifiedMillis.coerceAtLeast(0L), 1000L),
        )

        fun combine(parts: Iterable<SaveSignature>): SaveSignature = parts.fold(EMPTY, SaveSignature::plus)

        /**
         * The signature of one game: every location must be known, otherwise
         * the result is unknown (null) rather than a partial sum that would
         * read as a change.
         */
        fun ofLocations(locations: List<SaveSignature?>): SaveSignature? =
            if (locations.isEmpty() || locations.any { it == null }) null else combine(locations.filterNotNull())

        /**
         * Parses the Root listing written by [RootStorage.saveSignatures]: for
         * each requested path a [SECTION_MARKER] line, then either a
         * [MISSING_MARKER] line or one `size mtime` line per file, where mtime
         * is `find -printf %T@` (seconds with a fraction) or `stat -c %Y`.
         * Returns one entry per section in order; a missing directory or a
         * malformed line makes that section unknown.
         */
        fun parseRootListing(output: String): List<SaveSignature?> {
            val sections = mutableListOf<SaveSignature?>()
            var current: SaveSignature? = null
            var open = false
            fun close() {
                if (open) sections += current
            }
            for (raw in output.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                when {
                    line == SECTION_MARKER -> {
                        close()
                        current = EMPTY
                        open = true
                    }
                    !open -> Unit
                    line == MISSING_MARKER -> current = null
                    else -> {
                        val sum = current ?: continue
                        current = parseRootLine(line)?.let { sum + it }
                    }
                }
            }
            close()
            return sections
        }

        private fun parseRootLine(line: String): SaveSignature? {
            val parts = line.split(' ', '\t').filter(String::isNotEmpty)
            if (parts.size < 2) return null
            val size = parts[0].toLongOrNull() ?: return null
            val seconds = parts[1].substringBefore('.').toLongOrNull() ?: return null
            return SaveSignature(1, size.coerceAtLeast(0L), seconds.coerceAtLeast(0L))
        }

        const val SECTION_MARKER = "@@opensave-signature"
        const val MISSING_MARKER = "!!opensave-missing"
    }
}
