package com.jun9100.opensave

import java.io.File

/** One save folder inside another device's archive. */
internal data class CrossDeviceArchivePart(
    /** Directory inside the extracted archive holding this folder; "" means the ZIP root (PC format). */
    val archivePath: String,
    /** Location kind from the Android manifest; null for the PC format. */
    val kind: String?,
)

internal enum class CrossDeviceArchiveFormat { ANDROID, PC }

internal data class CrossDeviceArchive(
    val format: CrossDeviceArchiveFormat,
    val parts: List<CrossDeviceArchivePart>,
)

/** Where one archive part goes: a local location's relativePath, or null = skip. */
internal data class CrossDevicePlan(
    val archive: CrossDeviceArchive,
    /** Local restorable locations (relativePath, kind), in display order. */
    val targets: List<CrossDeviceTarget>,
    /** Archive part index -> local relativePath; parts missing from the map are not decided yet. */
    val mapping: Map<Int, String?>,
) {
    /** Every part has a decision (a target or an explicit skip). */
    val complete: Boolean
        get() = mapping.size == archive.parts.size && archive.parts.indices.all(mapping::containsKey)

    /** At least one part goes somewhere. */
    val restoresAnything: Boolean
        get() = mapping.values.any { it != null }

    /** Parts the automatic rule could not place; the UI must ask about these. */
    val undecided: List<Int>
        get() = archive.parts.indices.filterNot(mapping::containsKey)
}

internal data class CrossDeviceTarget(val relativePath: String, val kind: String)

/**
 * Pure decisions for restoring another device's snapshot. Paths from the
 * source device are not comparable with this device's paths, so automatic
 * matching only uses location kinds from the Android manifest. A PC archive
 * has no kinds and can therefore only target a game with one save location.
 */
internal object CrossDeviceRestorePolicy {
    fun readArchive(extractedRoot: File): CrossDeviceArchive {
        val manifestFile = File(extractedRoot, GameSnapshotManifest.FILE_NAME)
        if (manifestFile.isFile) {
            val manifest = GameSnapshotManifest.read(manifestFile)
            val parts = manifest.locations.map { location ->
                require(File(extractedRoot, location.archivePath).isDirectory) {
                    "其他设备的存档不完整"
                }
                CrossDeviceArchivePart(archivePath = location.archivePath, kind = location.kind)
            }
            return CrossDeviceArchive(CrossDeviceArchiveFormat.ANDROID, parts)
        }

        require(extractedRoot.isDirectory && extractedRoot.listFiles()?.isNotEmpty() == true) {
            "其他设备的存档是空的"
        }
        return CrossDeviceArchive(
            format = CrossDeviceArchiveFormat.PC,
            parts = listOf(CrossDeviceArchivePart(archivePath = "", kind = null)),
        )
    }

    fun plan(archive: CrossDeviceArchive, targets: List<CrossDeviceTarget>): CrossDevicePlan {
        require(targets.isNotEmpty()) { "本机这款游戏没有可恢复的存档位置" }
        val mapping = when (archive.format) {
            CrossDeviceArchiveFormat.PC -> {
                if (archive.parts.size == 1 && targets.size == 1) {
                    mapOf(0 to targets.single().relativePath)
                } else {
                    emptyMap()
                }
            }

            CrossDeviceArchiveFormat.ANDROID -> {
                val archiveKinds = archive.parts.indices
                    .filter { archive.parts[it].kind != null }
                    .groupBy { archive.parts[it].kind!! }
                val targetKinds = targets.groupBy { it.kind }
                val usedTargets = mutableSetOf<String>()
                buildMap {
                    archive.parts.forEachIndexed { index, part ->
                        val kind = part.kind ?: return@forEachIndexed
                        val archiveMatches = archiveKinds[kind].orEmpty()
                        val targetMatches = targetKinds[kind].orEmpty()
                        if (archiveMatches.size == 1 && targetMatches.size == 1) {
                            val target = targetMatches.single()
                            // A malformed target list must never make one local
                            // location receive two archive folders.
                            if (usedTargets.add(target.relativePath)) {
                                put(index, target.relativePath)
                            }
                        }
                    }
                }
            }
        }
        return CrossDevicePlan(archive = archive, targets = targets, mapping = mapping)
    }

    fun choose(plan: CrossDevicePlan, part: Int, relativePath: String?): CrossDevicePlan {
        require(part in plan.archive.parts.indices) { "存档分片不存在" }
        require(relativePath == null || plan.targets.any { it.relativePath == relativePath }) {
            "存档位置不属于当前游戏"
        }
        val mapping = plan.mapping.toMutableMap()
        if (relativePath == null) {
            mapping[part] = null
        } else {
            // A local location can only receive one archive folder. Choosing it
            // again moves the decision here and leaves the old part undecided.
            mapping.entries
                .filter { (other, path) -> other != part && path == relativePath }
                .map { it.key }
                .forEach(mapping::remove)
            mapping[part] = relativePath
        }
        return plan.copy(mapping = mapping)
    }

    fun targetsOf(game: DetectedGame): List<CrossDeviceTarget> =
        GameLibrary.batchBackupLocations(game)
            .sortedBy { it.relativePath }
            .distinctBy { it.relativePath }
            .map { CrossDeviceTarget(relativePath = it.relativePath, kind = it.kind) }
}
