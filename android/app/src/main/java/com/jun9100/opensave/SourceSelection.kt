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
        val canonical = RootPathPolicy.canonicalize(path)
        preferences.edit().putString(ROOT_PATH_KEY, canonical).apply()
        canonical
    }

    private companion object {
        const val PREFERENCES = "opensave_mobile"
        const val MODE_KEY = "source_mode"
        const val ROOT_PATH_KEY = "root_source_path"
    }
}
