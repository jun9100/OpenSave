package com.jun9100.opensave

import android.content.Context
import android.net.Uri

enum class StorageMode {
    SAF,
    ROOT,
}

sealed interface SourceSelection {
    val idSeed: String
    val displayPath: String

    data class Saf(val uri: Uri) : SourceSelection {
        // Keep the pre-Root source ID contract so existing SAF history remains deduplicated.
        override val idSeed: String = uri.toString()
        override val displayPath: String = uri.toString()
    }

    data class Root(val path: String) : SourceSelection {
        override val idSeed: String = "root:$path"
        override val displayPath: String = path
    }
}

data class RootSourcePreset(
    val label: String,
    val path: String,
)

object RootSourcePresets {
    val all = listOf(
        RootSourcePreset("GameHub PC", "/data/user/0/com.xiaoji.egggame/files"),
        RootSourcePreset("Eden Switch", "/data/user/0/dev.eden.eden_emulator/files"),
        RootSourcePreset("Yuzu", "/data/user/0/org.yuzu.yuzu_emu/files"),
        RootSourcePreset("Yuzu EA", "/data/user/0/org.yuzu.yuzu_emu.ea/files"),
        RootSourcePreset("Suyu", "/data/user/0/dev.suyu.suyu_emu/files"),
        RootSourcePreset("Sudachi", "/data/user/0/org.sudachi.sudachi_emu/files"),
        RootSourcePreset("Citron", "/data/user/0/org.citron.citron_emu/files"),
        RootSourcePreset("PPSSPP", "/storage/emulated/0/PSP"),
        RootSourcePreset("RetroArch", "/storage/emulated/0/RetroArch"),
        RootSourcePreset("Dolphin", "/storage/emulated/0/dolphin-emu"),
        RootSourcePreset("Android 应用数据", "/storage/emulated/0/Android/data"),
    )

    fun find(path: String): RootSourcePreset? = all.firstOrNull {
        it.path.trimEnd('/') == path.trimEnd('/')
    }
}

class SourceSelectionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun mode(): StorageMode = runCatching {
        StorageMode.valueOf(preferences.getString(MODE_KEY, StorageMode.SAF.name).orEmpty())
    }.getOrDefault(StorageMode.SAF)

    fun saveMode(mode: StorageMode) {
        preferences.edit().putString(MODE_KEY, mode.name).apply()
    }

    fun rootPath(): String? = preferences.getString(ROOT_PATH_KEY, null)

    fun saveRootPath(path: String): Result<String> = runCatching {
        val canonical = RootPathPolicy.canonicalizeForScan(path)
        preferences.edit().putString(ROOT_PATH_KEY, canonical).apply()
        canonical
    }

    private companion object {
        const val PREFERENCES = "opensave_mobile"
        const val MODE_KEY = "source_mode"
        const val ROOT_PATH_KEY = "root_source_path"
    }
}
