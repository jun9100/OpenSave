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

    /** An empty or missing workspace would wipe the source, so it is never written back. */
    fun requireWorkspace(workspace: File) {
        require(workspace.isDirectory && !workspace.list().isNullOrEmpty()) {
            "工作区为空：请先“复制到工作区”或恢复一份整来源快照"
        }
    }
}
