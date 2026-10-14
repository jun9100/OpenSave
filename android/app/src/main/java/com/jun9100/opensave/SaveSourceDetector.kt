package com.jun9100.opensave

import java.io.File

data class DetectedSave(
    val kind: String,
    val relativePath: String,
    val titleId: String? = null,
    val note: String,
)

/** Identifies useful save boundaries only inside a user-authorized workspace. */
object SaveSourceDetector {
    private val titleIdPattern = Regex("^[0-9a-fA-F]{16}$")

    fun detect(root: File, selectedRootName: String? = null): List<DetectedSave> {
        if (!root.isDirectory) return emptyList()
        val found = mutableListOf<DetectedSave>()
        val rootName = selectedRootName.orEmpty()
        if (titleIdPattern.matches(rootName)) {
            found += DetectedSave("switch-title", ".", rootName.uppercase(), "选择的目录本身是 Switch TitleID")
        }

        root.walkTopDown()
            .filter { it.isDirectory && it != root }
            .forEach { directory ->
                val relative = directory.relativeTo(root).invariantSeparatorsPath
                val parts = relative.split('/')
                val titleId = parts.lastOrNull()?.takeIf { titleIdPattern.matches(it) }
                val saveIndex = parts.indexOfLast { it.equals("save", ignoreCase = true) }
                if (titleId != null && saveIndex >= 0 && saveIndex < parts.lastIndex) {
                    val family = when {
                        parts.any { it.equals("bis", ignoreCase = true) } -> "ryujinx-switch"
                        parts.any { it.equals("nand", ignoreCase = true) } -> "yuzu-switch"
                        else -> "switch-title"
                    }
                    found += DetectedSave(family, relative, titleId.uppercase(), "按 TitleID 分组的 Switch 存档")
                }

                val lower = parts.joinToString("/").lowercase()
                when {
                    lower.contains("savedata") || lower.contains("ppsspp") ->
                        found += DetectedSave("ppsspp", relative, note = "PPSSPP 存档目录")
                    lower.contains("retroarch") ->
                        found += DetectedSave("retroarch", relative, note = "RetroArch 存档目录")
                    lower.contains("dolphin") ->
                        found += DetectedSave("dolphin", relative, note = "Dolphin 存档目录")
                    lower.contains("duckstation") || lower.contains("aethersx2") || lower.contains("nether sx2") ->
                        found += DetectedSave("emulator", relative, note = "模拟器存档目录")
                    lower.contains("android/data/") || lower.startsWith("android/data") ->
                        found += DetectedSave("android-shared", relative, note = "Android 共享存储应用目录")
                    lower.contains("gamehub") || lower.contains("xiaoji") || lower.contains("egggame") ->
                        found += DetectedSave("gamehub", relative, note = "GameHub/盖世游戏相关目录，需人工确认")
                }
            }

        return found.distinctBy { listOf(it.kind, it.relativePath, it.titleId) }
    }
}
