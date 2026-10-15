package com.jun9100.opensave

import android.content.Context
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class RootCommandResult(
    val exitCode: Int,
    val output: String,
)

internal object RootCommand {
    private const val TIMEOUT_MILLIS = 60_000L

    fun execute(command: String): RootCommandResult {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("Root command timed out")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return RootCommandResult(process.exitValue(), output.trim())
    }

    fun requireSuccess(command: String): String {
        val result = execute(command)
        require(result.exitCode == 0) {
            "Root command failed (${result.exitCode})${result.output.take(512).let { if (it.isBlank()) "" else ": $it" }}"
        }
        return result.output
    }

    fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}

class RootStorage(private val context: Context) {
    private val selectionStore = SourceSelectionStore(context)

    fun persistedRootPath(): String? = selectionStore.rootPath()

    fun persistRootPath(path: String): Result<String> = selectionStore.saveRootPath(path)

    fun workspaceDirectory(): File = File(context.filesDir, WORKSPACE_DIRECTORY)

    fun checkRootAccess(): Result<Unit> = runCatching {
        val output = RootCommand.requireSuccess("id -u")
        require(output.trim() == "0") { "su did not grant uid 0" }
    }

    suspend fun importPath(rawPath: String): Result<CopyReport> = withContext(Dispatchers.IO) {
        runCatching {
            val path = RootPathPolicy.canonicalize(rawPath)
            checkRootAccess().getOrThrow()
            requireDirectory(path)

            val workspace = workspaceDirectory()
            clearWorkspace(workspace)
            val sourceArg = RootCommand.shellQuote(path)
            val workspaceArg = RootCommand.shellQuote(workspace.canonicalPath)
            RootCommand.requireSuccess(
                "set -e; cp -a -- $sourceArg/. $workspaceArg/; " +
                    "chown -R ${Process.myUid()}:${Process.myUid()} $workspaceArg",
            )
            WorkspaceReport.build(workspace, File(path).name.ifBlank { path })
        }
    }

    suspend fun restoreWorkspace(rawPath: String): Result<CopyReport> = withContext(Dispatchers.IO) {
        runCatching {
            val path = RootPathPolicy.canonicalize(rawPath)
            checkRootAccess().getOrThrow()
            requireDirectory(path)
            val workspace = workspaceDirectory()
            val report = WorkspaceReport.build(workspace)
            val target = File(path).canonicalFile
            val parent = target.parentFile ?: error("Root target has no parent")
            val metadata = readMetadata(target)
            val staging = File(parent, ".opensave-restore-${UUID.randomUUID()}")
            val oldTarget = File(parent, ".opensave-old-${UUID.randomUUID()}")
            val targetArg = RootCommand.shellQuote(target.path)
            val workspaceArg = RootCommand.shellQuote(workspace.canonicalPath)
            val stagingArg = RootCommand.shellQuote(staging.path)
            val oldTargetArg = RootCommand.shellQuote(oldTarget.path)
            val command = """
                set -e
                rm -rf -- $stagingArg
                rm -rf -- $oldTargetArg
                mkdir -- $stagingArg
                if ! cp -a -- $workspaceArg/. $stagingArg/; then rm -rf -- $stagingArg; exit 1; fi
                if ! chown -R ${metadata.uid}:${metadata.gid} $stagingArg; then rm -rf -- $stagingArg; exit 1; fi
                if ! chmod ${metadata.mode} $stagingArg; then rm -rf -- $stagingArg; exit 1; fi
                if ! mv -- $targetArg $oldTargetArg; then rm -rf -- $stagingArg; exit 1; fi
                if ! mv -- $stagingArg $targetArg; then mv -- $oldTargetArg $targetArg; rm -rf -- $stagingArg; exit 1; fi
                rm -rf -- $oldTargetArg
            """.trimIndent()
            try {
                RootCommand.requireSuccess(command)
            } catch (error: Throwable) {
                runCatching {
                    RootCommand.requireSuccess("rm -rf -- $stagingArg; rm -rf -- $oldTargetArg")
                }
                throw error
            }
            report
        }
    }

    private fun requireDirectory(path: String) {
        RootCommand.requireSuccess("test -d ${RootCommand.shellQuote(path)}")
    }

    private fun clearWorkspace(workspace: File) {
        if (workspace.exists() && !workspace.deleteRecursively()) {
            RootCommand.requireSuccess("rm -rf -- ${RootCommand.shellQuote(workspace.canonicalPath)}")
        }
        require(workspace.mkdirs() || workspace.isDirectory) {
            "Could not create the private workspace"
        }
    }

    private fun readMetadata(target: File): TargetMetadata {
        val value = RootCommand.requireSuccess(
            "stat -c '%u:%g:%a' ${RootCommand.shellQuote(target.path)}",
        )
        val match = Regex("^(\\d+):(\\d+):(\\d+)$").matchEntire(value.trim())
            ?: error("Could not read Root target ownership")
        return TargetMetadata(
            uid = match.groupValues[1],
            gid = match.groupValues[2],
            mode = match.groupValues[3],
        )
    }

    private data class TargetMetadata(
        val uid: String,
        val gid: String,
        val mode: String,
    )

    private companion object {
        const val WORKSPACE_DIRECTORY = "source-workspace"
    }
}
