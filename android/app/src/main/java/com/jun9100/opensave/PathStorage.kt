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

    /**
     * [rawPath] 之下最多 [maxDepth] 层里的文件名与目录名（不含路径），最多 [limit] 条。
     *
     * 只用来判断"模拟器认不认这个游戏"——云端下发前要拿文件名核对标题 id，见
     * [InstallEvidence]。读不到就返回空列表：没有证据时下发会跳过，而不是猜。
     */
    suspend fun listNames(rawPath: String, maxDepth: Int, limit: Int): Result<List<String>>

    /**
     * [rawPath] 之下前 [maxDepth] 层里每个目录的相对路径与最后修改时间（毫秒），
     * 按路径排序。用来判断"这个来源这一轮还用不用重新扫"，见 [ScanSnapshot]。
     *
     * 读不到就走空：空指纹永不与缓存的指纹相等，于是退化成"每次都扫"。
     */
    suspend fun dirStamps(rawPath: String, maxDepth: Int): Result<List<String>>

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

    /**
     * [createMissing] 允许目标存档目录原本不存在——云端下发到一台从未存过档的设备时，
     * `nand/user/save/<uid>/<TITLE_ID>` 和 `PSP/SAVEDATA/<GAME_ID>` 都还没有被模拟器建出来。
     */
    suspend fun restoreGameLocations(
        rawRootPath: String,
        locations: List<GameSnapshotLocation>,
        extractedRoot: File,
        createMissing: Boolean = false,
    ): Result<Unit>

    suspend fun importPath(rawPath: String): Result<CopyReport>

    suspend fun restoreWorkspace(rawPath: String): Result<CopyReport>
}
