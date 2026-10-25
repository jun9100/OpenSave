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
        "remote",
    )
    private val broadPcSaveDirectoryNames = setOf("saved", "userdata")
    private val androidPersistentDirectoryNames = setOf(
        "files",
        "databases",
        "shared_prefs",
        "no_backup",
        "app_flutter",
    )
    private val ignoredAndroidAppDirectoryNames = setOf(
        "cache",
        "code_cache",
        "app_webview",
        "app_textures",
        "lib",
        "oat",
    )
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
        "ugc",
        "unrealengine",
    )
    private val dedicatedSharedStorageRoots = setOf(
        "eden",
        "pc-game",
        "psp",
        "retroarch",
        "dolphin-emu",
        "duckstation",
        "aethersx2",
        "nethersx2",
    )
    private val dedicatedSharedStoragePackages = setOf(
        "dev.eden.eden_emulator",
        "com.xiaoji.egggame",
        "com.droiddeck.launcher",
        "com.winlator",
        "com.ludashi.benchmark",
    )
    private val excludedArtifactPathNames = setOf(
        "opensaveintegrationtest",
        "eden-save-import-stage",
        "eden-sync-test",
        "eden-test",
    )
    // Steam uses AppID 7 for client bootstrap data, not a game save.
    private val ignoredSteamAppIds = setOf("7")
    private val dedicatedAndroidSavePackages = setOf("com.ducvd89.bloodborne")

    fun detect(
        root: File,
        selectedRootName: String? = null,
        androidGames: Map<String, AndroidGameMetadata> = emptyMap(),
    ): List<DetectedSave> {
        return scan(root, selectedRootName, androidGames).detected
    }

    fun scan(
        root: File,
        selectedRootName: String? = null,
        androidGames: Map<String, AndroidGameMetadata> = emptyMap(),
    ): SaveScanReport {
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
            androidGames = androidGames,
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
        androidGames: Map<String, AndroidGameMetadata> = emptyMap(),
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
            if (isExcludedArtifact(fullParts) || isCoveredByDedicatedSharedSource(rootPath, relativeParts, androidGames)) {
                return@forEach
            }
            detectSwitch(relative, relativeParts, fullParts, found)
            detectAndroidApp(rootPath, relative, relativeParts, fullParts, androidGames, found)
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
        rootPath: String,
        relative: String,
        relativeParts: List<String>,
        fullParts: List<String>,
        androidGames: Map<String, AndroidGameMetadata>,
        found: MutableList<DetectedSave>,
    ) {
        val appPath = findAndroidAppPath(fullParts)
        val selectedPackage = rootPath.trimEnd('/').substringAfterLast('/')
            .takeIf { androidPackagePattern.matches(it) }
        val packageName = appPath?.packageName ?: selectedPackage ?: return
        // Only package metadata collected from PackageManager may turn an app
        // data directory into a candidate. This keeps system-service state out.
        val metadata = androidGames[packageName] ?: return
        val directChild = when {
            appPath != null && relative != "." && fullParts.size == appPath.packageIndex + 2 -> fullParts.last()
            appPath == null && selectedPackage == packageName && relativeParts.size == 1 -> relativeParts.single()
            else -> null
        }?.lowercase()

        detectDedicatedAndroidSave(packageName, metadata, relative, relativeParts, found)?.let { dedicated ->
            found += dedicated
            return
        }

        if (directChild in androidPersistentDirectoryNames && packageName !in dedicatedAndroidSavePackages) {
            val identifiedGame = metadata?.backsUpByDefault == true
            found += DetectedSave(
                kind = if (identifiedGame) "android-game" else "android-app",
                relativePath = relative,
                note = if (identifiedGame) {
                    "Android 游戏持久数据目录"
                } else {
                    "Android 应用持久数据目录，尚未确认是游戏存档"
                },
                displayName = metadata?.displayName ?: packageName,
                gameKey = "android:$packageName",
                candidate = !identifiedGame,
            )
            return
        }

        if (
            directChild != null &&
            directChild !in ignoredAndroidAppDirectoryNames &&
            packageName !in dedicatedAndroidSavePackages
        ) {
            found += DetectedSave(
                kind = if (metadata.backsUpByDefault) "android-game" else "android-app",
                relativePath = relative,
                note = "未识别的 Android 应用数据目录，确认后可作为存档备份",
                displayName = metadata.displayName,
                gameKey = "android:$packageName",
                candidate = true,
            )
            return
        }

    }

    private fun detectDedicatedAndroidSave(
        packageName: String,
        metadata: AndroidGameMetadata?,
        relative: String,
        relativeParts: List<String>,
        found: List<DetectedSave>,
    ): DetectedSave? {
        val lowerParts = relativeParts.map(String::lowercase)
        if (
            packageName == "com.ducvd89.bloodborne" &&
            lowerParts == listOf("files", "bbport", "user", "savedata")
        ) {
            return simpleDetection(
                kind = "android-game",
                relative = relative,
                note = "Bloodborne 的实际游戏存档目录",
                displayName = metadata?.displayName ?: "Bloodborne",
                found = found,
            )?.copy(
                gameKey = "android:$packageName",
                candidate = metadata?.backsUpByDefault != true,
            )
        }
        return null
    }

    private fun findAndroidAppPath(fullParts: List<String>): AndroidAppPath? {
        val externalIndex = fullParts.indexOfLast { it.equals("android", ignoreCase = true) }
        if (externalIndex >= 0 && fullParts.getOrNull(externalIndex + 1)?.equals("data", ignoreCase = true) == true) {
            val packageIndex = externalIndex + 2
            val packageName = fullParts.getOrNull(packageIndex).orEmpty()
            if (androidPackagePattern.matches(packageName)) return AndroidAppPath(packageName, packageIndex)
        }

        val dataIndex = fullParts.indexOfLast { it.equals("data", ignoreCase = true) }
        if (
            dataIndex >= 0 &&
            fullParts.getOrNull(dataIndex + 1)?.equals("user", ignoreCase = true) == true &&
            fullParts.getOrNull(dataIndex + 2)?.all(Char::isDigit) == true
        ) {
            val packageIndex = dataIndex + 3
            val packageName = fullParts.getOrNull(packageIndex).orEmpty()
            if (androidPackagePattern.matches(packageName)) return AndroidAppPath(packageName, packageIndex)
        }
        return null
    }

    private data class AndroidAppPath(
        val packageName: String,
        val packageIndex: Int,
    )

    private fun detectEmulator(
        relative: String,
        relativeParts: List<String>,
        fullParts: List<String>,
        found: MutableList<DetectedSave>,
    ) {
        val lowerParts = relativeParts.map { it.lowercase() }
        val last = lowerParts.lastOrNull().orEmpty()
        val lowerPath = fullParts.joinToString("/").lowercase()
        val isSwitchSaveRoot = fullParts.windowed(2).any { pair ->
            pair[0].equals("user", ignoreCase = true) && pair[1].equals("save", ignoreCase = true)
        }
        if (isSwitchSaveRoot) return
        when {
            lowerPath.contains("ppsspp") && last == "savedata" ->
                simpleDetection("ppsspp", relative, "PPSSPP 存档目录", "PPSSPP", found)?.let { found += it }

            lowerPath.contains("retroarch") && last in setOf("saves", "states") ->
                simpleDetection("retroarch", relative, "RetroArch 存档目录", "RetroArch", found)?.let { found += it }

            lowerPath.contains("dolphin") && last in setOf("wii", "gc", "statesaves", "state saves") ->
                simpleDetection("dolphin", relative, "Dolphin 存档目录", "Dolphin", found)?.let { found += it }

            lowerPath.contains("duckstation") && last in setOf("memcards", "sstates", "savestates", "states", "memorycards") ->
                simpleDetection("duckstation", relative, "DuckStation 存档目录", "DuckStation", found)?.let { found += it }

            (lowerPath.contains("aethersx2") || lowerPath.contains("nethersx2") || lowerPath.contains("nether sx2")) &&
                last in setOf("memcards", "sstates", "savestates", "states", "memorycards") ->
                simpleDetection("aethersx2", relative, "AetherSX2/NetherSX2 存档目录", "AetherSX2", found)?.let { found += it }

            (lowerPath.contains("citra") || lowerPath.contains("lime3ds") || lowerPath.contains("azahar") ||
                lowerPath.contains("panda3ds")) && last in emulatorSaveDirectoryNames ->
                simpleDetection("citra", relative, "3DS 模拟器存档目录", "Citra / Lime3DS", found)?.let { found += it }

            lowerPath.contains("melonds") && last in emulatorSaveDirectoryNames ->
                simpleDetection("melonds", relative, "melonDS 存档目录", "melonDS", found)?.let { found += it }

            lowerPath.contains("vita3k") && last in emulatorSaveDirectoryNames ->
                simpleDetection("vita3k", relative, "Vita3K 存档目录", "Vita3K", found)?.let { found += it }

            (lowerPath.contains("mupen64") || lowerPath.contains("m64plus")) && last in emulatorSaveDirectoryNames ->
                simpleDetection("mupen64plus", relative, "Mupen64Plus 存档目录", "Mupen64Plus", found)?.let { found += it }

            lowerPath.contains("drastic") && last in emulatorSaveDirectoryNames ->
                simpleDetection("drastic", relative, "DraStic 存档目录", "DraStic", found)?.let { found += it }

            lowerPath.contains("lemuroid") && last in emulatorSaveDirectoryNames ->
                simpleDetection("lemuroid", relative, "Lemuroid 存档目录", "Lemuroid", found)?.let { found += it }

            lowerPath.contains("emulator") && last in emulatorSaveDirectoryNames ->
                simpleDetection("emulator", relative, "模拟器存档目录", "其他模拟器", found)?.let { found += it }
        }
    }

    private fun isExcludedArtifact(fullParts: List<String>): Boolean {
        val lowerParts = fullParts.map(String::lowercase)
        return lowerParts.any { part ->
            part in excludedArtifactPathNames ||
                part.startsWith("eden-migration-final-") ||
                part.startsWith("eden-save-before-import-")
        }
    }

    /** The broad shared-storage source fills unknown gaps without duplicating dedicated roots. */
    private fun isCoveredByDedicatedSharedSource(
        rootPath: String,
        relativeParts: List<String>,
        androidGames: Map<String, AndroidGameMetadata>,
    ): Boolean {
        if (rootPath.trimEnd('/') !in setOf("/storage/emulated/0", "/sdcard")) return false
        val lowerParts = relativeParts.map(String::lowercase)
        if (lowerParts.firstOrNull() in dedicatedSharedStorageRoots) return true
        if (lowerParts.getOrNull(0) != "android" || lowerParts.getOrNull(1) != "data") return false
        val packageName = lowerParts.getOrNull(2) ?: return false
        return packageName in dedicatedSharedStoragePackages ||
            androidGames[packageName]?.backsUpByDefault == true
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
            "com.winlator",
            "winlator",
            "com.droiddeck.launcher",
            "droiddeck",
            "com.ludashi.benchmark",
            "pc-game",
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
            if (appId !in ignoredSteamAppIds) {
                found += DetectedSave(
                    kind = "gamehub-pc",
                    relativePath = relative,
                    note = "Android PC 容器的 Steam AppID 存档容器",
                    displayName = "Steam AppID $appId",
                    gameKey = "steam:$appId",
                )
            }
        }

        detectEldenRingSave(relative, relativeParts, lowerParts, userdataIndex, found)?.let { detected ->
            found += detected
            return
        }

        val saveName = last.takeIf { it in pcSaveDirectoryNames } ?: return
        val isDroidDeckPath = lowerFull.contains("com.droiddeck.launcher") || lowerFull.contains("droiddeck")
        if (!isAllowedSteamInstallSave(lowerParts, isDroidDeckPath)) return
        if (saveName in broadPcSaveDirectoryNames && hasMoreSpecificSave(relative, allRelativePaths)) return

        val appId = steamAppId(relativeParts, userdataIndex)
        if (appId != null && appId in ignoredSteamAppIds) return
        if (saveName == "remote" && appId != null && hasMoreSpecificSteamSave(relative, relativeParts, allRelativePaths)) {
            return
        }
        val displayName = inferPcGameName(relativeParts, fullParts, appId)
        val anonymousSavedGames = saveName == "saved games" && appId == null && isAnonymousSavedGames(relativeParts)
        found += DetectedSave(
            kind = "gamehub-pc",
            relativePath = relative,
            note = if (anonymousSavedGames) {
                "Android PC 容器的 Saved Games 存档目录"
            } else {
                "Android PC 容器的游戏存档目录，可备份；不会把整个游戏安装目录当成存档"
            },
            displayName = if (anonymousSavedGames) "PC Saved Games 容器" else displayName,
            gameKey = if (anonymousSavedGames) "pc-container:$relative" else normalizedGameKey(displayName, appId),
        )
    }

    private fun detectEldenRingSave(
        relative: String,
        parts: List<String>,
        lowerParts: List<String>,
        userdataIndex: Int,
        found: List<DetectedSave>,
    ): DetectedSave? {
        val eldenRingIndex = lowerParts.indexOfLast { it == "eldenring" }
        if (eldenRingIndex < 1 || eldenRingIndex + 1 != lowerParts.size - 1) return null
        if (!lowerParts.take(eldenRingIndex).containsAll(listOf("appdata", "roaming"))) return null
        if (lowerParts.any { it.contains("backup") }) return null
        val profileId = parts.getOrNull(eldenRingIndex + 1).orEmpty()
        if (!appIdPattern.matches(profileId)) return null
        val appId = steamAppId(parts, userdataIndex)
        if (appId == null || appId in ignoredSteamAppIds || appId == "0") return null
        return simpleDetection(
            kind = "gamehub-pc",
            relative = relative,
            note = "Android PC 容器的 ELDEN RING 存档目录",
            displayName = "Elden Ring",
            found = found,
        )?.copy(gameKey = "steam:$appId")
    }

    /** DroidDeck hosts both Proton prefixes and native Linux game saves under SteamApps. */
    private fun isAllowedSteamInstallSave(parts: List<String>, isDroidDeckPath: Boolean): Boolean {
        val steamAppsIndex = parts.indexOfLast { it == "steamapps" }
        if (steamAppsIndex < 0) return true
        if (!isDroidDeckPath) return false
        return when (parts.getOrNull(steamAppsIndex + 1)) {
            "compatdata" -> parts.getOrNull(steamAppsIndex + 2)?.let { appId ->
                appIdPattern.matches(appId) && appId != "0"
            } ?: false
            "common" -> parts.getOrNull(steamAppsIndex + 2)
                ?.let(::isSteamRuntimeDirectory)
                ?.not()
                ?: false
            else -> false
        }
    }

    private fun isSteamRuntimeDirectory(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized.contains("proton") || normalized.contains("steamlinuxruntime") ||
            normalized.contains("steam runtime") || normalized.contains("redistributable")
    }

    private fun steamAppId(parts: List<String>, userdataIndex: Int): String? {
        userdataIndex
            .takeIf { it >= 0 && it + 2 < parts.size }
            ?.let { parts[it + 2] }
            ?.takeIf { appIdPattern.matches(it) || it in ignoredSteamAppIds }
            ?.let { return it }

        val compatDataIndex = parts.indexOfLast { it.equals("compatdata", ignoreCase = true) }
        compatDataIndex
            .takeIf { it >= 0 && it + 1 < parts.size }
            ?.let { parts[it + 1] }
            ?.takeIf { appIdPattern.matches(it) || it in ignoredSteamAppIds }
            ?.let { return it }

        val remoteIndex = parts.indexOfLast { it.equals("remote", ignoreCase = true) }
        val appId = parts.getOrNull(remoteIndex - 1)
            ?.takeIf { appIdPattern.matches(it) || it in ignoredSteamAppIds }
            ?: return null
        return appId.takeIf { candidate ->
            parts.take(remoteIndex).any { it.equals("steam", ignoreCase = true) }
        }
    }

    private fun hasMoreSpecificSteamSave(
        remotePath: String,
        relativeParts: List<String>,
        paths: List<String>,
    ): Boolean {
        val remoteIndex = relativeParts.indexOfLast { it.equals("remote", ignoreCase = true) }
        if (remoteIndex <= 0) return false
        val appRoot = relativeParts.take(remoteIndex).joinToString("/")
        val prefix = "$appRoot/"
        return paths.any { path ->
            if (path == remotePath || !path.startsWith(prefix)) return@any false
            val last = path.substringAfterLast('/').lowercase()
            last in pcSaveDirectoryNames && last != "remote"
        }
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
            findGameNameBeforeSave(parts, acIndex)
                ?.takeUnless { it == appId || isGenericPcPathName(it) }
                ?.let { return it }
            return "Steam AppID $appId"
        }

        if (parts.any { it.equals("pc-game", ignoreCase = true) } ||
            fullParts.any { it.equals("pc-game", ignoreCase = true) }
        ) {
            return "PC 共享存档"
        }

        val localLowIndex = parts.indexOfLast { it.equals("locallow", ignoreCase = true) }
        if (localLowIndex >= 0 && localLowIndex + 2 < parts.size) return parts[localLowIndex + 2]
        val myGamesIndex = parts.indexOfLast { it.equals("my games", ignoreCase = true) }
        if (myGamesIndex >= 0 && myGamesIndex + 1 < parts.size) return parts[myGamesIndex + 1]
        val steamCommonIndex = parts.indexOfLast { it.equals("common", ignoreCase = true) }
        if (
            steamCommonIndex > 0 &&
            parts.getOrNull(steamCommonIndex - 1)?.equals("steamapps", ignoreCase = true) == true &&
            steamCommonIndex + 1 < parts.size
        ) {
            return parts[steamCommonIndex + 1]
        }
        val pcIndex = parts.indexOfLast { it.equals("pc", ignoreCase = true) }
        if (pcIndex >= 0 && pcIndex + 1 < parts.size) return parts[pcIndex + 1]
        val fullPcIndex = fullParts.indexOfLast { it.equals("pc", ignoreCase = true) }
        if (fullPcIndex >= 0 && fullPcIndex + 1 < fullParts.size) return fullParts[fullPcIndex + 1]
        findGameNameBeforeSave(parts, minimumIndex = -1)?.let { return it }
        return "PC 游戏"
    }

    private fun isGenericPcPathName(value: String): Boolean {
        val normalized = value.trim().lowercase()
        return normalized in setOf("steamuser", "xuser", "user", "users", "public", "remote", "userdata") ||
            appIdPattern.matches(normalized)
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

    private val emulatorSaveDirectoryNames = setOf(
        "save",
        "saves",
        "savedata",
        "savegames",
        "states",
        "savestates",
        "sstates",
        "memcards",
        "memorycards",
        "battery",
    )

    private const val MAX_SCAN_DIRECTORIES = 50_000
}
