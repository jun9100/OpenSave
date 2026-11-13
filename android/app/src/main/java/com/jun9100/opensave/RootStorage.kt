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

class RootStorage(private val context: Context) : PathStorage {
    private val selectionStore = SourceSelectionStore(context)

    fun persistedRootPath(): String? = selectionStore.rootPath()

    fun persistRootPath(path: String): Result<String> = selectionStore.saveRootPath(path)

    override fun workspaceDirectory(): File = File(context.filesDir, WORKSPACE_DIRECTORY)

    fun checkRootAccess(): Result<Unit> = runCatching {
        val output = RootCommand.requireSuccess("id -u")
        require(output.trim() == "0") { "su did not grant uid 0" }
    }

    override suspend fun pathExists(rawPath: String): Result<Boolean> = withContext(Dispatchers.IO) {
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

    override suspend fun scanPath(
        rawPath: String,
        androidGames: Map<String, AndroidGameMetadata>,
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

    /**
     * [SaveSignature] of each of [relativePaths] below [rawRootPath] in one su
     * call, null for a missing location. Uses `find -printf '%s %T@'`, and
     * `find -exec stat -c '%s %Y'` where toybox find has no -printf (plan §5.2).
     */
    /** The bytes of [fileName] in [directory] (an absolute path), or null when missing or larger than [maxBytes]. */
    override suspend fun readSmallFile(directory: String, fileName: String, maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val path = RootCommand.shellQuote(directory.trimEnd('/') + "/" + fileName)
            val result = RootCommand.execute("[ -f $path ] && [ \$(stat -c %s $path) -le $maxBytes ] && base64 -w 0 $path")
            if (result.exitCode != 0 || result.output.isBlank()) return@runCatching null
            android.util.Base64.decode(result.output, android.util.Base64.DEFAULT)
        }.getOrNull()
    }

    /**
     * 列举目录里的名字。`find … | head -n` 让 toybox 在取够条数后自己断掉管道，
     * 再套一层 `timeout`：游戏目录动辄几十 GB，真遇上符号链接环或挂载点卡死时，
     * 这条命令不能把 App 的一个线程永远钉在这里。
     */
    override suspend fun listNames(rawPath: String, maxDepth: Int, limit: Int): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val path = RootPathPolicy.canonicalizeForScan(rawPath)
                checkRootAccess().getOrThrow()
                val quoted = RootCommand.shellQuote(path)
                val result = RootCommand.execute(
                    "timeout $LIST_TIMEOUT find $quoted -mindepth 1 -maxdepth $maxDepth -print 2>/dev/null | head -n $limit",
                )
                result.output.lineSequence()
                    .asSequence()
                    .filter { it.isNotBlank() }
                    .map { it.substringAfterLast('/') }
                    .filter { it.isNotBlank() }
                    .toList()
            }
        }

        override suspend fun saveSignatures(rawRootPath: String, relativePaths: List<String>): Result<Map<String, SaveSignature?>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = RootPathPolicy.canonicalizeForScan(rawRootPath)
                val paths = relativePaths.distinct()
                if (paths.isEmpty()) return@runCatching emptyMap()
                val rootPrefix = root.trimEnd('/') + "/"
                val absolute = paths.map { relative ->
                    File(root, GameSnapshotManifest.safeRelativePath(relative)).path.also {
                        require(it.startsWith(rootPrefix)) { "存档路径超出已扫描目录" }
                    }
                }
                val section = RootCommand.shellQuote(SaveSignature.SECTION_MARKER)
                val missing = RootCommand.shellQuote(SaveSignature.MISSING_MARKER)
                val script = buildString {
                    append("if find / -maxdepth 0 -printf '' >/dev/null 2>&1; then P=1; else P=0; fi; ")
                    append("for p in ${absolute.joinToString(" ") { RootCommand.shellQuote(it) }}; do ")
                    append("printf '%s\\n' $section; ")
                    append("if [ -z \"\$(find \"\$p\" -maxdepth 0 -type d -print 2>/dev/null)\" ]; then printf '%s\\n' $missing; continue; fi; ")
                    append("if [ \$P = 1 ]; then find \"\$p\" -type f -printf '%s %T@\\n' 2>/dev/null; ")
                    append("else find \"\$p\" -type f -exec stat -c '%s %Y' {} + 2>/dev/null; fi; ")
                    append("done")
                }
                val result = RootCommand.execute(script)
                require(result.exitCode == 0) { "Root signature listing failed (${result.exitCode})" }
                val sections = SaveSignature.parseRootListing(result.output)
                require(sections.size == paths.size) { "Root signature listing is incomplete" }
                paths.zip(sections).toMap()
            }
        }

    /**
     * 目录指纹：路径 + mtime，前 [maxDepth] 层。
     *
     * 走 `find -type d` 一次列出，再让 shell 把时间戳贴上去；toybox 没有
     * `-printf` 的机型退化成 `-exec stat`。两种输出都只有一层"时间 + 路径"，
     * 在 Kotlin 侧按第一个空格切开，避免文件名里的空格干扰。
     */
    override suspend fun dirStamps(rawPath: String, maxDepth: Int, maxEntries: Int): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val path = RootPathPolicy.canonicalizeForScan(rawPath)
                checkRootAccess().getOrThrow()
                val quoted = RootCommand.shellQuote(path)
                val result = RootCommand.execute(
                    "if find / -maxdepth 0 -printf '' >/dev/null 2>&1; then " +
                        "find $quoted -mindepth 1 -maxdepth $maxDepth -type d -printf '%T@ %p\\n' 2>/dev/null | head -n ${maxEntries + 1}; " +
                        "else find $quoted -mindepth 1 -maxdepth $maxDepth -type d -exec stat -c '%Y %n' {} + 2>/dev/null; fi",
                )
                val stamps = result.output.lineSequence()
                    .mapNotNull(DirStamps::parseLine)
                    .sorted()
                    .toList()
                DirStamps.limit(stamps, maxEntries)
            }
        }


    override suspend fun copyGameLocations(
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

    override suspend fun restoreGameLocations(
        rawRootPath: String,
        locations: List<GameSnapshotLocation>,
        extractedRoot: File,
        createMissing: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = RootPathPolicy.canonicalizeForScan(rawRootPath)
            checkRootAccess().getOrThrow()
            require(locations.isNotEmpty()) { "游戏快照没有存档位置" }
            val rootPrefix = root.trimEnd('/') + "/"
            if (createMissing) {
                // 云端下发：目标存档目录可能还不存在，先按相对路径建出来。
                locations.forEach { location ->
                    val relative = GameSnapshotManifest.safeRelativePath(location.relativePath)
                    val target = File(root, relative).canonicalFile
                    require(target.path.startsWith(rootPrefix)) { "恢复目标超出已扫描目录" }
                    RootCommand.requireSuccess("mkdir -p -- ${RootCommand.shellQuote(target.path)}")
                }
            }
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

    override suspend fun importPath(rawPath: String): Result<CopyReport> = withContext(Dispatchers.IO) {
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

    override suspend fun restoreWorkspace(rawPath: String): Result<CopyReport> = withContext(Dispatchers.IO) {
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
        /** 列举游戏目录时的墙钟上限（秒）。 */
        const val LIST_TIMEOUT = 20
    }
}
