package com.jun9100.opensave

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Covers for the library, first found wins: a cover the user picked; one
 * cached earlier (downloaded, or a PSP icon copied from the save folder); a
 * Steam / Switch cover downloaded once, scaled down and kept in app-private
 * storage; the installed launcher icon of the Android game or of the
 * emulator that owns the saves.
 */
class CoverRepository(context: Context) {
    private val contentResolver = context.contentResolver
    private val packageManager = context.packageManager
    private val steamSearchDirectory = File(context.filesDir, "steam-search")
    private val directory = File(context.filesDir, "covers")
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    suspend fun load(presentation: GamePresentation): Bitmap? = withContext(Dispatchers.IO) {
        presentation.gameKey?.let { decode(customFile(it)) }?.let { return@withContext it }
        presentation.coverCacheKey?.let { decode(File(directory, "$it.jpg")) }?.let { return@withContext it }

        val searched = presentation.steamSearchName?.let(::steamAppIdByName)
        val (cacheKey, urls) = if (searched != null) {
            "steam-$searched" to presentation.copy(steamAppId = searched).coverUrls
        } else {
            presentation.coverCacheKey to presentation.coverUrls
        }
        val downloaded = if (cacheKey != null && urls.isNotEmpty()) download(cacheKey, urls) else null
        downloaded
            ?: presentation.androidPackageName?.let(::appIcon)
            ?: presentation.emulatorPackages.firstNotNullOfOrNull(::appIcon)
    }

    /** Copies a PSP save's icon so the game has a cover without the save folder being read again. */
    fun savePspIcon(gameId: String, bytes: ByteArray): Boolean {
        val target = File(directory, "ppsspp-${gameId.lowercase()}.jpg")
        if (target.isFile) return false
        return writeScaled(BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false, target)
    }

    fun hasPspIcon(gameId: String): Boolean = File(directory, "ppsspp-${gameId.lowercase()}.jpg").isFile

    fun hasCustomCover(gameKey: String): Boolean = customFile(gameKey).isFile

    /** Stores the picture at [uri] as [gameKey]'s cover on this device. */
    suspend fun setCustomCover(gameKey: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { input -> readAtMost(input, MAX_PICKED_BYTES) }
        }.getOrNull() ?: return@withContext false
        val bitmap = decodeScaled(bytes) ?: return@withContext false
        writeScaled(bitmap, customFile(gameKey)).also { if (it) revision++ }
    }

    suspend fun clearCustomCover(gameKey: String) = withContext(Dispatchers.IO) {
        if (customFile(gameKey).delete()) revision++
    }

    /** The whole stream, or null when it is longer than [limit]. */
    private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray? {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return output.toByteArray()
            if (output.size() + read > limit) return null
            output.write(buffer, 0, read)
        }
    }

    private fun customFile(gameKey: String) = File(directory, "custom-${Hashing.sha256(gameKey).take(24)}.jpg")

    private fun download(cacheKey: String, urls: List<String>): Bitmap? {
        val target = File(directory, "$cacheKey.jpg")
        decode(target)?.let { return it }
        if (!directory.exists() && !directory.mkdirs()) return null
        val temporary = File(directory, ".$cacheKey.part")
        val bitmap = urls.firstNotNullOfOrNull { url ->
            if (download(url, temporary)) decode(temporary) else null
        }
        temporary.delete()
        // eShop icons are 1024px JPEGs of about 1 MB; keep only what a row needs.
        bitmap?.let { writeScaled(it, target) }
        return bitmap
    }

    /**
     * The Steam AppID whose store name equals [name], from a store search
     * cached for a week either way so an unmatched name is not searched on
     * every scroll.
     */
    private fun steamAppIdByName(name: String): String? {
        val normalized = GameArtworkSources.normalizedTitle(name).takeIf(String::isNotBlank) ?: return null
        val cache = File(steamSearchDirectory, Hashing.sha256(normalized).take(24))
        if (cache.isFile && System.currentTimeMillis() - cache.lastModified() < SEARCH_CACHE_MILLIS) {
            return cache.readText().trim().takeIf(String::isNotEmpty)
        }
        val url = "https://store.steampowered.com/api/storesearch/".toHttpUrl().newBuilder()
            .addQueryParameter("term", name)
            .addQueryParameter("l", "english")
            .addQueryParameter("cc", "US")
            .build()
        val body = runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", "OpenSave Android").build()).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrNull() ?: return null // offline: try again next time
        val appId = GameArtworkSources.steamAppIdForExactName(body, name)
        runCatching {
            steamSearchDirectory.mkdirs()
            cache.writeText(appId.orEmpty())
        }
        return appId
    }

    private fun writeScaled(bitmap: Bitmap, target: File): Boolean {
        if (!directory.exists() && !directory.mkdirs()) return false
        val temporary = File(directory, ".${target.name}.part")
        val saved = runCatching {
            FileOutputStream(temporary).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        }.getOrDefault(false)
        if (!saved) {
            temporary.delete()
            return false
        }
        if (target.exists()) target.delete()
        return temporary.renameTo(target).also { if (!it) temporary.delete() }
    }

    private fun download(url: String, destination: File): Boolean = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OpenSave Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use false
            val body = response.body ?: return@use false
            if (body.contentLength() > MAX_COVER_BYTES) return@use false
            var validDownload = true
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > MAX_COVER_BYTES) {
                            validDownload = false
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            validDownload
        }
    }.getOrDefault(false)

    /** The launcher icon of an installed app, or null when it is not installed or not visible. */
    private fun appIcon(packageName: String): Bitmap? = runCatching {
        val drawable = packageManager.getApplicationIcon(packageName)
        Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
            drawable.draw(Canvas(bitmap))
        }
    }.getOrNull()

    /** Decodes [file] with its shorter side reduced to no less than [MAX_EDGE] pixels. */
    private fun decode(file: File): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = sampleSize(bounds) ?: return null
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = sampleSize(bounds) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun sampleSize(bounds: BitmapFactory.Options): Int? {
        val shortEdge = minOf(bounds.outWidth, bounds.outHeight)
        if (shortEdge <= 0) return null
        var sample = 1
        while (shortEdge / (sample * 2) >= MAX_EDGE) sample *= 2
        return sample
    }

    companion object {
        /** Bumped when a picked cover changes, so artwork already on screen reloads. */
        var revision by mutableIntStateOf(0)
            private set

        fun bumpRevision() {
            revision++
        }

        private const val MAX_PICKED_BYTES = 20 * 1024 * 1024
        private const val SEARCH_CACHE_MILLIS = 7L * 24L * 60L * 60L * 1000L
        private const val BUFFER_SIZE = 16 * 1024
        private const val MAX_COVER_BYTES = 4L * 1024L * 1024L
        private const val TIMEOUT_MS = 15L * 1000L
        private const val MAX_EDGE = 256
        private const val ICON_SIZE = 192
        private const val JPEG_QUALITY = 88
    }
}
