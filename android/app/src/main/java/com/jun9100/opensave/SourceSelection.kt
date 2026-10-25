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
        RootSourcePreset("DuckStation", "/storage/emulated/0/DuckStation"),
        RootSourcePreset("AetherSX2 / NetherSX2", "/storage/emulated/0/AetherSX2"),
        RootSourcePreset("NetherSX2", "/storage/emulated/0/NetherSX2"),
        RootSourcePreset("Android 应用数据", "/storage/emulated/0/Android/data"),
    )

    fun find(path: String): RootSourcePreset? = all.firstOrNull {
        it.path.trimEnd('/') == path.trimEnd('/')
    }
}

/** A user-visible source choice shared by interactive and scheduled scans. */
data class SaveSourceOption(
    val source: SourceSelection,
    val label: String,
    val saveType: GameSaveType,
    val selected: Boolean,
) {
    val id: String
        get() = source.idSeed
}

/** Builds the complete, de-duplicated source list. Sources are enabled by default. */
object SaveSourceOptions {
    fun build(
        selectedUri: Uri?,
        rootPath: String?,
        disabledSourceIds: Set<String>,
    ): List<SaveSourceOption> {
        val candidates = linkedMapOf<String, Pair<SourceSelection, String>>()
        selectedUri?.let { uri ->
            val source = SourceSelection.Saf(uri)
            candidates[source.idSeed] = source to "系统授权目录"
        }
        RootSourcePresets.all.forEach { preset ->
            val source = SourceSelection.Root(preset.path)
            candidates.putIfAbsent(source.idSeed, source to preset.label)
        }
        rootPath?.takeIf(String::isNotBlank)?.let { path ->
            val source = SourceSelection.Root(path)
            val label = RootSourcePresets.find(path)?.label ?: "自定义 Root"
            candidates.putIfAbsent(source.idSeed, source to label)
        }
        return candidates.values.map { (source, label) ->
            SaveSourceOption(
                source = source,
                label = label,
                saveType = GameSaveTypes.forSource(source),
                selected = source.idSeed !in disabledSourceIds,
            )
        }
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

    fun disabledSourceIds(): Set<String> =
        preferences.getStringSet(DISABLED_SOURCE_IDS_KEY, emptySet()).orEmpty().toSet()

    fun setSourcesEnabled(sourceIds: Collection<String>, enabled: Boolean) {
        if (sourceIds.isEmpty()) return
        val disabled = disabledSourceIds().toMutableSet()
        if (enabled) {
            disabled.removeAll(sourceIds.toSet())
        } else {
            disabled.addAll(sourceIds)
        }
        val editor = preferences.edit()
        if (disabled.isEmpty()) {
            editor.remove(DISABLED_SOURCE_IDS_KEY)
        } else {
            editor.putStringSet(DISABLED_SOURCE_IDS_KEY, disabled)
        }
        editor.apply()
    }

    private companion object {
        const val PREFERENCES = "opensave_mobile"
        const val MODE_KEY = "source_mode"
        const val ROOT_PATH_KEY = "root_source_path"
        const val DISABLED_SOURCE_IDS_KEY = "disabled_source_ids"
    }
}
