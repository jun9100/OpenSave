package com.jun9100.opensave

import java.util.Locale

/**
 * 「本机到底装没装这个游戏」的证据。
 *
 * 云端优先下发原先只判断"云端有、本机这个存档目录没有"就往模拟器的存档根里写字。
 * 于是云端存着、本机根本没装的游戏也会被建出 `nand/user/save/…/<TITLE_ID>`——
 * 模拟器按目录扫描存档，凭空多出来的目录在它眼里就是一个装好的游戏。
 *
 * 能证明"装过"的只有模拟器自己认得的东西：
 *
 *  1. Eden / Yuzu 的 `config/config.ini` 里 `Paths\gamedirs` 指向的游戏目录，
 *     文件名（或目录名）通常带着 `<TITLE_ID>`，例如
 *     `13 Sentinels Aegis Rim [01008D7016438000] [v1.00].xci`；
 *  2. Eden 的 `log/eden_log.txt`：跑过的游戏一定会把标题 id 写进日志（日志会轮转，
 *     所以 `.old.txt` 也算）；文件名不带 id 的镜像（`SUPER MARIO ODYSSEY [1.3.0].xci`）
 *     靠这一条兜住；
 *  3. PPSSPP 的游戏镜像（iso / cso / chd / pbp …）文件名带着 `<GAME_ID>`，
 *     以及 `SYSTEM/ppsspp.ini` 里的最近游玩列表。
 *
 * 这里只做纯字符串处理，文件读取由调用方注入，方便 JVM 单测。
 */
internal data class InstallEvidence(
    /** Eden / Yuzu 游戏目录下的文件名与目录名。 */
    val switchNames: List<String> = emptyList(),
    /** Eden 日志正文（`eden_log.txt` + 轮转的那份）。 */
    val switchText: String = "",
    /** PPSSPP 根目录下的文件名与目录名。 */
    val pspNames: List<String> = emptyList(),
    /** PPSSPP 的 `SYSTEM/ppsspp.ini`，Recent 列表里有镜像全路径。 */
    val pspText: String = "",
) {
    fun provesSwitch(titleId: String): Boolean =
        InstalledGames.namesContain(switchNames, titleId) || InstalledGames.textContains(switchText, titleId)

    fun provesPsp(gameId: String): Boolean =
        InstalledGames.namesContain(pspNames, gameId) || InstalledGames.textContains(pspText, gameId)

    val isEmpty: Boolean
        get() = switchNames.isEmpty() && switchText.isBlank() && pspNames.isEmpty() && pspText.isBlank()

    companion object {
        val EMPTY = InstallEvidence()
    }
}

/** 从模拟器自己的配置里问"游戏装在哪"，再按文件名核对标题 id。 */
internal object InstalledGames {
    /** 一份配置最多认这么多游戏目录，避免手改坏的 ini 撑爆扫描。 */
    private const val MAX_GAME_DIRS = 8

    /** 例如 `Paths\gamedirs\1\path="content://…tree/primary%3Aswitch1-games"`。 */
    private val edenGameDirLine =
        Regex("""^Paths\\gamedirs\\\d+\\path\s*=\s*(.+)$""", RegexOption.IGNORE_CASE)

    /** Sentinels 这类 id 是 16 位十六进制。 */
    private val titleIdPattern = Regex("^[0-9A-Fa-f]{16}$")

    /** PSP 的游戏 id 是 `ULUS10041` 这种形状。 */
    private val pspIdPattern = Regex("^[A-Za-z]{4}[0-9]{5}$")

    fun looksLikeTitleId(value: String): Boolean = titleIdPattern.matches(value)

    fun looksLikePspId(value: String): Boolean = pspIdPattern.matches(value)

    /**
     * Eden / Yuzu `config.ini` 里配置的游戏目录，已从 SAF tree URI 解码成绝对路径。
     *
     * 顺序与 ini 里的编号一致；同一路径重复出现只留一份。
     */
    fun gameDirsFromEdenConfig(text: String): List<String> = buildList {
        for (line in text.lineSequence()) {
            val match = edenGameDirLine.find(line.trim()) ?: continue
            val value = match.groupValues[1].trim().trim('"').trim()
            val path = pathOfTreeUri(value) ?: value.takeIf { it.startsWith("/") } ?: continue
            if (path.isNotBlank() && none { it == path }) add(path)
            if (size >= MAX_GAME_DIRS) break
        }
    }

