package com.jun9100.opensave

/**
 * Presentation metadata for a detected save location. Detection remains
 * filesystem-only; this catalog is deliberately offline and safe to extend.
 */
enum class GameIconKey {
    SWITCH,
    PC,
    ANDROID,
    PPSSPP,
    RETROARCH,
    DOLPHIN,
    EMULATOR,
    UNKNOWN,
}

data class GamePresentation(
    val localizedName: String?,
    val originalName: String?,
    val identity: String?,
    val iconKey: GameIconKey,
    val androidPackageName: String? = null,
    val steamAppId: String? = null,
) {
    val primaryName: String
        get() = localizedName ?: originalName ?: "未命名游戏"

    val hasLocalizedName: Boolean
        get() = !localizedName.isNullOrBlank()

    val coverUrl: String?
        get() = steamAppId?.let { "https://cdn.cloudflare.steamstatic.com/steam/apps/$it/header.jpg" }

    val coverCacheKey: String?
        get() = steamAppId?.let { "steam-$it" }
}

object GameCatalog {
    private data class Entry(
        val localizedName: String,
        val originalName: String,
        val steamAppId: String? = null,
    )

    private val nameEntries = mapOf(
        normalized("Wandering Sword") to Entry("逸剑风云决", "Wandering Sword", "1876890"),
        normalized("Vampire Crawlers") to Entry("吸血鬼爬虫", "Vampire Crawlers", "3265700"),
        normalized("The Life and Suffering of Sir Brante") to Entry(
            "布兰特爵士的生平和苦难",
            "The Life and Suffering of Sir Brante",
            "1272160",
        ),
        normalized("Vampire Survivors") to Entry("吸血鬼幸存者", "Vampire Survivors", "1794680"),
        normalized("Raging Loop") to Entry("人狼村之谜", "Raging Loop"),
        normalized("ELDEN RING") to Entry("艾尔登法环", "ELDEN RING", "1245620"),
        normalized("Elden Ring") to Entry("艾尔登法环", "ELDEN RING", "1245620"),
        normalized("Hades") to Entry("哈迪斯", "Hades", "1145360"),
        normalized("Stardew Valley") to Entry("星露谷物语", "Stardew Valley", "413150"),
        normalized("Hollow Knight") to Entry("空洞骑士", "Hollow Knight", "367520"),
        normalized("Dead Cells") to Entry("死亡细胞", "Dead Cells", "588650"),
        normalized("Baldur's Gate 3") to Entry("博德之门3", "Baldur's Gate 3", "1086940"),
        normalized("Persona 5 Royal") to Entry("女神异闻录5 皇家版", "Persona 5 Royal", "1687950"),
    )

    private val steamEntries = mapOf(
        "1876890" to Entry("逸剑风云决", "Wandering Sword", "1876890"),
        "3265700" to Entry("吸血鬼爬虫", "Vampire Crawlers", "3265700"),
        "1245620" to Entry("艾尔登法环", "ELDEN RING", "1245620"),
        "1272160" to Entry(
            "布兰特爵士的生平和苦难",
            "The Life and Suffering of Sir Brante",
            "1272160",
        ),
        "1794680" to Entry("吸血鬼幸存者", "Vampire Survivors", "1794680"),
    )

    private val androidEntries = mapOf(
        "dev.eden.eden_emulator" to Entry("Eden 模拟器", "Eden"),
        "org.yuzu.yuzu_emu" to Entry("Yuzu 模拟器", "Yuzu"),
        "org.yuzu.yuzu_emu.ea" to Entry("Yuzu EA 模拟器", "Yuzu EA"),
        "com.xiaoji.egggame" to Entry("GameHub", "GameHub"),
        "org.ppsspp.ppsspp" to Entry("PPSSPP 模拟器", "PPSSPP"),
        "com.retroarch" to Entry("RetroArch 模拟器", "RetroArch"),
        "org.dolphinemu.dolphinemu" to Entry("Dolphin 模拟器", "Dolphin"),
    )

    fun present(save: DetectedSave): GamePresentation {
        val rawName = save.displayName?.trim()?.takeIf { it.isNotEmpty() }
        val packageName = save.takeIf { it.kind == "android-app" }
            ?.let { rawName }
        val appId = extractAppId(save)
        val entry = packageName?.let { androidEntries[it] }
            ?: appId?.let { steamEntries[it] }
            ?: save.titleId?.let { titleIdEntries[it.uppercase()] }
            ?: rawName?.let { nameEntries[normalized(it)] }
        val steamAppId = appId ?: entry?.steamAppId

        val originalName = entry?.originalName ?: humanReadableName(save, rawName)
        return GamePresentation(
            localizedName = entry?.localizedName,
            originalName = originalName,
            identity = save.titleId ?: steamAppId?.let { "Steam AppID $it" },
            iconKey = iconKey(save.kind),
            androidPackageName = packageName,
            steamAppId = steamAppId,
        )
    }

    private fun extractAppId(save: DetectedSave): String? {
        val fromKey = save.gameKey
            ?.takeIf { it.startsWith("steam:") }
            ?.removePrefix("steam:")
            ?.takeIf { it.all(Char::isDigit) }
        if (fromKey != null) return fromKey
        return Regex("(?:AppID\\s+|steam:)(\\d+)", RegexOption.IGNORE_CASE)
            .find(save.displayName.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun humanReadableName(save: DetectedSave, rawName: String?): String? {
        if (save.kind == "android-app") return rawName
        if (rawName.isNullOrBlank()) return null
        if (rawName.startsWith("Switch ", ignoreCase = true)) return null
        if (rawName.startsWith("Steam AppID ", ignoreCase = true)) return null
        if (rawName == "未命名 PC 存档") return null
        return rawName.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun iconKey(kind: String): GameIconKey = when (kind) {
        "yuzu-switch", "ryujinx-switch", "switch-title" -> GameIconKey.SWITCH
        "gamehub-pc" -> GameIconKey.PC
        "android-app" -> GameIconKey.ANDROID
        "ppsspp" -> GameIconKey.PPSSPP
        "retroarch" -> GameIconKey.RETROARCH
        "dolphin" -> GameIconKey.DOLPHIN
        "emulator" -> GameIconKey.EMULATOR
        else -> GameIconKey.UNKNOWN
    }

    private fun normalized(value: String): String = value
        .replace('_', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase()

    /** TitleID entries can be expanded without touching filesystem detection. */
    private val titleIdEntries: Map<String, Entry> = emptyMap()
}
