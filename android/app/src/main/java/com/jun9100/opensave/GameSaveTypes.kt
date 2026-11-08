package com.jun9100.opensave

/**
 * The service stores each save family in a separate namespace. Keep this
 * model independent from a particular emulator so new emulator families do
 * not require a protocol change.
 */
enum class GameSaveFamily {
    PC,
    SWITCH,
    EMULATOR,
}

data class GameSaveType(
    val id: String,
    val label: String,
    val family: GameSaveFamily,
)

object GameSaveTypes {
    val PC = GameSaveType("pc", "PC 游戏", GameSaveFamily.PC)
    val SWITCH = GameSaveType("switch", "Switch 游戏", GameSaveFamily.SWITCH)
    val PPSSPP = GameSaveType("emulator-ppsspp", "PPSSPP", GameSaveFamily.EMULATOR)
    val RETROARCH = GameSaveType("emulator-retroarch", "RetroArch", GameSaveFamily.EMULATOR)
    val DOLPHIN = GameSaveType("emulator-dolphin", "Dolphin", GameSaveFamily.EMULATOR)
    val DUCKSTATION = GameSaveType("emulator-duckstation", "DuckStation", GameSaveFamily.EMULATOR)
    val AETHERSX2 = GameSaveType("emulator-aethersx2", "AetherSX2 / NetherSX2", GameSaveFamily.EMULATOR)
    val CITRA = GameSaveType("emulator-citra", "Citra / Lime3DS / Azahar", GameSaveFamily.EMULATOR)
    val MELONDS = GameSaveType("emulator-melonds", "melonDS", GameSaveFamily.EMULATOR)
    val VITA3K = GameSaveType("emulator-vita3k", "Vita3K", GameSaveFamily.EMULATOR)
    val MUPEN64PLUS = GameSaveType("emulator-mupen64plus", "Mupen64Plus", GameSaveFamily.EMULATOR)
    val DRASTIC = GameSaveType("emulator-drastic", "DraStic", GameSaveFamily.EMULATOR)
    val LEMUROID = GameSaveType("emulator-lemuroid", "Lemuroid", GameSaveFamily.EMULATOR)
    val EPSXE = GameSaveType("emulator-epsxe", "ePSXe", GameSaveFamily.EMULATOR)
    val MAME = GameSaveType("emulator-mame", "MAME", GameSaveFamily.EMULATOR)
    val ANDROID = GameSaveType("emulator-android", "Android 游戏", GameSaveFamily.EMULATOR)
    val GENERIC = GameSaveType("emulator-generic", "其他模拟器", GameSaveFamily.EMULATOR)

    private val known = listOf(
        PC,
        SWITCH,
        PPSSPP,
        RETROARCH,
        DOLPHIN,
        DUCKSTATION,
        AETHERSX2,
        CITRA,
        MELONDS,
        VITA3K,
        MUPEN64PLUS,
        DRASTIC,
        LEMUROID,
        EPSXE,
        MAME,
        ANDROID,
        GENERIC,
    )
        .associateBy(GameSaveType::id)
    private val emulatorTypePattern = Regex("^emulator-[a-z0-9][a-z0-9-]{0,53}$")

    fun fromId(value: String): GameSaveType {
        val id = value.trim().lowercase()
        known[id]?.let { return it }
        require(isValid(id)) { "不支持的存档类型" }
        return GameSaveType(id, emulatorLabel(id), GameSaveFamily.EMULATOR)
    }

    fun isValid(value: String): Boolean = value == PC.id || value == SWITCH.id ||
        emulatorTypePattern.matches(value)

    fun forSave(save: DetectedSave): GameSaveType = when (save.kind) {
        "gamehub-pc" -> PC
        "yuzu-switch", "ryujinx-switch", "switch-title" -> SWITCH
        "ppsspp" -> PPSSPP
        "retroarch" -> RETROARCH
        "dolphin" -> DOLPHIN
        "duckstation" -> DUCKSTATION
        "aethersx2" -> AETHERSX2
        "citra" -> CITRA
        "melonds" -> MELONDS
        "vita3k" -> VITA3K
        "mupen64plus" -> MUPEN64PLUS
        "drastic" -> DRASTIC
        "lemuroid" -> LEMUROID
        "epsxe" -> EPSXE
        "mame" -> MAME
        "android-app", "android-game" -> ANDROID
        else -> GENERIC
    }

    fun forGame(game: DetectedGame): GameSaveType = game.saves
        .asSequence()
        .filterNot(DetectedSave::candidate)
        .map(::forSave)
        .firstOrNull()
        ?: game.saves.firstOrNull()?.let(::forSave)
        ?: GENERIC

