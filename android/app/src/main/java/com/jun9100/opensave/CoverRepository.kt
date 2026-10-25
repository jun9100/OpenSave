package com.jun9100.opensave

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Downloads Steam and Switch title covers, then keeps them in app-private storage. */
class CoverRepository(context: Context) {
    private val directory = File(context.filesDir, "covers")
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    suspend fun load(presentation: GamePresentation): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = presentation.coverCacheKey ?: return@withContext null
        val coverUrl = presentation.coverUrl ?: return@withContext null
        val target = File(directory, "$cacheKey.jpg")
        decode(target)?.let { return@withContext it }

        if (!directory.exists() && !directory.mkdirs()) return@withContext null
        val temporary = File(directory, ".$cacheKey.part")
        val downloaded = runCatching {
            val request = Request.Builder()
                .url(coverUrl)
                .header("User-Agent", "OpenSave Android")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val body = response.body ?: return@use false
                if (body.contentLength() > MAX_COVER_BYTES) return@use false
                var validDownload = true
                body.byteStream().use { input ->
                    FileOutputStream(temporary).use { output ->
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

        if (!downloaded) {
            temporary.delete()
            return@withContext null
        }
        val bitmap = decode(temporary)
        if (bitmap == null) {
            temporary.delete()
            return@withContext null
        }
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) temporary.delete()
        bitmap
    }

    private fun decode(file: File): Bitmap? =
        if (file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
        const val MAX_COVER_BYTES = 4L * 1024L * 1024L
        const val TIMEOUT_MS = 15L * 1000L
    }
}
