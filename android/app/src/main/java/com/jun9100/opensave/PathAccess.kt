package com.jun9100.opensave

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 路径型来源的统一入口：按设备实际能力选 [RootStorage] 或 [AllFilesStorage]。
 *
 * 优先级是 root 高于「所有文件访问」——有 root 时 `/data/user/0/<pkg>` 里的
 * 私有目录也能读，这是「所有文件访问」做不到的（它只覆盖
 * `/storage/emulated/0`，见 [AllFilesPathPolicy]）。
 *
 * su 的探测结果会被缓存：`su` 的每次调用都要起一个进程，扫描一个来源会走
 * 十几次路径操作，逐次探测会让"来源"页明显变慢。缓存由 [checkRootAccess] 填充，
 * 它同时也是设置页「重新检测 Root」按钮走的那条路，所以用户手动重试仍然有效。
 */
class PathAccess(context: Context) : PathStorage {
    private val appContext = context.applicationContext
    private val root = RootStorage(appContext)
    private val allFiles = AllFilesStorage(appContext)

    @Volatile
    private var rootGranted: Boolean? = null

    override fun workspaceDirectory(): File = root.workspaceDirectory()

    fun persistedRootPath(): String? = root.persistedRootPath()

    fun persistRootPath(path: String): Result<String> = root.persistRootPath(path)

    fun hasAllFilesAccess(): Boolean = allFiles.hasAllFilesAccess()

    fun checkAllFilesAccess(): Result<Unit> = allFiles.checkAllFilesAccess()

    fun requestAllFilesAccessIntent(): Intent? = allFiles.requestIntent()

    /** 探测并缓存 su 是否可用；同时是"重新检测 Root"的入口。 */
    suspend fun checkRootAccess(): Result<Unit> = withContext(Dispatchers.IO) {
        val result = root.checkRootAccess()
        rootGranted = result.isSuccess
        result
    }

    /** 上一次探测的结果；没探测过则为 null。用于 UI 文案，不触发 `su`。 */
    fun rootAccessState(): Boolean? = rootGranted

    /** 当前能否读该路径型来源。 */
    fun canReadPaths(): Boolean = rootGranted == true || allFiles.hasAllFilesAccess()

    /**
     * 本次操作由谁执行。两者都不可用时抛出带两条建议的错误，
     * 让"来源"页显示的是原因而不是一个空列表。
     */
    private suspend fun backend(): PathStorage {
        val granted = rootGranted ?: withContext(Dispatchers.IO) {
            root.checkRootAccess().isSuccess.also { rootGranted = it }
        }
        return when {
            granted -> root
            allFiles.hasAllFilesAccess() -> allFiles
            else -> throw IllegalStateException(
                "需要 Root 或「所有文件访问」权限才能读取该目录；" +
                    "可在系统设置中为 SaveVault 开启「所有文件访问」后重试。",
            )
        }
    }

    override suspend fun pathExists(rawPath: String): Result<Boolean> =
        runCatching { backend().pathExists(rawPath).getOrThrow() }

    override suspend fun scanPath(
        rawPath: String,
        androidGames: Map<String, AndroidGameMetadata>,
    ): Result<SaveScanReport> = runCatching { backend().scanPath(rawPath, androidGames).getOrThrow() }

    override suspend fun dirStamps(rawPath: String, maxDepth: Int): Result<List<String>> =
        backend().dirStamps(rawPath, maxDepth)

    override suspend fun readSmallFile(
        directory: String,
        fileName: String,
        maxBytes: Int,
    ): ByteArray? = runCatching { backend().readSmallFile(directory, fileName, maxBytes) }.getOrNull()

    override suspend fun listNames(rawPath: String, maxDepth: Int, limit: Int): Result<List<String>> =
        runCatching { backend().listNames(rawPath, maxDepth, limit).getOrThrow() }

    override suspend fun saveSignatures(
        rawRootPath: String,
        relativePaths: List<String>,
    ): Result<Map<String, SaveSignature?>> =
        runCatching { backend().saveSignatures(rawRootPath, relativePaths).getOrThrow() }

    override suspend fun copyGameLocations(
        rawRootPath: String,
        locations: List<GameSnapshotLocation>,
        destinationRoot: File,
    ): Result<Unit> = runCatching {
        backend().copyGameLocations(rawRootPath, locations, destinationRoot).getOrThrow()
    }

    override suspend fun restoreGameLocations(
        rawRootPath: String,
        locations: List<GameSnapshotLocation>,
        extractedRoot: File,
        createMissing: Boolean,
    ): Result<Unit> = runCatching {
        backend().restoreGameLocations(rawRootPath, locations, extractedRoot, createMissing).getOrThrow()
    }

    override suspend fun importPath(rawPath: String): Result<CopyReport> =
        runCatching { backend().importPath(rawPath).getOrThrow() }

    override suspend fun restoreWorkspace(rawPath: String): Result<CopyReport> =
        runCatching { backend().restoreWorkspace(rawPath).getOrThrow() }
}
