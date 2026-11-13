package com.jun9100.opensave

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * `/storage/emulated/0` 下路径的规范化与边界检查。与 Android API 无关，
 * 便于在纯 JVM 单测里覆盖（见 AllFilesPathPolicyTest）。
 */
object AllFilesPathPolicy {
    const val PRIMARY_ROOT: String = "/storage/emulated/0"

    /**
     * [rawPath] 的规范绝对路径。只接受主共享存储之下的路径：`Android/data`、
     * `Android/obb`、用户自己的目录都在这个范围里；`/data/user/0/<pkg>` 之类
     * 需要 root 的路径在这里直接拒绝，避免上层误以为免 root 能读。
     */
    fun canonicalize(rawPath: String): String {
        val trimmed = rawPath.trim().trimEnd('/').ifEmpty { PRIMARY_ROOT }
        require(trimmed.startsWith("/")) { "路径必须是绝对路径：$rawPath" }
        val canonical = File(trimmed).canonicalPath
        require(canonical == PRIMARY_ROOT || canonical.startsWith("$PRIMARY_ROOT/")) {
            "「所有文件访问」只覆盖 $PRIMARY_ROOT 之下：$canonical"
        }
        return canonical
    }

    /** 「共享存储 · 全盘补漏」扫的就是这个根，也只有它需要剪枝。 */
    fun isSharedStorageRoot(path: String): Boolean = path.trimEnd('/') == PRIMARY_ROOT

    /**
     * 全盘扫描时整棵跳过的目录。
     *
     * 这些名字下面不会有存档，而在 256 GB 的共享存储里它们动辄占掉几十万条目：
     * 实测这台机器 `/storage/emulated/0` 有 370,107 个条目，其中 350,512 个在
     * `switch2-games/<TITLE_ID>-work/` 这种解压出来的游戏树里。逐个 `stat`
     * 一遍要 15.9 秒，而存档一个都不会在那里。
     */
    /**
     * 装着游戏镜像的目录一律不下潜。
     *
     * 名字完全靠不住：这台机器上 `switch2-games/Fire Emblem꞉ Fortune's Weave`
     * 一个 23,077 个目录、毫无特征的文件夹，就是解包出来的游戏本体，而它旁边
     * 躺着 28.9 GB 的 `.nup`。按"这个目录里有镜像文件"来判断才稳：这种目录
     * 是放游戏的地方，存档从来不在里面。
     */
    fun holdsGameImage(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return false
        return name.substring(dot + 1).lowercase() in GAME_IMAGE_EXTENSIONS
    }

    private val GAME_IMAGE_EXTENSIONS = setOf(
        "nsp", "xci", "nup", "nca", "xcz", "wad",
        "iso", "cso", "chd", "rvz", "wbfs", "gcm", "gcz", "wia",
        "3ds", "cia", "cci", "nds", "gba", "gbc", "n64", "z64", "v64",
    )

    fun skipWholeDeviceSubtree(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in SKIPPED_WHOLE_DEVICE_NAMES) return true
        // Eden / yuzu 把 NSP 解压成 `<TITLE_ID>-work`，里面是哈希命名的内容文件。
        return lower.endsWith("-work")
    }

    private val SKIPPED_WHOLE_DEVICE_NAMES = setOf(
        // 媒体库与系统噪音
        "dcim",
        "pictures",
        "movies",
        "music",
        "podcasts",
        "ringtones",
        "alarms",
        "notifications",
        "recordings",
        "lost.dir",
        ".thumbnails",
        ".trashed",
        // 缓存
        "cache",
        "code_cache",
        ".cache",
        // 游戏资源包，存档不在里面
        "obb",
    )

    /** [relativePath] 拼到 [root] 之后仍在 [root] 之内；越界时抛异常。 */
    fun requireInside(root: String, relativePath: String): File {
        val rootPrefix = root.trimEnd('/') + "/"
        val target = File(root, GameSnapshotManifest.safeRelativePath(relativePath)).canonicalFile
        require(target.path == root || target.path.startsWith(rootPrefix)) { "存档路径超出已扫描目录" }
        return target
    }
}

