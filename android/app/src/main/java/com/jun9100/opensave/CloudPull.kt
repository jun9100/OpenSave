package com.jun9100.opensave

import java.util.Locale

/**
 * 云端优先下发（cloud pull）。
 *
 * 备份的锚点是"本机已有存档"：扫描器只在 `<root>/nand/user/save/<uid>/<TITLE_ID>/`
 * 或 `<root>/PSP/SAVEDATA/<GAME_ID>/` 真的存在时才产出一个游戏条目。新设备、
 * 或者刚加进模拟器但还没玩过的游戏，本机一个字节都没有，于是云端有新版本也
 * 没有入口把它拉下来。
 *
 * 这里换一个锚点：云端有哪些游戏（`switch-<TITLE_ID>`、`ppsspp-<GAME_ID>` 这类
 * 跨设备稳定的 id），本机又有哪些模拟器存档根，两者一乘就得到"该补哪些目录"。
 * 目标目录不存在不是问题——[GameOperations.restoreCloudPullLocked] 会建。
 *
 * 只做纯计算，方便 JVM 单测：文件是否存在由调用方注入。
 */
internal enum class SaveRootFamily(val kind: String) {
    /** Yuzu / Eden / Suyu / Sudachi / Citron：`nand/user/save/<uid>/<TITLE_ID>`。 */
    YUZU_SWITCH("yuzu-switch"),

    /** Ryujinx / Ryubing：`bis/user/save/<uid>/<TITLE_ID>`。 */
    RYUJINX_SWITCH("ryujinx-switch"),

    /** PPSSPP：`PSP/SAVEDATA/<GAME_ID>`。 */
    PPSSPP("ppsspp"),
}

/** 一个可写的模拟器存档根；[path] 是绝对路径，[relativePath] 相对它计算。 */
internal data class LocalSaveRoot(
    val label: String,
    val path: String,
    val family: SaveRootFamily,
)

/**
 * 云端的一款游戏。
 *
 * 云端会把同一款游戏在**不同设备、不同作用域**里的存档合成一个 game（members），
 * 游戏键可能长成 `switch/title:0100F2C0115B6000`、`emulator-ppsspp/psp:ULUS10041`
 * 或没有身份信息时的 `pc/scope:<hash>`。真正的存档作用域要从某个 member 上取，
 * 所以这里把"游戏身份"和"要下载的作用域"分开存。
 */
internal data class CloudPullGame(
    /** 要下载的那个作用域的 SaveVault 类型。 */
    val scopeType: GameSaveType,
    /** 要下载的那个作用域的 gameId。 */
    val scopeGameId: String,
    /** 云端游戏键的游戏部分，用来解析 title/psp id，例如 `title:0100F2C0115B6000`。 */
    val identityId: String,
    val displayName: String,
)

/** 一条待下发的存档位置。 */
internal data class CloudPullCandidate(
    val root: LocalSaveRoot,
    val game: CloudPullGame,
    /** 相对 [LocalSaveRoot.path]，例如 `nand/user/save/0000000000000000/0100F2C0115B6000`。 */
    val relativePath: String,
    /** 位置种类，与 [DetectedSave.kind] 取值一致。 */
    val kind: String,
    /** 与 [DetectedGame.gameKey] 一致，恢复后本机扫描会归到同一个游戏。 */
    val gameKey: String,
)

/** 云端下发在界面上显示的状态。 */
sealed interface CloudPullStatus {
    data object Idle : CloudPullStatus
    data class Running(val done: Int, val total: Int, val label: String) : CloudPullStatus
    data class Finished(
        val restored: Int,
        val failed: Int,
        val message: String,
        /** 云端有存档、但本机拿不出"这个游戏装过"的证据，于是没下发的数量。 */
        val skipped: Int = 0,
    ) : CloudPullStatus
}

/**
 * 一次下发计划：真正要拉的位置，以及因为"本机没有这个游戏"被挡下的游戏名。
 *
 * 被挡下的不算失败，也不该悄悄消失——界面要把数量说清楚，否则用户只会看到
 * "云端明明有，为什么没补下来"。
 */
internal data class CloudPullPlan(
    val candidates: List<CloudPullCandidate> = emptyList(),
    val skippedNotInstalled: List<String> = emptyList(),
)

internal object CloudPull {
    /** Switch 存档副本在 `nand/user/save` 下的固定用户目录（Yuzu 系的 0000000000000000）。 */
    private const val SWITCH_SAVE_USER = "0000000000000000"

    private val titleIdPattern = Regex("^[0-9A-Fa-f]{16}$")
    private val pspIdPattern = Regex("^[A-Za-z]{4}[0-9]{5}$")

    /** `title:0100…`（云端按身份分组后）、`switch:0100…`、`switch-0100…`。 */
    private val switchIdPrefixes = listOf("title:", "switch:", "switch-")

    /** `psp:ULUS10041`（云端按身份分组后）、`ppsspp-`、`psp-`、`emulator-ppsspp-`。 */
    private val pspIdPrefixes = listOf("psp:", "emulator-ppsspp-", "ppsspp-", "psp-")

    /** 作用域 id 的公共前缀；`scope:switch-0100…` 去掉后按普通 id 解析。 */
    private const val SCOPE_PREFIX = "scope:"