    fun forSource(source: SourceSelection): GameSaveType = when (source) {
        is SourceSelection.Saf -> GENERIC
        is SourceSelection.Root -> when (RootSourcePresets.find(source.path)?.label) {
            "Eden Switch · 私有目录", "Eden Switch · Android/data", "Eden Switch · 公共目录 eden",
            "Eglantine Switch 2 · 私有目录", "Eglantine Switch 2 · Android/data",
            "Eden SX2A Switch 2 · 私有目录", "Eden SX2A Switch 2 · Android/data",
            "Yuzu", "Yuzu EA", "Suyu", "Sudachi", "Citron", "Ryujinx" -> SWITCH
            "GameHub PC · 私有目录", "GameHub PC · Android/data", "PC 游戏 · 公共目录 pc-game",
            "DroidDeck PC · 私有目录", "DroidDeck PC · Android/data",
            "Winlator PC · 私有目录", "Winlator PC · Android/data",
            "Winlator 6.1 · 私有目录", "Winlator 6.1 · Android/data" -> PC
            "PPSSPP" -> PPSSPP
            "RetroArch" -> RETROARCH
            "Dolphin" -> DOLPHIN
            "DuckStation" -> DUCKSTATION
            "AetherSX2 / NetherSX2", "NetherSX2" -> AETHERSX2
            "ePSXe" -> EPSXE
            "小鸡模拟器 · 公共目录 XiaoJi" -> MAME
            else -> rootPathType(source.path)
        }
    }

    private fun rootPathType(path: String): GameSaveType = when {
        path.startsWith("/data/user/") || path.startsWith("/storage/emulated/0/Android/data/") -> ANDROID
        path.contains("winlator", ignoreCase = true) ||
            path.contains("gamehub", ignoreCase = true) ||
            path.contains("pc-game", ignoreCase = true) -> PC
        else -> GENERIC
    }

    private fun emulatorLabel(id: String): String = id
        .removePrefix("emulator-")
        .split('-')
        .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }
        .ifBlank { "其他模拟器" }
}

data class GameCloudIdentity(
    val saveType: GameSaveType,
    val gameId: String,
    val desktopCompatible: Boolean,
)

/**
 * What this device knows about the game behind a cloud scope, reported after a
 * backup so SaveVault 云端 can show one title's scopes from every device as one
 * game (C1). It never changes which scope a save goes to.
 */
data class GameCloudFacts(
    val name: String = "",
    val titleId: String = "",
    val steamAppId: String = "",
    val pspGameId: String = "",
    val androidPackage: String = "",
) {
    val isEmpty: Boolean
        get() = name.isBlank() && titleId.isBlank() && steamAppId.isBlank() && pspGameId.isBlank() && androidPackage.isBlank()

    companion object {
        private const val MAX_NAME_LENGTH = 120

        fun of(game: DetectedGame): GameCloudFacts {
            val presentation = game.presentation
            return GameCloudFacts(
                name = presentation.primaryName.takeUnless { it == "未命名游戏" }.orEmpty().take(MAX_NAME_LENGTH),
                titleId = presentation.switchTitleId.orEmpty(),
                steamAppId = presentation.steamAppId.orEmpty(),
                pspGameId = presentation.pspGameId.orEmpty(),
                androidPackage = presentation.androidPackageName.orEmpty(),
            )
        }
    }
}

/** Names snapshots so only a proven single-directory Root Switch save can cross clients directly. */
object GameCloudNaming {
    /**
     * A SAF copy cannot preserve source mtimes on all Android document
     * providers. Restrict automatic cross-device Switch heads to Root sources
     * so an older document-provider copy cannot become current merely because
     * it was copied later. It is still backed up in its own namespace.
     */
    fun identity(source: SourceSelection, sourceId: String, game: DetectedGame): GameCloudIdentity =
        identity(sourceId, game, portableSource = source is SourceSelection.Root)

    fun identity(
        sourceId: String,
        game: DetectedGame,
        portableSource: Boolean = false,
    ): GameCloudIdentity {
        val type = GameSaveTypes.forGame(game)
        val titleId = game.saves.asSequence()
            .mapNotNull(DetectedSave::titleId)
            .firstOrNull()
            ?.lowercase()
        val desktopCompatible = portableSource && type == GameSaveTypes.SWITCH &&
            titleId != null &&
            game.saves.count { !it.candidate } == 1 &&
            game.saves.none { it.relativePath == "." }
        val gameId = if (desktopCompatible) {
            "switch-$titleId"
        } else {
            val family = when (type.family) {
                GameSaveFamily.PC -> "pc"
                GameSaveFamily.SWITCH -> "switch"
                GameSaveFamily.EMULATOR -> "emu"
            }
            "android-$family-${Hashing.sha256("$sourceId|${game.gameKey}").take(16)}"
        }
        return GameCloudIdentity(type, gameId, desktopCompatible)
    }

    fun remoteName(identity: GameCloudIdentity, timestampMillis: Long): String =
        "${identity.gameId}__main__snap_$timestampMillis.zip"

    fun remotePrefix(identity: GameCloudIdentity): String = "${identity.gameId}__main__snap_"
}