    /**
     * Ryujinx 的 `Config.json` 里 `GameDirs` 的路径。字段名在不同版本之间换过大小写，
     * 这里只在切片里挑出绝对路径，解析不出来的部分直接忽略。
     */
    fun gameDirsFromRyujinxConfig(text: String): List<String> {
        val value = text.trim()
        if (value.isEmpty() || '[' !in value) return emptyList()
        val start = value.indexOf("[", value.indexOf("GameDirs", ignoreCase = true).takeIf { it >= 0 } ?: return emptyList())
        if (start < 0) return emptyList()
        var depth = 0
        var end = -1
        for (index in start until value.length) {
            when (value[index]) {
                '[' -> depth += 1
                ']' -> {
                    depth -= 1
                    if (depth == 0) {
                        end = index
                        break
                    }
                }
            }
        }
        if (end < 0) return emptyList()
        return Regex("\"([^\"]+)\"").findAll(value.substring(start, end))
            .map { it.groupValues[1].trim() }
            .filter { it.startsWith("/") }
            .distinct()
            .take(MAX_GAME_DIRS)
            .toList()
    }

    /**
     * SAF 的 `content://…/tree/primary%3Aswitch1-games` → `/storage/emulated/0/switch1-games`。
     *
     * 不认识的形态（`raw:`、相对路径、非 tree URI）返回 null，由调用方决定怎么退让。
     */
    fun pathOfTreeUri(value: String): String? {
        val trimmed = value.trim().trim('"')
        val treeIndex = trimmed.indexOf("/tree/")
        if (treeIndex < 0) return null
        val documentIndex = trimmed.indexOf("/document/", treeIndex)
        val encoded = if (documentIndex >= 0) {
            trimmed.substring(treeIndex + "/tree/".length, documentIndex)
        } else {
            trimmed.substring(treeIndex + "/tree/".length)
        }
        val decoded = decodePercent(encoded)
        val colon = decoded.indexOf(':')
        if (colon <= 0) return null
        val volume = decoded.substring(0, colon)
        val relative = decoded.substring(colon + 1).trim('/')
        val base = when (volume.lowercase(Locale.ROOT)) {
            "primary", "home" -> AllFilesPathPolicy.PRIMARY_ROOT
            else -> "/storage/$volume"
        }
        return if (relative.isEmpty()) base else "$base/$relative"
    }

    /**
     * 名字列表里有没有该 id（大小写无关）。
     *
     * 16 位十六进制或 `ULUS10041` 这种 9 位游戏 id 不会偶然出现在无关文件名里，
     * 所以用 `contains` 就够了；短 id 直接判否，避免 `contains("")` 全部命中。
     */
    fun namesContain(names: Iterable<String>, id: String): Boolean {
        val needle = id.trim()
        if (needle.length < 8) return false
        return names.any { it.contains(needle, ignoreCase = true) }
    }

    /** 日志 / ini 正文里有没有该 id。 */
    fun textContains(text: String, id: String): Boolean {
        val needle = id.trim()
        if (needle.length < 8 || text.isEmpty()) return false
        return text.contains(needle, ignoreCase = true)
    }

    /**
     * `%3A` 之类的百分号转义。不用 [URLDecoder] 是因为它把 `+` 当成空格，
     * 而游戏目录名里的 `+` 是字面量。
     */
    private fun decodePercent(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var index = 0
        while (index < bytes.size) {
            val byte = bytes[index]
            if (byte == '%'.code.toByte() && index + 2 < bytes.size) {
                val hex = value.substring(index + 1, index + 3)
                val parsed = hex.toIntOrNull(16)
                if (parsed != null) {
                    out.write(parsed)
                    index += 3
                    continue
                }
            }
            out.write(byte.toInt())
            index += 1
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
