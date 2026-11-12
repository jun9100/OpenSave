package com.jun9100.opensave

import java.io.File

/**
 * 路径型来源的存取后端。
 *
 * SaveVault 里有两种"来源"：SAF 授权的目录（[SafStorage]，走 content:// URI）和
 * 绝对路径的目录（[SourceSelection.Root]）。绝对路径这一侧原先只有一个实现
 * [RootStorage]——它靠 `su` 才能读到 `/storage/emulated/0/Android/data/<pkg>`。
 *
 * Android 11 起，没有 root 的设备还有第二条合法通道：「所有文件访问」
 * （`MANAGE_EXTERNAL_STORAGE`）。持有时 FUSE 不再对 `Android/data` 做
 * scoped-storage 过滤，普通 `java.io.File` 即可读写——MT 管理器、MiXplorer
 * 这些文件管理器就是这么工作的。这条路补上后，免 root 设备不必再为了
 * 读模拟器存档去刷 root。
 *
 * 调用方（扫描、备份、恢复、导入导出）只依赖本接口，由 [PathAccess] 在运行时
 * 选择实际执行者；两者的方法语义必须一致，否则同一份快照在两条通道下的
 * 校验值会不一致。
 */
interface PathStorage {
    /** 私有工作区；两条通道都使用 App 自己的 filesDir。 */
    fun workspaceDirectory(): File

    suspend fun pathExists(rawPath: String): Result<Boolean>

    suspend fun scanPath(
        rawPath: String,
        androidGames: Map<String, AndroidGameMetadata> = emptyMap(),
    ): Result<SaveScanReport>

    /** [directory] 下 [fileName] 的字节；缺失或超过 [maxBytes] 时为 null。 */
    suspend fun readSmallFile(directory: String, fileName: String, maxBytes: Int): ByteArray?

    /** 每个相对路径的 [SaveSignature]；位置不存在时为 null。 */
    suspend fun saveSignatures(
        rawRootPath: String,
        relativePaths: List<String>,
    ): Result<Map<String, SaveSignature?>>

    suspend fun copyGameLocations(
        rawRootPath: String,
        locations: List<GameSnapshotLocation>,
        destinationRoot: File,
    ): Result<Unit>

    suspend fun restoreGameLocations(
        rawRootPath: String,
        locations: List<GameSnapshotLocation>,
        extractedRoot: File,
    ): Result<Unit>

    suspend fun importPath(rawPath: String): Result<CopyReport>

    suspend fun restoreWorkspace(rawPath: String): Result<CopyReport>
}
