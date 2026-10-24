package com.jun9100.opensave

import java.io.File

data class DetectedSave(
    val kind: String,
    val relativePath: String,
    val titleId: String? = null,
    val note: String,
    val displayName: String? = null,
    val gameKey: String? = null,
    val candidate: Boolean = false,
)

data class SaveScanReport(
    val rootPath: String,
    val scannedDirectoryCount: Int,
    val detected: List<DetectedSave>,
    val unreadableDirectoryCount: Int = 0,
    val truncated: Boolean = false,
) {
    /** Counts only locations with a concrete game identity. */
    val gameCount: Int
        get() = detected
            .asSequence()
            .filterNot { it.candidate || it.kind == "android-app" }
            .map { it.gameKey ?: fallbackGameKey(it) }
            .distinct()
            .count()

    val candidateCount: Int
        get() = detected.count { it.candidate }

    val gameHubPcCount: Int
        get() = detected.count { it.kind == "gamehub-pc" }

    private fun fallbackGameKey(save: DetectedSave): String = listOf(
        save.kind,
        save.titleId.orEmpty(),
        save.displayName.orEmpty(),
        save.relativePath,
    ).joinToString(":")
}

/** Identifies useful save boundaries only inside a user-authorized workspace. */
object SaveSourceDetector {
    private val titleIdPattern = Regex("^[0-9a-fA-F]{16}$")
    private val switchTitleIdPattern = Regex("^01[0-9a-fA-F]{14}$")
    private val appIdPattern = Regex("^\\d{3,}$")
    private val androidPackagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val pcSaveDirectoryNames = setOf(
        "save",
        "saves",
        "savedata",
        "savegames",
        "saved games",
        "remotestorage",
    )
    private val broadPcSaveDirectoryNames = setOf("saved", "userdata")
    private val ignoredPathNames = setOf(
        "cache",
        "code_cache",
        "shader",
        "logs",
        "log",
        "temp",
        "tmp",
    )
    private val ignoredGameHubPathNames = setOf(
        "appcache",
        "steamapps",
        "ugc",
        "unrealengine",
    )

    fun detect(root: File, selectedRootName: String? = null): List<DetectedSave> {
        return scan(root, selectedRootName).detected
    }

    fun scan(root: File, selectedRootName: String? = null): SaveScanReport {
        if (!root.isDirectory) {
            return SaveScanReport(root.path, 0, emptyList())
        }
        val directories = root.walkTopDown()
            .filter { it.isDirectory }
            .take(MAX_SCAN_DIRECTORIES + 1)
            .map { it.path }
            .toList()
        val truncated = directories.size > MAX_SCAN_DIRECTORIES
        return scanDirectories(
            rootPath = root.path,
            directoryPaths = directories.take(MAX_SCAN_DIRECTORIES),
            selectedRootName = selectedRootName,
            truncated = truncated,
        )
    }

    /**
     * Scans directory paths collected by Root's `find` or by SAF. No file is
     * copied and the method never follows a path outside the selected root.
     */
    fun scanDirectories(
        rootPath: String,
        directoryPaths: Iterable<String>,
        selectedRootName: String? = null,
        unreadableDirectoryCount: Int = 0,
        truncated: Boolean = false,
    ): SaveScanReport {
        val paths = directoryPaths.toList()
        val found = mutableListOf<DetectedSave>()
        val rootName = selectedRootName ?: File(rootPath).name
        val relativePaths = paths.map { relativePath(rootPath, it) }

        if (titleIdPattern.matches(rootName)) {
            found += DetectedSave(
                kind = "switch-title",
                relativePath = ".",
                titleId = rootName.uppercase(),
                note = "选择的目录本身是 Switch TitleID",
                displayName = "Switch $rootName",
                gameKey = "switch:${rootName.uppercase()}",
            )
        }

        paths.forEach { absolutePath ->
            val relative = relativePath(rootPath, absolutePath)
            val relativeParts = relative.split('/').filter { it.isNotEmpty() && it != "." }
            val fullParts = absolutePath.trim('/').split('/').filter { it.isNotEmpty() }
            detectSwitch(relative, relativeParts, fullParts, found)
            detectAndroidApp(relative, fullParts, found)
            detectEmulator(relative, relativeParts, fullParts, found)
            detectGameHubPc(relative, relativeParts, fullParts, relativePaths, found)
        }

        val deduplicated = found
            .distinctBy { listOf(it.kind, it.relativePath, it.titleId, it.gameKey) }
            .filterNot { save ->
                save.kind == "gamehub-pc" && isBroadSteamContainer(save, found)
            }
            .sortedWith(
                compareBy<DetectedSave> {
                    it.displayName.orEmpty().lowercase()
                }.thenBy { it.relativePath.lowercase() },
            )
        return SaveScanReport(
            rootPath = rootPath,
            scannedDirectoryCount = paths.size,
            detected = deduplicated,
            unreadableDirectoryCount = unreadableDirectoryCount,
            truncated = truncated,
        )
    }

