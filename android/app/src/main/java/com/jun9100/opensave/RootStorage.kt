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
        // Magisk's app-local mount namespace hides other app data; -mm uses the global namespace.
        val process = ProcessBuilder("su", "-mm", "-c", command)
            .redirectErrorStream(true)
            .start()
        var output = ""
        val reader = Thread {
            output = process.inputStream.bufferedReader().use { it.readText() }
        }.apply {
            isDaemon = true
            start()
        }
        val finished = process.waitFor(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(1_000L)
            error("Root command timed out")
        }
        reader.join(TIMEOUT_MILLIS)
        if (reader.isAlive) {
            process.destroyForcibly()
            error("Root command output timed out")
        }
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

    suspend fun pathExists(rawPath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val path = RootPathPolicy.canonicalizeForScan(rawPath)
            checkRootAccess().getOrThrow()
            val result = RootCommand.execute("test -d ${RootCommand.shellQuote(path)}")
            require(result.exitCode == 0 || result.exitCode == 1) {
                "Root directory check failed (${result.exitCode})${result.output.take(256)}"
            }
            result.exitCode == 0
        }
    }

    suspend fun scanPath(
        rawPath: String,
        androidGames: Map<String, AndroidGameMetadata> = emptyMap(),
    ): Result<SaveScanReport> = withContext(Dispatchers.IO) {
        runCatching {
            val path = RootPathPolicy.canonicalizeForScan(rawPath)
            checkRootAccess().getOrThrow()
            val result = RootCommand.execute(
                "set +e; " +
                    "find ${RootCommand.shellQuote(path)} -type d -print 2>/dev/null; " +
                    "find_status=\$?; " +
                    "printf '%s%s\\n' ${RootCommand.shellQuote(FIND_STATUS_MARKER)} \"\$find_status\"",
            )
            require(result.exitCode == 0) {
                "Root command failed (${result.exitCode})${result.output.take(512).let { if (it.isBlank()) "" else ": $it" }}"
            }
            val lines = result.output.lineSequence().toList()
            val findStatus = lines.lastOrNull { it.startsWith(FIND_STATUS_MARKER) }
                ?.removePrefix(FIND_STATUS_MARKER)
                ?.toIntOrNull()
                ?: error("Root scan did not return a find status")
            val directories = lines
                .filterNot { it.startsWith(FIND_STATUS_MARKER) }
                .map(String::trimEnd)
                .filter(String::isNotEmpty)
                .toList()
            val truncated = directories.size > MAX_SCAN_DIRECTORIES
            SaveSourceDetector.scanDirectories(
                rootPath = path,
                directoryPaths = directories.take(MAX_SCAN_DIRECTORIES),
                selectedRootName = File(path).name.ifBlank { path },
                unreadableDirectoryCount = if (findStatus == 0) 0 else 1,
                truncated = truncated,
                androidGames = androidGames,
            )
        }
    }

    suspend fun copyGameLocations(
        rawRootPath: String,
        locations: List<GameSnapshotLocation>,
        destinationRoot: File,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = RootPathPolicy.canonicalizeForScan(rawRootPath)
            checkRootAccess().getOrThrow()
            require(locations.isNotEmpty()) { "游戏没有可备份的存档位置" }
            clearWorkspace(destinationRoot)
            val rootPrefix = root.trimEnd('/') + "/"
            locations.forEach { location ->
                val relative = GameSnapshotManifest.safeRelativePath(location.relativePath)
                val source = File(root, relative).canonicalPath
                require(source.startsWith(rootPrefix)) { "存档路径超出已扫描目录" }
                requireDirectory(source)
                val sourceArg = RootCommand.shellQuote(source)
                val destination = File(destinationRoot, PathPolicy.requireSafeDocumentName(location.archivePath))
                val destinationArg = RootCommand.shellQuote(destination.canonicalPath)
                val uid = Process.myUid()
                RootCommand.requireSuccess(
                    "set -e; " +
                        "if [ -n \"\$(find $sourceArg -type l -print -quit)\" ]; then echo 'symlink in save path'; exit 1; fi; " +
                        "if [ -n \"\$(find $sourceArg ! -type d ! -type f -print -quit)\" ]; then echo 'special file in save path'; exit 1; fi; " +
                        "mkdir -- $destinationArg; " +
                        "cp -a -- ${RootCommand.shellQuote("$source/.")} ${RootCommand.shellQuote("${destination.path}/")}; " +
                        "chown -R $uid:$uid $destinationArg",
                )
            }
        }
    }

    suspend fun restoreGameLocations(
        rawRootPath: String,
        locations: List<GameSnapshotLocation>,
        extractedRoot: File,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = RootPathPolicy.canonicalizeForScan(rawRootPath)
            checkRootAccess().getOrThrow()
            require(locations.isNotEmpty()) { "游戏快照没有存档位置" }
            val rootPrefix = root.trimEnd('/') + "/"
            val token = UUID.randomUUID().toString()
            val operations = locations.mapIndexed { index, location ->
                val relative = GameSnapshotManifest.safeRelativePath(location.relativePath)
                val target = File(root, relative).canonicalFile
                require(target.path.startsWith(rootPrefix)) { "恢复目标超出已扫描目录" }
                requireDirectory(target.path)
                val archivePath = PathPolicy.requireSafeDocumentName(location.archivePath)
                val staged = File(extractedRoot, archivePath).canonicalFile
                require(staged.path.startsWith(extractedRoot.canonicalPath + File.separator) && staged.isDirectory) {
                    "游戏快照缺少存档位置：$archivePath"
                }
                val metadata = readMetadata(target)
                val parent = target.parentFile ?: error("游戏存档目录没有父目录")
                GameRestoreOperation(
                    target = target,
                    prepared = File(parent, ".opensave-game-new-$token-$index"),
                    backup = File(parent, ".opensave-game-old-$token-$index"),
                    staged = staged,
                    uid = metadata.uid,
                    gid = metadata.gid,
                    mode = metadata.mode,
                )
            }
            require(operations.map { it.target.path }.distinct().size == operations.size) {
                "游戏快照包含重复恢复目标"
            }
            require(operations.none { current ->
                operations.any { other -> other != current && other.target.path.startsWith(current.target.path.trimEnd('/') + "/") }
            }) { "游戏快照包含重叠恢复目标" }

            val commands = buildString {
                appendLine("set -e")
                operations.forEach { operation ->
                    val preparedArg = RootCommand.shellQuote(operation.prepared.path)
                    appendLine("rm -rf -- $preparedArg")
                    appendLine("mkdir -- $preparedArg")
                    appendLine("cp -a -- ${RootCommand.shellQuote("${operation.staged.path}/.")} ${RootCommand.shellQuote("${operation.prepared.path}/")}")
                    appendLine("chown -R ${operation.uid}:${operation.gid} $preparedArg")
                    appendLine("chmod ${operation.mode} $preparedArg")
                }
                operations.forEachIndexed { index, operation ->
                    val target = RootCommand.shellQuote(operation.target.path)
                    val backup = RootCommand.shellQuote(operation.backup.path)
                    appendLine("if ! mv -- $target $backup; then")
                    operations.take(index).asReversed().forEach { moved ->
                        appendLine("mv -- ${RootCommand.shellQuote(moved.backup.path)} ${RootCommand.shellQuote(moved.target.path)}")
                    }
                    operations.forEach { appendLine("rm -rf -- ${RootCommand.shellQuote(it.prepared.path)}") }
                    appendLine("exit 1")
                    appendLine("fi")
                }
                operations.forEachIndexed { index, operation ->
                    val prepared = RootCommand.shellQuote(operation.prepared.path)
                    val target = RootCommand.shellQuote(operation.target.path)
                    appendLine("if ! mv -- $prepared $target; then")
                    operations.take(index).forEach { published ->
                        appendLine("rm -rf -- ${RootCommand.shellQuote(published.target.path)}")
                    }
                    operations.forEach { old ->
                        appendLine("if [ -e ${RootCommand.shellQuote(old.backup.path)} ]; then mv -- ${RootCommand.shellQuote(old.backup.path)} ${RootCommand.shellQuote(old.target.path)}; fi")
                    }
                    operations.forEach { appendLine("rm -rf -- ${RootCommand.shellQuote(it.prepared.path)}") }
                    appendLine("exit 1")
                    appendLine("fi")
                }
                operations.forEach { appendLine("rm -rf -- ${RootCommand.shellQuote(it.backup.path)}") }
            }
            try {
                RootCommand.requireSuccess(commands)
            } catch (error: Throwable) {
                runCatching {
                    RootCommand.requireSuccess(
                        operations.joinToString(";") { "rm -rf -- ${RootCommand.shellQuote(it.prepared.path)}" },
                    )
                }
                throw error
            }
            Unit
        }
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
        // Some rooted Android builds deny stat/test on another app's data path while find can read it.
        RootCommand.requireSuccess(
            "find ${RootCommand.shellQuote(path)} -maxdepth 0 -type d -print >/dev/null 2>&1",
        )
    }

    private fun clearWorkspace(workspace: File) {
        // Root mode may hold a full GameHub/Wine tree; Kotlin's recursive delete is too slow here.
        RootCommand.requireSuccess("rm -rf -- ${RootCommand.shellQuote(workspace.canonicalPath)}")
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

    private data class GameRestoreOperation(
        val target: File,
        val prepared: File,
        val backup: File,
        val staged: File,
        val uid: String,
        val gid: String,
        val mode: String,
    )

    private companion object {
        const val WORKSPACE_DIRECTORY = "source-workspace"
        const val MAX_SCAN_DIRECTORIES = 50_000
        const val FIND_STATUS_MARKER = "__OPEN_SAVE_FIND_STATUS__="
    }
}
