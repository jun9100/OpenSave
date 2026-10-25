package com.jun9100.opensave

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Hashing {
    private const val BUFFER_SIZE = 1024 * 1024

    fun sha256(file: File): String = file.inputStream().use(::sha256)

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
        }
        return digest.toHex()
    }

    fun sha256(value: String): String =
        sha256(value.toByteArray(Charsets.UTF_8))

    fun sha256(value: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(value)
        return digest.toHex()
    }

    fun manifestHash(files: Iterable<FileRecord>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.relativePath }.forEach { file ->
            digest.update(file.relativePath.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(file.sizeBytes.toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(file.sha256.toByteArray(Charsets.US_ASCII))
            digest.update(0)
        }
        return digest.toHex()
    }

    private fun MessageDigest.toHex(): String =
        digest().joinToString("") { byte -> "%02x".format(byte) }
}
