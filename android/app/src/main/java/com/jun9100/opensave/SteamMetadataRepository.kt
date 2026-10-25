package com.jun9100.opensave

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class SteamNameMetadata(
    val name: String,
    val localized: Boolean,
)

/** Resolves unknown Steam AppIDs without making the scanner depend on a network. */
class SteamMetadataRepository(context: Context) {
    private val directory = File(context.filesDir, "steam-metadata")
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    suspend fun enrich(presentation: GamePresentation): GamePresentation = withContext(Dispatchers.IO) {
        val appId = presentation.steamAppId ?: return@withContext presentation
        if (presentation.localizedName != null || presentation.originalName != null) {
            return@withContext presentation
        }
        val metadata = readCache(appId) ?: fetch(appId)?.also { writeCache(appId, it) }
        metadata?.let {
            if (it.localized) {
                presentation.copy(localizedName = it.name)
            } else {
                presentation.copy(originalName = it.name)
            }
        } ?: presentation
    }

    private fun fetch(appId: String): SteamNameMetadata? = runCatching {
        val url = "https://store.steampowered.com/api/appdetails?appids=$appId&l=schinese&cc=cn"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OpenSave Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val body = response.body?.string().orEmpty()
            parse(body, appId)
        }
    }.getOrNull()

    private fun readCache(appId: String): SteamNameMetadata? = runCatching {
        val file = File(directory, "$appId.json")
        if (!file.isFile) return@runCatching null
        val json = JSONObject(file.readText())
        val name = json.optString("name").trim()
        if (name.isBlank()) null else SteamNameMetadata(name, json.optBoolean("localized"))
    }.getOrNull()

    private fun writeCache(appId: String, metadata: SteamNameMetadata) {
        runCatching {
            if (!directory.exists() && !directory.mkdirs()) return@runCatching
            File(directory, "$appId.json").writeText(
                JSONObject()
                    .put("name", metadata.name)
                    .put("localized", metadata.localized)
                    .toString(),
            )
        }
    }

    companion object {
        fun parse(body: String, appId: String): SteamNameMetadata? = runCatching {
            val app = JSONObject(body).optJSONObject(appId) ?: return@runCatching null
            if (!app.optBoolean("success")) return@runCatching null
            val name = app.optJSONObject("data")?.optString("name")?.trim().orEmpty()
            if (name.isBlank()) return@runCatching null
            SteamNameMetadata(name, name.any(::isCjk))
        }.getOrNull()

        private fun isCjk(character: Char): Boolean =
            character in '\u4E00'..'\u9FFF' || character in '\u3400'..'\u4DBF'

        private const val TIMEOUT_MS = 15L * 1000L
    }
}