/**
 * 免 root 的路径后端：「所有文件访问」（`MANAGE_EXTERNAL_STORAGE`）。
 *
 * 与 [RootStorage] 的差异只有三处，其余语义刻意保持一致，保证同一份存档在两条
 * 通道下算出同一个 [SaveSignature]、写出同样的目录结构：
 *
 *  1. 没有 `chown`。写回 `Android/data/<pkg>/` 的文件由 FUSE 归属为
 *     "本 App uid + 目标目录的组"（这些目录普遍是 0770/0660），目标 App 靠
 *     组权限照常读写，与 root 恢复等效。
 *  2. 跨文件系统不能 rename。私有 filesDir 与 /storage/emulated/0 不是同一个
 *     文件系统，所以恢复时的 staging 一律建在目标父目录内，最后一步才是
 *     rename 交换；直接对 filesDir 里的临时目录 rename 会 EXDEV。
 *  3. 读不到别的 App 的私有目录。`/data/user/0/<pkg>` 需要 root，
 *     [AllFilesPathPolicy.canonicalize] 会直接拒绝，由上层显示"需要 Root"。
 */
class AllFilesStorage(
    private val context: Context,
    /** 便于在单测里注入；默认读系统设置。 */
    private val permissionChecker: () -> Boolean = ::isExternalStorageManager,
) : PathStorage {
    private val selectionStore = SourceSelectionStore(context)

    fun persistedPath(): String? = selectionStore.rootPath()

    fun persistPath(path: String): Result<String> = selectionStore.saveRootPath(path)

    override fun workspaceDirectory(): File = File(context.filesDir, WORKSPACE_DIRECTORY)

    fun hasAllFilesAccess(): Boolean = permissionChecker()

    fun checkAllFilesAccess(): Result<Unit> = runCatching {
        require(hasAllFilesAccess()) { "未授予「所有文件访问」权限，无法读取 Android/data" }
    }

    /** 跳系统设置里本 App 的「所有文件访问」开关；API 30 以下没有这一项。 */
    fun requestIntent(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
    } else {
        null
    }

    override suspend fun pathExists(rawPath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            checkAllFilesAccess().getOrThrow()
            File(AllFilesPathPolicy.canonicalize(rawPath)).isDirectory
        }
    }

    override suspend fun scanPath(
        rawPath: String,
        androidGames: Map<String, AndroidGameMetadata>,
    ): Result<SaveScanReport> = withContext(Dispatchers.IO) {
        runCatching {
            checkAllFilesAccess().getOrThrow()
            val path = AllFilesPathPolicy.canonicalize(rawPath)
            val root = File(path)
            require(root.isDirectory) { "目录不存在或不可读：$path" }

            val directories = ArrayList<String>(1024)
            var unreadableDirectoryCount = 0
            var truncated = false
            // 共享存储整棵扫时用队列（BFS）：条目预算先铺满浅层，再往深处走，
            // 于是被截断时丢的是"最深的角落"而不是"某个大盘符的整棵树"。
            val wholeDevice = AllFilesPathPolicy.isSharedStorageRoot(path)
            val budget = if (wholeDevice) WHOLE_DEVICE_DIRECTORY_BUDGET else MAX_SCAN_DIRECTORIES
            val pending = ArrayDeque<File>()
            pending.addLast(root)
            while (pending.isNotEmpty()) {
                val directory = pending.removeFirst()
                if (directories.size >= budget) {
                    truncated = true
                    break
                }
                directories.add(directory.path)
                val children = directory.listFiles()
                if (children == null) {
                    unreadableDirectoryCount++
                    continue
                }
                val holdsGameImage = wholeDevice && children.any { !it.isDirectory && AllFilesPathPolicy.holdsGameImage(it.name) }
                for (child in children) {
                    if (!child.isDirectory || Files.isSymbolicLink(child.toPath())) continue
                    if (wholeDevice && (holdsGameImage || AllFilesPathPolicy.skipWholeDeviceSubtree(child.name))) continue
                    pending.addLast(child)
                }
            }

            SaveSourceDetector.scanDirectories(
                rootPath = path,
                directoryPaths = directories,
                selectedRootName = root.name.ifBlank { path },
                unreadableDirectoryCount = unreadableDirectoryCount,
                truncated = truncated,
                androidGames = androidGames,
            )
        }
    }

    override suspend fun dirStamps(rawPath: String, maxDepth: Int, maxEntries: Int): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                checkAllFilesAccess().getOrThrow()
                val path = AllFilesPathPolicy.canonicalize(rawPath)
                val root = File(path)
                require(root.isDirectory) { "目录不存在或不可读：$path" }
                val stamps = ArrayList<String>(256)
                val pending = ArrayDeque<Pair<File, Int>>()
                pending.addLast(root to 0)
                while (pending.isNotEmpty()) {
                    val (directory, depth) = pending.removeFirst()
                    if (depth >= maxDepth) continue
                    val children = directory.listFiles() ?: continue
                    for (child in children) {
                        if (!child.isDirectory || Files.isSymbolicLink(child.toPath())) continue
                        stamps.add("${child.path}\t${child.lastModified()}")
                        pending.addLast(child to depth + 1)
                    }
                    // 提前收工：再走也没用，指纹已经不可信
                    if (stamps.size > maxEntries) return@runCatching emptyList()
                }
                stamps.sort()
                DirStamps.limit(stamps, maxEntries)
            }
        }

    override suspend fun readSmallFile(
        directory: String,
        fileName: String,
        maxBytes: Int,
    ): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            checkAllFilesAccess().getOrThrow()
            val file = File(directory.trimEnd('/'), fileName)
            if (!file.isFile || file.length() > maxBytes) return@runCatching null
            file.readBytes()
        }.getOrNull()
    }

    override suspend fun listNames(rawPath: String, maxDepth: Int, limit: Int): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                checkAllFilesAccess().getOrThrow()
                val root = File(AllFilesPathPolicy.canonicalize(rawPath))
                if (!root.isDirectory) return@runCatching emptyList()
                val names = mutableListOf<String>()
                fun walk(directory: File, depth: Int) {
                    val children = directory.listFiles() ?: return
                    for (child in children) {
                        if (names.size >= limit) return
                        names += child.name
                        if (child.isDirectory && depth < maxDepth) walk(child, depth + 1)
                    }
                }
                walk(root, 1)
                names
            }
        }

    override suspend fun saveSignatures(
        rawRootPath: String,
        relativePaths: List<String>,
    ): Result<Map<String, SaveSignature?>> = withContext(Dispatchers.IO) {
        runCatching {
            checkAllFilesAccess().getOrThrow()
            val root = AllFilesPathPolicy.canonicalize(rawRootPath)
            relativePaths.distinct().associateWith { relative ->
                val target = AllFilesPathPolicy.requireInside(root, relative)
                if (!target.isDirectory) {
                    null
                } else {
                    var fileCount = 0
                    var totalBytes = 0L
                    var newestModifiedSeconds = 0L
                    target.walkTopDown().forEach { file ->
                        require(!Files.isSymbolicLink(file.toPath())) { "存档目录包含符号链接：${file.path}" }
                        if (file.isFile) {
                            fileCount++
                            totalBytes += file.length()
                            val seconds = Math.floorDiv(file.lastModified(), 1000L)
                            if (seconds > newestModifiedSeconds) newestModifiedSeconds = seconds
                        }
                    }
                    SaveSignature(fileCount, totalBytes, newestModifiedSeconds)
                }
            }
        }
    }

    override suspend fun copyGameLocations(
        rawRootPath: String,
        locations: List<GameSnapshotLocation>,
        destinationRoot: File,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            checkAllFilesAccess().getOrThrow()
            val root = AllFilesPathPolicy.canonicalize(rawRootPath)
            require(locations.isNotEmpty()) { "游戏没有可备份的存档位置" }
            clearWorkspace(destinationRoot)
            for (location in locations) {
                val source = AllFilesPathPolicy.requireInside(root, location.relativePath)
                require(source.isDirectory) { "存档目录不存在：${source.path}" }
                val destination = File(
                    destinationRoot,
                    PathPolicy.requireSafeDocumentName(location.archivePath),
                )
                copyTree(source, destination)
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
            checkAllFilesAccess().getOrThrow()
            val root = AllFilesPathPolicy.canonicalize(rawRootPath)
            require(locations.isNotEmpty()) { "游戏快照没有存档位置" }
            val token = UUID.randomUUID().toString()
            val extractedPrefix = extractedRoot.canonicalPath + File.separator

            data class Operation(val target: File, val prepared: File, val backup: File, val staged: File)

            val operations = locations.mapIndexed { index, location ->
                val target = AllFilesPathPolicy.requireInside(root, location.relativePath)
                // 云端下发到一台还没有这款游戏存档的设备时，目标目录由这里建出来。
                require(createMissing || target.isDirectory) { "恢复目标不存在：${target.path}" }
                val archivePath = PathPolicy.requireSafeDocumentName(location.archivePath)
                val staged = File(extractedRoot, archivePath).canonicalFile
                require(staged.path.startsWith(extractedPrefix) && staged.isDirectory) {
                    "游戏快照缺少存档位置：$archivePath"
                }
                val parent = target.parentFile ?: error("游戏存档目录没有父目录")
                Operation(
                    target = target,
                    prepared = File(parent, ".opensave-game-new-$token-$index"),
                    backup = File(parent, ".opensave-game-old-$token-$index"),
                    staged = staged,
                )
            }
            require(operations.map { it.target.path }.distinct().size == operations.size) {
                "游戏快照包含重复恢复目标"
            }
            require(
                operations.none { current ->
                    operations.any { other ->
                        other != current &&
                            other.target.path.startsWith(current.target.path.trimEnd('/') + "/")
                    }
                },
            ) { "游戏快照包含重叠恢复目标" }

            try {
                for (operation in operations) {
                    // staging 建在目标父目录内，才能保证最后的 rename 不跨文件系统。
                    deleteRecursively(operation.prepared)
                    copyTree(operation.staged, operation.prepared)
                }
                for (operation in operations) {
                    // 新建的目标目录没有旧内容，跳过暂存，恢复失败时直接把空目录删掉。
                    if (!operation.target.exists()) continue
                    if (!operation.target.renameTo(operation.backup)) {
                        error("无法暂存原存档：${operation.target.path}")
                    }
                }
                for (operation in operations) {
                    if (!operation.prepared.renameTo(operation.target)) {
                        error("无法发布新存档：${operation.target.path}")
                    }
                }
                operations.forEach { deleteRecursively(it.backup) }
            } catch (error: Throwable) {
                operations.forEach { deleteRecursively(it.prepared) }
                for (operation in operations) {
                    if (!operation.target.exists() && operation.backup.exists()) {
                        operation.backup.renameTo(operation.target)
                    }
                }
                throw error
            }
        }
    }

    override suspend fun importPath(rawPath: String): Result<CopyReport> = withContext(Dispatchers.IO) {
        runCatching {
            checkAllFilesAccess().getOrThrow()
            val path = AllFilesPathPolicy.canonicalize(rawPath)
            val source = File(path)
            require(source.isDirectory) { "目录不存在或不可读：$path" }
            val workspace = workspaceDirectory()
            clearWorkspace(workspace)
            copyTree(source, workspace)
            WorkspaceReport.build(workspace, source.name.ifBlank { path })
        }
    }

    override suspend fun restoreWorkspace(rawPath: String): Result<CopyReport> = withContext(Dispatchers.IO) {
        runCatching {
            checkAllFilesAccess().getOrThrow()
            val path = AllFilesPathPolicy.canonicalize(rawPath)
            val target = File(path)
            require(target.isDirectory) { "目录不存在或不可读：$path" }
            val workspace = workspaceDirectory()
            val report = WorkspaceReport.build(workspace)
            val parent = target.parentFile ?: error("目标目录没有父目录")
            val token = UUID.randomUUID().toString()
            val staging = File(parent, ".opensave-restore-$token")
            val previous = File(parent, ".opensave-old-$token")
            try {
                deleteRecursively(staging)
                copyTree(workspace, staging)
                if (!target.renameTo(previous)) error("无法暂存原目录：${target.path}")
                if (!staging.renameTo(target)) {
                    previous.renameTo(target)
                    error("无法发布新目录：${target.path}")
                }
                deleteRecursively(previous)
            } catch (error: Throwable) {
                deleteRecursively(staging)
                if (!target.exists() && previous.exists()) previous.renameTo(target)
                throw error
            }
            report
        }
    }

    /** 递归复制并拒绝符号链接/特殊文件：快照里不允许出现指向目录外的条目。 */
    private fun copyTree(source: File, destination: File) {
        require(!Files.isSymbolicLink(source.toPath())) { "存档路径包含符号链接：${source.path}" }
        if (source.isDirectory) {
            if (!destination.isDirectory && !destination.mkdirs()) {
                error("无法创建目录：${destination.path}")
            }
            val children = source.listFiles() ?: error("无法读取目录：${source.path}")
            for (child in children) copyTree(child, File(destination, child.name))
        } else {
            require(source.isFile) { "存档路径包含特殊文件：${source.path}" }
            destination.parentFile?.let { parent ->
                if (!parent.isDirectory && !parent.mkdirs()) error("无法创建目录：${parent.path}")
            }
            source.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
            destination.setLastModified(source.lastModified())
        }
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
            file.listFiles()?.forEach(::deleteRecursively)
        }
        file.delete()
    }

    private fun clearWorkspace(workspace: File) {
        deleteRecursively(workspace)
        require(workspace.mkdirs() || workspace.isDirectory) {
            "Could not create the private workspace"
        }
    }

    private companion object {
        const val WORKSPACE_DIRECTORY = "source-workspace"
        const val MAX_SCAN_DIRECTORIES = 50_000

        /**
         * 「全盘补漏」的目录预算。剩下的来源都是几十个目录，只有这一个会走满整个
         * 共享存储；剪枝之后正常设备在几千以内，预算只是防止极端布局把一核钉死。
         */
        const val WHOLE_DEVICE_DIRECTORY_BUDGET = 20_000
    }
}

/** 默认权限来源：API 30 起读系统设置，30 以下旧存储模型本来就放行。 */
internal fun isExternalStorageManager(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