    private fun detectSwitch(
        relative: String,
        relativeParts: List<String>,
        fullParts: List<String>,
        found: MutableList<DetectedSave>,
    ) {
        val titleId = relativeParts.lastOrNull()?.takeIf { switchTitleIdPattern.matches(it) } ?: return
        val saveIndex = fullParts.indexOfLast { it.equals("save", ignoreCase = true) }
        if (saveIndex < 1 || fullParts.getOrNull(saveIndex - 1)?.equals("user", ignoreCase = true) != true) return
        if (fullParts.size <= saveIndex + 2) return
        if (fullParts.take(saveIndex).any { it.lowercase() in ignoredPathNames }) return

        val family = when {
            fullParts.any { it.equals("bis", ignoreCase = true) } -> "ryujinx-switch"
            fullParts.any { it.equals("nand", ignoreCase = true) } -> "yuzu-switch"
            else -> "switch-title"
        }
        found += DetectedSave(
            kind = family,
            relativePath = relative,
            titleId = titleId.uppercase(),
            note = "按 TitleID 分组的 Switch 存档",
            displayName = "Switch ${titleId.uppercase()}",
            gameKey = "switch:${titleId.uppercase()}",
        )
    }

    private fun detectAndroidApp(
        relative: String,
        fullParts: List<String>,
        found: MutableList<DetectedSave>,
    ) {
        val androidIndex = fullParts.indexOfLast { it.equals("android", ignoreCase = true) }
        if (androidIndex < 0 || androidIndex + 2 != fullParts.size) return
        if (!fullParts[androidIndex + 1].equals("data", ignoreCase = true)) return
        val packageName = fullParts.lastOrNull().orEmpty()
        if (!androidPackagePattern.matches(packageName)) return
        found += DetectedSave(
            kind = "android-app",
            relativePath = relative,
            note = "Android 应用数据目录，可能包含游戏存档",
            displayName = packageName,
            gameKey = "android:$packageName",
            candidate = true,
        )
    }

    private fun detectEmulator(
        relative: String,
        relativeParts: List<String>,
        fullParts: List<String>,
        found: MutableList<DetectedSave>,
    ) {
        val lowerParts = relativeParts.map { it.lowercase() }
        val last = lowerParts.lastOrNull().orEmpty()
        val lowerPath = fullParts.joinToString("/").lowercase()
        when {
            lowerPath.contains("ppsspp") && last == "savedata" ->
                simpleDetection("ppsspp", relative, "PPSSPP 存档目录", "PPSSPP", found)?.let { found += it }

            lowerPath.contains("retroarch") && last in setOf("saves", "states") ->
                simpleDetection("retroarch", relative, "RetroArch 存档目录", "RetroArch", found)?.let { found += it }

            lowerPath.contains("dolphin") && last in setOf("wii", "gc", "statesaves", "state saves") ->
                simpleDetection("dolphin", relative, "Dolphin 存档目录", "Dolphin", found)?.let { found += it }

            lowerPath.contains("duckstation") || lowerPath.contains("aethersx2") || lowerPath.contains("nether sx2") -> {
                if (last in setOf("memcards", "sstates", "savestates", "states", "memorycards")) {
                    simpleDetection("emulator", relative, "模拟器存档目录", last, found)?.let { found += it }
                }
            }
        }
    }

    private fun detectGameHubPc(
        relative: String,
        relativeParts: List<String>,
        fullParts: List<String>,
        allRelativePaths: List<String>,
        found: MutableList<DetectedSave>,
    ) {
        val lowerFull = fullParts.joinToString("/").lowercase()
        val gameHubPath = listOf(
            "com.xiaoji.egggame",
            "gamehub",
            "xiaoji",
            "/pc/",
        ).any { lowerFull.contains(it) } || lowerFull.endsWith("/pc")
        if (!gameHubPath) return
        if (fullParts.any { it.lowercase() in ignoredGameHubPathNames }) return

        val lowerParts = relativeParts.map { it.lowercase() }
        val last = lowerParts.lastOrNull().orEmpty()
        val userdataIndex = lowerParts.indexOfLast { it == "userdata" }
        if (
            userdataIndex >= 0 &&
            relativeParts.size == userdataIndex + 3 &&
            appIdPattern.matches(relativeParts.last())
        ) {
            val appId = relativeParts.last()
            found += DetectedSave(
                kind = "gamehub-pc",
                relativePath = relative,
                note = "GameHub 本地 PC 的 Steam userdata 容器，未命名的存档也会落在这里",
                displayName = "Steam AppID $appId",
                gameKey = "steam:$appId",
                candidate = true,
            )
        }

        val saveName = last.takeIf { it in pcSaveDirectoryNames } ?: return
        if (saveName in broadPcSaveDirectoryNames && hasMoreSpecificSave(relative, allRelativePaths)) return

        val appId = userdataIndex
            .takeIf { it >= 0 && it + 2 < relativeParts.size }
            ?.let { relativeParts[it + 2] }
            ?.takeIf { appIdPattern.matches(it) }
        val displayName = inferPcGameName(relativeParts, fullParts, appId)
        val anonymousSavedGames = saveName == "saved games" && isAnonymousSavedGames(relativeParts)
        found += DetectedSave(
            kind = "gamehub-pc",
            relativePath = relative,
            note = if (anonymousSavedGames) {
                "GameHub 本地 PC 的未命名 Saved Games 目录，需人工确认归属"
            } else {
                "GameHub 本地 PC 游戏存档目录，可备份；不会把整个游戏安装目录当成存档"
            },
            displayName = if (anonymousSavedGames) "未命名 PC 存档" else displayName,
            gameKey = if (anonymousSavedGames) {
                "candidate:$relative"
            } else {
                normalizedGameKey(displayName, appId)
            },
            candidate = anonymousSavedGames,
        )
    }

