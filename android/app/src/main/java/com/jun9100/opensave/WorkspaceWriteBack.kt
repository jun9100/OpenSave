package com.jun9100.opensave

import java.io.File

/** A confirmed request to replace a saved source directory with the private workspace. */
data class WorkspaceWriteBackRequest(
    val source: SourceSelection,
    val label: String,
)

/**
 * Writing the workspace back is destructive, so the target must be a source
 * the user has already saved: never an unsaved Root path draft.
 */
object WorkspaceWriteBackPolicy {
    fun rootTarget(draftPath: String, persistedPath: String?): Result<String> = runCatching {
        val persisted = persistedPath?.takeIf(String::isNotBlank)
            ?: error("请先保存 Root 路径，再写回工作区")
        val canonicalPersisted = RootPathPolicy.canonicalize(persisted)
        val canonicalDraft = runCatching { RootPathPolicy.canonicalize(draftPath) }.getOrNull()
        require(canonicalDraft == canonicalPersisted) {
            "Root 路径有未保存的修改；请先点“保存并扫描”，或改回已保存的路径"
        }
        canonicalPersisted
    }

    /** Prefix of every write-back leftover; names without a kind come from older builds. */
    const val TEMPORARY_PREFIX = ".source-workspace-write-back-"

    /** The user's workspace, set aside while the source is imported. */
    const val HELD_PREFIX = "${TEMPORARY_PREFIX}held-"

    /** The imported source copy, thrown away after its safety snapshot. */
    const val DISCARDED_PREFIX = "${TEMPORARY_PREFIX}discarded-"

    /**
     * Cleans up after a write-back that the process did not finish. When the
     * workspace is missing, the newest held directory (or, from an older build,
     * an unlabelled one) was the user's workspace and is renamed back. Leftovers
     * are deleted only once a workspace exists, so a held workspace is never
     * deleted before it is recovered. Runs under the storage lock.
     */
    fun recoverHeldWorkspace(workspace: File) {
        val parent = workspace.absoluteFile.parentFile ?: return
        val leftovers = parent.listFiles { file -> file.name.startsWith(TEMPORARY_PREFIX) }.orEmpty().toList()
        if (leftovers.isEmpty()) return
        if (!workspace.exists()) {
            val candidates = leftovers.filter { it.isDirectory && !it.name.startsWith(DISCARDED_PREFIX) }
            val heldOnes = candidates.filter { it.name.startsWith(HELD_PREFIX) }
            val recovered = (heldOnes.ifEmpty { candidates }).maxByOrNull(File::lastModified) ?: return
            if (!recovered.renameTo(workspace)) return
        }
        if (workspace.isDirectory) {
            leftovers.filter { it.exists() }.forEach { runCatching { it.deleteRecursively() } }
        }
    }

    /** An empty or missing workspace would wipe the source, so it is never written back. */
    fun requireWorkspace(workspace: File) {
        require(workspace.isDirectory && !workspace.list().isNullOrEmpty()) {
            "工作区为空：请先“复制到工作区”或恢复一份整来源快照"
        }
    }
}
