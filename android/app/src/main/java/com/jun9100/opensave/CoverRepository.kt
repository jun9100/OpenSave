package com.jun9100.opensave

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Covers for the library: an Android game's installed launcher icon, or a
 * Steam / Switch cover downloaded once, scaled down and kept in app-private
 * storage.
 */
class CoverRepository(context: Context) {
    private val packageManager = context.packageManager
    private val directory = File(context.filesDir, "covers")
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    suspend fun load(presentation: GamePresentation): Bitmap? = withContext(Dispatchers.IO) {
        if (presentation.coverUrls.isEmpty()) {
            return@withContext presentation.androidPackageName?.let(::appIcon)
        }
        val cacheKey = presentation.coverCacheKey ?: return@withContext null
        val target = File(directory, "$cacheKey.jpg")
        decode(target)?.let { return@withContext it }

        if (!directory.exists() && !directory.mkdirs()) return@withContext null
        val temporary = File(directory, ".$cacheKey.part")
        val bitmap = presentation.coverUrls.firstNotNullOfOrNull { url ->
            if (download(url, temporary)) decode(temporary) else null
        }
        temporary.delete()
        if (bitmap == null) return@withContext presentation.androidPackageName?.let(::appIcon)

        // eShop icons are 1024px JPEGs of about 1 MB; keep only what a row needs.
        val saved = runCatching {
            FileOutputStream(temporary).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        }.getOrDefault(false)
        if (saved) {
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) temporary.delete()
        } else {
            temporary.delete()
        }
        bitmap
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
        val shortEdge = minOf(bounds.outWidth, bounds.outHeight)
        if (shortEdge <= 0) return null
        var sample = 1
        while (shortEdge / (sample * 2) >= MAX_EDGE) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
        const val MAX_COVER_BYTES = 4L * 1024L * 1024L
        const val TIMEOUT_MS = 15L * 1000L
        const val MAX_EDGE = 256
        const val ICON_SIZE = 192
        const val JPEG_QUALITY = 88
    }
}