    private fun isAnonymousSavedGames(parts: List<String>): Boolean {
        val lower = parts.map { it.lowercase() }
        return lower.takeLast(3) == listOf("users", "steamuser", "saved games")
    }

    private fun normalizedGameKey(displayName: String, appId: String?): String {
        val normalizedName = displayName
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
        return if (normalizedName.isNotEmpty() && normalizedName != "pc 游戏") {
            "pc:$normalizedName"
        } else {
            appId?.let { "steam:$it" } ?: "pc:$normalizedName"
        }
    }

    private fun simpleDetection(
        kind: String,
        relative: String,
        note: String,
        displayName: String,
        found: List<DetectedSave>,
    ): DetectedSave? {
        if (found.any { it.kind == kind && it.relativePath == relative }) return null
        return DetectedSave(kind, relative, note = note, displayName = displayName)
    }

    private fun isBroadSteamContainer(save: DetectedSave, found: List<DetectedSave>): Boolean {
        val path = save.relativePath.trimEnd('/')
        val isAppIdContainer = path.substringAfterLast('/').let { appIdPattern.matches(it) } &&
            found.any { it !== save && it.kind == "gamehub-pc" && it.relativePath.startsWith("$path/") }
        return isAppIdContainer
    }

    private fun hasMoreSpecificSave(relative: String, paths: List<String>): Boolean {
        val prefix = relative.trimEnd('/') + "/"
        return paths.any { path ->
            path.startsWith(prefix) &&
                path.removePrefix(prefix).split('/').firstOrNull()?.lowercase() !in broadPcSaveDirectoryNames
        }
    }

    private fun inferPcGameName(parts: List<String>, fullParts: List<String>, appId: String?): String {
        if (appId != null) {
            val acIndex = parts.indexOfLast { it.equals("ac", ignoreCase = true) }
            findGameNameBeforeSave(parts, acIndex)?.let { return it }
            return "Steam AppID $appId"
        }

        val localLowIndex = parts.indexOfLast { it.equals("locallow", ignoreCase = true) }
        if (localLowIndex >= 0 && localLowIndex + 2 < parts.size) return parts[localLowIndex + 2]
        val myGamesIndex = parts.indexOfLast { it.equals("my games", ignoreCase = true) }
        if (myGamesIndex >= 0 && myGamesIndex + 1 < parts.size) return parts[myGamesIndex + 1]
        val pcIndex = parts.indexOfLast { it.equals("pc", ignoreCase = true) }
        if (pcIndex >= 0 && pcIndex + 1 < parts.size) return parts[pcIndex + 1]
        val fullPcIndex = fullParts.indexOfLast { it.equals("pc", ignoreCase = true) }
        if (fullPcIndex >= 0 && fullPcIndex + 1 < fullParts.size) return fullParts[fullPcIndex + 1]
        findGameNameBeforeSave(parts, minimumIndex = -1)?.let { return it }
        return "PC 游戏"
    }

    /** Handles both `Game/Save` and `Game/Saved/<user>/SaveGames` layouts. */
    private fun findGameNameBeforeSave(parts: List<String>, minimumIndex: Int): String? {
        val savedIndex = parts.indexOfLast { it.equals("saved", ignoreCase = true) }
        if (savedIndex > minimumIndex && savedIndex > 0) return parts[savedIndex - 1]

        val saveIndex = parts.indexOfLast { it.lowercase() in pcSaveDirectoryNames }
        if (saveIndex > minimumIndex && saveIndex > 0) return parts[saveIndex - 1]
        return null
    }

    private fun relativePath(rootPath: String, absolutePath: String): String {
        val root = rootPath.trimEnd('/').ifEmpty { "/" }
        if (absolutePath == root) return "."
        val prefix = if (root == "/") "/" else "$root/"
        return absolutePath.removePrefix(prefix).trim('/').ifEmpty { "." }
    }

    private const val MAX_SCAN_DIRECTORIES = 50_000
}
