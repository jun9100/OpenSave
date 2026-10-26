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
    /** Candidate application data must be explicitly enabled before scanning. */
    val enabledByDefault: Boolean = true,
)

object RootSourcePresets {
    val all = listOf(
        RootSourcePreset("GameHub PC", "/data/user/0/com.xiaoji.egggame/files"),
        RootSourcePreset("GameHub PC（外部数据）", "/storage/emulated/0/Android/data/com.xiaoji.egggame/files"),
        RootSourcePreset("PC 共享目录", "/storage/emulated/0/pc-game"),
        RootSourcePreset("DroidDeck PC", "/data/user/0/com.droiddeck.launcher/files"),
        RootSourcePreset("DroidDeck PC（外部数据）", "/storage/emulated/0/Android/data/com.droiddeck.launcher/files"),
        RootSourcePreset("Eden Switch", "/data/user/0/dev.eden.eden_emulator/files"),
        RootSourcePreset("Eden Switch（外部数据）", "/storage/emulated/0/Android/data/dev.eden.eden_emulator/files"),
        RootSourcePreset("Eden Switch（共享数据）", "/storage/emulated/0/eden"),
        RootSourcePreset("Yuzu", "/data/user/0/org.yuzu.yuzu_emu/files"),
        RootSourcePreset("Yuzu EA", "/data/user/0/org.yuzu.yuzu_emu.ea/files"),
        RootSourcePreset("Suyu", "/data/user/0/dev.suyu.suyu_emu/files"),
        RootSourcePreset("Sudachi", "/data/user/0/org.sudachi.sudachi_emu/files"),
        RootSourcePreset("Citron", "/data/user/0/org.citron.citron_emu/files"),
        RootSourcePreset("Winlator PC", "/data/user/0/com.winlator/files"),
        RootSourcePreset("Winlator PC（外部数据）", "/storage/emulated/0/Android/data/com.winlator/files"),
        RootSourcePreset("Winlator 6.1", "/data/user/0/com.ludashi.benchmark/files"),
        RootSourcePreset("Winlator 6.1（外部数据）", "/storage/emulated/0/Android/data/com.ludashi.benchmark/files"),
        RootSourcePreset("PPSSPP", "/storage/emulated/0/PSP"),
        RootSourcePreset("RetroArch", "/storage/emulated/0/RetroArch"),
        RootSourcePreset("Dolphin", "/storage/emulated/0/dolphin-emu"),
        RootSourcePreset("DuckStation", "/storage/emulated/0/DuckStation"),
        RootSourcePreset("AetherSX2 / NetherSX2", "/storage/emulated/0/AetherSX2"),
        RootSourcePreset("NetherSX2", "/storage/emulated/0/NetherSX2"),
        RootSourcePreset("共享存储（自动发现）", "/storage/emulated/0"),
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
    val requiredAccess: SourceAccessCapability,
    val selected: Boolean,
    val enabledByDefault: Boolean = true,
) {
    val id: String
        get() = source.idSeed

    val sourceId: String
        get() = SnapshotNaming.sourceId(source.idSeed)
}

/** Builds the complete, de-duplicated source list. Sources are enabled by default. */
object SaveSourceOptions {
    fun build(
        authorizedTrees: List<AuthorizedSafTree>,
        rootPath: String?,
        disabledSourceIds: Set<String>,
        explicitlyEnabledSourceIds: Set<String> = emptySet(),
        rootPresets: List<RootSourcePreset> = RootSourcePresets.all,
    ): List<SaveSourceOption> {
        val candidates = linkedMapOf<String, Triple<SourceSelection, String, Boolean>>()
        authorizedTrees.forEach { tree ->
            val source = SourceSelection.Saf(tree.uri)
            candidates[source.idSeed] = Triple(source, tree.label, true)
        }
        rootPresets.forEach { preset ->
            val source = SourceSelection.Root(preset.path)
            candidates.putIfAbsent(source.idSeed, Triple(source, preset.label, preset.enabledByDefault))
        }
        rootPath?.takeIf(String::isNotBlank)?.let { path ->
            val source = SourceSelection.Root(path)
            val label = RootSourcePresets.find(path)?.label ?: "自定义 Root"
            candidates.putIfAbsent(source.idSeed, Triple(source, label, true))
        }
        return candidates.values.map { (source, label, enabledByDefault) ->
            SaveSourceOption(
                source = source,
                label = label,
                saveType = GameSaveTypes.forSource(source),
                requiredAccess = source.requiredAccessCapability(),
                selected = source.idSeed !in disabledSourceIds &&
                    (enabledByDefault || source.idSeed in explicitlyEnabledSourceIds),
                enabledByDefault = enabledByDefault,
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

    fun explicitlyEnabledSourceIds(): Set<String> =
        preferences.getStringSet(EXPLICITLY_ENABLED_SOURCE_IDS_KEY, emptySet()).orEmpty().toSet()

    fun setSourcesEnabled(sourceIds: Collection<String>, enabled: Boolean) {
        if (sourceIds.isEmpty()) return
        val disabled = disabledSourceIds().toMutableSet()
        val explicitlyEnabled = explicitlyEnabledSourceIds().toMutableSet()
        if (enabled) {
            disabled.removeAll(sourceIds.toSet())
            explicitlyEnabled.addAll(sourceIds)
        } else {
            disabled.addAll(sourceIds)
            explicitlyEnabled.removeAll(sourceIds.toSet())
        }
        val editor = preferences.edit()
        if (disabled.isEmpty()) {
            editor.remove(DISABLED_SOURCE_IDS_KEY)
        } else {
            editor.putStringSet(DISABLED_SOURCE_IDS_KEY, disabled)
        }
        if (explicitlyEnabled.isEmpty()) {
            editor.remove(EXPLICITLY_ENABLED_SOURCE_IDS_KEY)
        } else {
            editor.putStringSet(EXPLICITLY_ENABLED_SOURCE_IDS_KEY, explicitlyEnabled)
        }
        editor.apply()
    }

    /**
     * Older Android builds left some preset sources disabled after test or
     * single-folder setup. The first full-device-discovery build re-enables
     * every source it knows about once; later user choices are preserved.
     */
    fun enableFullDeviceDiscoveryOnce(sourceIds: Collection<String>): Boolean {
        if (preferences.getInt(FULL_DEVICE_DISCOVERY_VERSION_KEY, 0) >= FULL_DEVICE_DISCOVERY_VERSION) {
            return false
        }
        val disabled = disabledSourceIds().toMutableSet()
        disabled.removeAll(sourceIds.toSet())
        val editor = preferences.edit().putInt(FULL_DEVICE_DISCOVERY_VERSION_KEY, FULL_DEVICE_DISCOVERY_VERSION)
        if (disabled.isEmpty()) {
            editor.remove(DISABLED_SOURCE_IDS_KEY)
        } else {
            editor.putStringSet(DISABLED_SOURCE_IDS_KEY, disabled)
        }
        editor.apply()
        return true
    }

    private companion object {
        const val PREFERENCES = "opensave_mobile"
        const val MODE_KEY = "source_mode"
        const val ROOT_PATH_KEY = "root_source_path"
        const val DISABLED_SOURCE_IDS_KEY = "disabled_source_ids"
        const val EXPLICITLY_ENABLED_SOURCE_IDS_KEY = "explicitly_enabled_source_ids"
        const val FULL_DEVICE_DISCOVERY_VERSION_KEY = "full_device_discovery_version"
        const val FULL_DEVICE_DISCOVERY_VERSION = 5
    }
}
