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
    val ANDROID = GameSaveType("emulator-android", "Android 游戏", GameSaveFamily.EMULATOR)
    val GENERIC = GameSaveType("emulator-generic", "其他模拟器", GameSaveFamily.EMULATOR)

    private val known = listOf(PC, SWITCH, PPSSPP, RETROARCH, DOLPHIN, ANDROID, GENERIC)
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
        "android-app" -> ANDROID
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
            "GameHub PC" -> PC
            "Eden Switch", "Yuzu", "Yuzu EA", "Suyu", "Sudachi", "Citron", "Ryujinx" -> SWITCH
            "PPSSPP" -> PPSSPP
            "RetroArch" -> RETROARCH
            "Dolphin" -> DOLPHIN
            "Android 应用数据" -> ANDROID
            else -> GENERIC
        }
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

/** Names snapshots so only a proven single-directory Switch save can cross clients directly. */
object GameCloudNaming {
    fun identity(sourceId: String, game: DetectedGame): GameCloudIdentity {
        val type = GameSaveTypes.forGame(game)
        val titleId = game.saves.asSequence()
            .mapNotNull(DetectedSave::titleId)
            .firstOrNull()
            ?.lowercase()
        val desktopCompatible = type == GameSaveTypes.SWITCH &&
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