    fun titleIdOf(gameId: String): String? {
        val value = stripScope(gameId)
        val prefix = switchIdPrefixes.firstOrNull { value.startsWith(it, ignoreCase = true) } ?: return null
        val tail = value.substring(prefix.length)
        if (!titleIdPattern.matches(tail)) return null
        return tail.uppercase(Locale.ROOT)
    }

    fun pspIdOf(gameId: String): String? {
        val value = stripScope(gameId)
        val prefix = pspIdPrefixes.firstOrNull { value.startsWith(it, ignoreCase = true) } ?: return null
        val tail = value.substring(prefix.length)
        if (!pspIdPattern.matches(tail)) return null
        return tail.uppercase(Locale.ROOT)
    }

    /** 该家族里 [id] 对应的存档相对路径；家族与 id 类型不匹配时为 null。 */
    fun stripScope(gameId: String): String {
        val value = gameId.trim()
        return if (value.startsWith(SCOPE_PREFIX, ignoreCase = true)) value.substring(SCOPE_PREFIX.length) else value
    }

    fun relativePath(family: SaveRootFamily, id: String): String? {
        val upper = id.uppercase(Locale.ROOT)
        return when (family) {
            SaveRootFamily.YUZU_SWITCH ->
                if (titleIdPattern.matches(upper)) "nand/user/save/$SWITCH_SAVE_USER/$upper" else null

            SaveRootFamily.RYUJINX_SWITCH ->
                if (titleIdPattern.matches(upper)) "bis/user/save/$SWITCH_SAVE_USER/$upper" else null

            SaveRootFamily.PPSSPP ->
                if (pspIdPattern.matches(upper)) "SAVEDATA/$upper" else null
        }
    }

    /**
     * 云端游戏与本机存档根的交叉结果。
     *
     * [exists] 判断"这个根下这个相对路径已经有存档了吗"，已有就跳过——已有存档的
     * 游戏走正常的云端比较/恢复流程，不该被下发覆盖。
     *
     * [proved] 判断"本机能证明这个游戏确实装过吗"，见 [InstallEvidence]。没有证据
     * 的一律不下发：往模拟器的存档根里凭空建一个目录，等于替它造出一个游戏。
     */
    fun plan(
        roots: List<LocalSaveRoot>,
        games: List<CloudPullGame>,
        exists: (String, String) -> Boolean,
        proved: (SaveRootFamily, String) -> Boolean,
    ): CloudPullPlan {
        val skipped = mutableListOf<String>()
        val accepted = mutableListOf<CloudPullCandidate>()
        val seen = mutableSetOf<String>()
        for (game in games) {
            val id = titleIdOf(game.identityId) ?: pspIdOf(game.identityId) ?: continue
            for (root in roots) {
                val relative = relativePath(root.family, id) ?: continue
                val key = "${root.path}\u0000$relative"
                if (!seen.add(key)) continue
                if (exists(root.path, relative)) continue
                if (!proved(root.family, id)) {
                    if (skipped.none { it == game.displayName }) skipped += game.displayName
                    continue
                }
                accepted += CloudPullCandidate(
                    root = root,
                    game = game,
                    relativePath = relative,
                    kind = root.family.kind,
                    gameKey = gameKeyOf(root.family, id),
                )
            }
        }
        return CloudPullPlan(
            candidates = accepted.sortedWith(
                compareBy<CloudPullCandidate> { it.game.displayName.lowercase(Locale.ROOT) }
                    .thenBy { it.root.path }
                    .thenBy { it.relativePath },
            ),
            skippedNotInstalled = skipped.sorted(),
        )
    }

    /** 与 [plan] 相同但不做安装判定；保留给只关心路径拼接的调用方和单测。 */
    fun candidates(
        roots: List<LocalSaveRoot>,
        games: List<CloudPullGame>,
        exists: (String, String) -> Boolean,
    ): List<CloudPullCandidate> = plan(roots, games, exists) { _, _ -> true }.candidates

    /** 与扫描器产出的 `DetectedSave.gameKey` 对齐，恢复后归到同一款游戏。 */
    fun gameKeyOf(family: SaveRootFamily, id: String): String {
        val upper = id.uppercase(Locale.ROOT)
        return when (family) {
            SaveRootFamily.YUZU_SWITCH, SaveRootFamily.RYUJINX_SWITCH -> "switch:$upper"
            SaveRootFamily.PPSSPP -> "path:ppsspp::PPSSPP:SAVEDATA/$upper"
        }
    }

    /**
     * 从一个绝对路径推断它属于哪个模拟器家族。扫描器给的是 [DetectedSave.kind]，
     * 这里要的是"这个根该怎么拼出存档路径"，两者一一对应。
     */
    fun familyOf(path: String): SaveRootFamily? {
        val lower = path.lowercase(Locale.ROOT).trimEnd('/')
        return when {
            lower.contains("ryujinx") || lower.contains("ryubing") -> SaveRootFamily.RYUJINX_SWITCH
            lower.contains("eden") || lower.contains("yuzu") ||
                lower.contains("suyu") || lower.contains("sudachi") || lower.contains("citron") ->
                SaveRootFamily.YUZU_SWITCH
            lower == "/storage/emulated/0/psp" || lower.endsWith("/psp/savedata") -> SaveRootFamily.PPSSPP
            else -> null
        }
    }
}
