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
    DUCKSTATION,
    AETHERSX2,
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
    val switchTitleId: String? = null,
) {
    val primaryName: String
        get() = localizedName ?: originalName ?: "未命名游戏"

    val hasLocalizedName: Boolean
        get() = !localizedName.isNullOrBlank()

    val coverUrl: String?
        get() = steamAppId?.let { "https://cdn.cloudflare.steamstatic.com/steam/apps/$it/header.jpg" }
            ?: switchTitleId?.let { "https://tinfoil.media/ti/$it/0/0/" }

    val coverCacheKey: String?
        get() = steamAppId?.let { "steam-$it" }
            ?: switchTitleId?.let { "switch-${it.lowercase()}" }
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
        "com.poncle.vampiresurvivors" to Entry("吸血鬼幸存者", "Vampire Survivors"),
        "com.ducvd89.bloodborne" to Entry("血源诅咒", "Bloodborne"),
        "org.ppsspp.ppsspp" to Entry("PPSSPP 模拟器", "PPSSPP"),
        "com.retroarch" to Entry("RetroArch 模拟器", "RetroArch"),
        "org.dolphinemu.dolphinemu" to Entry("Dolphin 模拟器", "Dolphin"),
    )

    fun present(save: DetectedSave): GamePresentation {
        val rawName = save.displayName?.trim()?.takeIf { it.isNotEmpty() }
        val packageName = save.takeIf { it.kind == "android-app" || it.kind == "android-game" }
            ?.gameKey
            ?.removePrefix("android:")
            ?.takeIf { it.contains('.') }
            ?: rawName?.takeIf { it.contains('.') }
        val appId = extractAppId(save)
        val titleId = save.titleId?.uppercase()
        val entry = packageName?.let { androidEntries[it] }
            ?: appId?.let { steamEntries[it] }
            ?: titleId?.let { titleIdEntries[it] }
            ?: rawName?.let { nameEntries[normalized(it)] }
        val steamAppId = appId ?: entry?.steamAppId

        val originalName = entry?.originalName ?: humanReadableName(save, rawName)
        return GamePresentation(
            localizedName = entry?.localizedName,
            originalName = originalName,
            identity = titleId ?: steamAppId?.let { "Steam AppID $it" },
            iconKey = iconKey(save.kind),
            androidPackageName = packageName,
            steamAppId = steamAppId,
            switchTitleId = titleId?.takeIf { switchTitleIdPattern.matches(it) },
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
        if (save.kind == "android-app" || save.kind == "android-game") return rawName
        if (rawName.isNullOrBlank()) return null
        if (rawName.startsWith("Switch ", ignoreCase = true)) return null
        if (rawName.startsWith("Steam AppID ", ignoreCase = true)) return null
        if (rawName == "未命名 PC 存档") return null
        return rawName.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun iconKey(kind: String): GameIconKey = when (kind) {
        "yuzu-switch", "ryujinx-switch", "switch-title" -> GameIconKey.SWITCH
        "gamehub-pc" -> GameIconKey.PC
        "android-app", "android-game" -> GameIconKey.ANDROID
        "ppsspp" -> GameIconKey.PPSSPP
        "retroarch" -> GameIconKey.RETROARCH
        "dolphin" -> GameIconKey.DOLPHIN
        "duckstation" -> GameIconKey.DUCKSTATION
        "aethersx2" -> GameIconKey.AETHERSX2
        "emulator" -> GameIconKey.EMULATOR
        else -> GameIconKey.UNKNOWN
    }

    private fun normalized(value: String): String = value
        .replace('_', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase()

    /**
     * Frequently used Switch TitleIDs. Unknown titles still show their TitleID
     * and a Switch badge; adding names here never changes filesystem detection.
     */
    private val titleIdEntries = mapOf(
        "0100000000010000" to Entry("超级马力欧 奥德赛", "Super Mario Odyssey"),
        "01007EF00011E000" to Entry("塞尔达传说：旷野之息", "The Legend of Zelda: Breath of the Wild"),
        "0100F2C0115B6000" to Entry("塞尔达传说：王国之泪", "The Legend of Zelda: Tears of the Kingdom"),
        "0100152000022000" to Entry("马力欧卡丁车8 豪华版", "Mario Kart 8 Deluxe"),
        "01006F8002326000" to Entry("集合啦！动物森友会", "Animal Crossing: New Horizons"),
        "01006A800016E000" to Entry("任天堂明星大乱斗 特别版", "Super Smash Bros. Ultimate"),
        "01001F5010DFA000" to Entry("宝可梦传说 阿尔宙斯", "Pokemon Legends: Arceus"),
        "0100A3D008C5C000" to Entry("宝可梦 朱", "Pokemon Scarlet"),
        "01008F6008C5E000" to Entry("宝可梦 紫", "Pokemon Violet"),
        "010074F013262000" to Entry("异度神剑3", "Xenoblade Chronicles 3"),
        "010015100B514000" to Entry("超级马力欧兄弟 惊奇", "Super Mario Bros. Wonder"),
        "010093801237C000" to Entry("密特罗德 生存恐惧", "Metroid Dread"),
        "0100A6301214E000" to Entry("火焰之纹章 Engage", "Fire Emblem Engage"),
    )

    private val switchTitleIdPattern = Regex("^01[0-9A-F]{14}$")
}
