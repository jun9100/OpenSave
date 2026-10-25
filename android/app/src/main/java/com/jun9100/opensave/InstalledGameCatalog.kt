package com.jun9100.opensave

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Metadata needed to turn an Android game's persistent folders into save entries. */
data class AndroidGameMetadata(
    val packageName: String,
    val displayName: String,
    val recognition: AndroidGameRecognition = AndroidGameRecognition.CONFIRMED,
) {
    val backsUpByDefault: Boolean
        get() = recognition != AndroidGameRecognition.CANDIDATE
}

enum class AndroidGameRecognition {
    CONFIRMED,
    LIKELY,
    CANDIDATE,
}

/**
 * Finds installed games whose private data may contain game saves.
 * Emulator and PC-container packages remain in the explicit source preset
 * list because their layouts require deeper, dedicated detectors.
 */
class InstalledGameCatalog(private val context: Context) {
    /**
     * Every visible, launchable non-system app that can own app-private data.
     *
     * Android 11+ intentionally limits package visibility. The manifest asks
     * for launcher apps and the known emulator hosts instead of the broad
     * QUERY_ALL_PACKAGES permission. Apps without a launcher remain available
     * through a user-authorized SAF tree or an explicit Root source.
     */
    fun allApps(): Map<String, AndroidGameMetadata> {
        val packageManager = context.packageManager
        @Suppress("DEPRECATION")
        val applications = runCatching {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            ).mapNotNull { it.activityInfo?.applicationInfo }
        }.getOrDefault(emptyList())
        return applications.asSequence()
            .filter { application -> application.packageName != context.packageName }
            .filter { application -> application.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .filterNot { application -> application.packageName in specializedSaveHosts }
            .distinctBy(ApplicationInfo::packageName)
            .map { application ->
                val label = runCatching { application.loadLabel(packageManager).toString().trim() }
                    .getOrDefault("")
                    .ifBlank { knownNativeGames[application.packageName] ?: application.packageName }
                AndroidGameMetadata(
                    packageName = application.packageName,
                    displayName = label,
                    recognition = recognitionOf(application, packageManager),
                )
            }
            .sortedBy { it.displayName.lowercase() }
            .associateBy(AndroidGameMetadata::packageName)
    }

    /** Sources that are safe to scan and back up automatically. */
    fun games(): Map<String, AndroidGameMetadata> = allApps()
        .filterValues { it.recognition != AndroidGameRecognition.CANDIDATE }

    /**
     * Each installed game keeps a separate source boundary. This avoids
     * copying a whole /data/user/0 tree while still covering games that omit
     * Android's CATEGORY_GAME declaration. Shared/external data is covered by
     * the dedicated broad shared-storage source.
     */
    fun rootPresets(): List<RootSourcePreset> = games().values.flatMap { app ->
        val prefix = when (app.recognition) {
            AndroidGameRecognition.CONFIRMED -> "Android 游戏"
            AndroidGameRecognition.LIKELY -> "疑似 Android 游戏"
            AndroidGameRecognition.CANDIDATE -> "应用候选"
        }
        listOf(
            RootSourcePreset("$prefix：${app.displayName}", "/data/user/0/${app.packageName}"),
            // Do not rely on the broad shared-storage traversal: large devices can
            // reach its directory limit before an app-specific external tree.
            RootSourcePreset(
                "$prefix（外部数据）：${app.displayName}",
                "/storage/emulated/0/Android/data/${app.packageName}",
            ),
        )
    }

    /**
     * Android does not require every game APK to declare a game category.
     * Keep those apps visible behind an opt-in source instead of silently
     * omitting a potential save or automatically backing up non-game data.
     */
    fun candidateRootPresets(): List<RootSourcePreset> = allApps().values
        .asSequence()
        .filter { it.recognition == AndroidGameRecognition.CANDIDATE }
        .filter { app -> context.packageManager.getLaunchIntentForPackage(app.packageName) != null }
        .flatMap { app ->
            listOf(
                RootSourcePreset(
                    label = "未归类应用（候选）：${app.displayName}",
                    path = "/data/user/0/${app.packageName}",
                    enabledByDefault = false,
                ),
                RootSourcePreset(
                    label = "未归类应用（候选，外部数据）：${app.displayName}",
                    path = "/storage/emulated/0/Android/data/${app.packageName}",
                    enabledByDefault = false,
                ),
            )
        }
        .toList()

    private fun recognitionOf(application: ApplicationInfo, packageManager: PackageManager): AndroidGameRecognition {
        if (application.packageName in knownNativeGames ||
            application.category == ApplicationInfo.CATEGORY_GAME ||
            application.flags and ApplicationInfo.FLAG_IS_GAME != 0
        ) {
            return AndroidGameRecognition.CONFIRMED
        }
        val launcherName = runCatching {
            packageManager.getLaunchIntentForPackage(application.packageName)
                ?.let { intent -> packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) }
                ?.activityInfo
                ?.name
                .orEmpty()
        }.getOrDefault("").lowercase()
        return if (gameActivityMarkers.any(launcherName::contains)) {
            AndroidGameRecognition.LIKELY
        } else {
            AndroidGameRecognition.CANDIDATE
        }
    }

    private companion object {
        val knownNativeGames = mapOf(
            "com.poncle.vampiresurvivors" to "Vampire Survivors",
            "com.ducvd89.bloodborne" to "Bloodborne",
        )

        val specializedSaveHosts = setOf(
            "dev.eden.eden_emulator",
            "com.xiaoji.egggame",
            "com.winlator",
            "com.ludashi.benchmark",
            "com.droiddeck.launcher",
            "com.limelight.qiin",
            "org.yuzu.yuzu_emu",
            "org.yuzu.yuzu_emu.ea",
            "dev.suyu.suyu_emu",
            "org.sudachi.sudachi_emu",
            "org.citron.citron_emu",
        )

        val gameActivityMarkers = listOf(
            "unity3d.player",
            "godot",
            "unreal",
            "cocos",
            "libgdx",
            "andengine",
        )
    }
}
