package com.jun9100.opensave

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

data class FileRecord(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class CopyReport(
    val fileCount: Int,
    val byteCount: Long,
    val manifestHash: String,
    val files: List<FileRecord>,
)

class SafStorage(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun persistedTreeUri(): Uri? {
        return preferences.getString(TREE_URI_KEY, null)?.let(Uri::parse)
    }

    fun persistTreePermission(uri: Uri): Result<Unit> {
        return runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            preferences.edit().putString(TREE_URI_KEY, uri.toString()).apply()
        }
    }

    fun hasReadWritePermission(uri: Uri): Boolean {
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri &&
                it.isReadPermission &&
                it.isWritePermission
        }
    }

    fun workspaceDirectory(): File = File(context.filesDir, WORKSPACE_DIRECTORY)

    suspend fun importTree(uri: Uri): Result<CopyReport> = withContext(Dispatchers.IO) {
        runCatching {
            val root = DocumentFile.fromTreeUri(context, uri)
                ?: error("The selected storage provider is unavailable")
            require(root.isDirectory) { "The selected URI is not a directory" }

            val workspace = workspaceDirectory()
            if (workspace.exists() && !workspace.deleteRecursively()) {
                error("Could not clear the previous workspace")
            }
            require(workspace.mkdirs() || workspace.isDirectory) {
                "Could not create the private workspace"
            }

            val files = mutableListOf<FileRecord>()
            copyDirectory(root, workspace, "", files)
            val manifestHash = manifestHash(files)
            CopyReport(
                fileCount = files.size,
                byteCount = files.sumOf { it.sizeBytes },
                manifestHash = manifestHash,
                files = files,
            )
        }
    }

    suspend fun restoreWorkspace(uri: Uri): Result<CopyReport> = withContext(Dispatchers.IO) {
        runCatching {
            val root = DocumentFile.fromTreeUri(context, uri)
                ?: error("The selected storage provider is unavailable")
            require(root.isDirectory) { "The selected URI is not a directory" }
            val workspace = workspaceDirectory()
            require(workspace.isDirectory) { "Import a source directory before restoring it" }

            val files = mutableListOf<FileRecord>()
            writeDirectory(root, workspace, workspace, files)
            CopyReport(
                fileCount = files.size,
                byteCount = files.sumOf { it.sizeBytes },
                manifestHash = manifestHash(files),
                files = files,
            )
        }
    }

    private fun copyDirectory(
        source: DocumentFile,
        destination: File,
        prefix: String,
        files: MutableList<FileRecord>,
    ) {
        val children = source.listFiles().sortedBy { it.name.orEmpty() }
        for (child in children) {
            val name = PathPolicy.requireSafeDocumentName(child.name ?: error("Document has no name"))
            val relativePath = if (prefix.isEmpty()) name else "$prefix/$name"
            val target = File(destination, name)
            if (child.isDirectory) {
                require(target.mkdirs() || target.isDirectory) { "Could not create $relativePath" }
                copyDirectory(child, target, relativePath, files)
            } else if (child.isFile) {
                copyFile(child, target, relativePath, files)
            }
        }
    }

    private fun copyFile(
        source: DocumentFile,
        target: File,
        relativePath: String,
        files: MutableList<FileRecord>,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
        val input = context.contentResolver.openInputStream(source.uri)
            ?: error("Could not read $relativePath")
        input.use { stream ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    byteCount += read
                }
                output.fd.sync()
            }
        }
        files += FileRecord(relativePath, byteCount, digest.hex())
    }

    private fun writeDirectory(
        destination: DocumentFile,
        source: File,
        workspaceRoot: File,
        files: MutableList<FileRecord>,
    ) {
        val children = source.listFiles()?.sortedBy { it.name } ?: return
        for (child in children) {
            val name = PathPolicy.requireSafeDocumentName(child.name)
            val relativePath = child.relativeTo(workspaceRoot).invariantSeparatorsPath
            if (child.isDirectory) {
                val directory = findOrCreateDirectory(destination, name)
                writeDirectory(directory, child, workspaceRoot, files)
            } else if (child.isFile) {
                val target = destination.findFile(name)?.also {
                    require(!it.isDirectory) { "Cannot overwrite directory $relativePath" }
                } ?: destination.createFile("application/octet-stream", name)
                    ?: error("Could not create $relativePath")
                val digest = MessageDigest.getInstance("SHA-256")
                var byteCount = 0L
                val output = context.contentResolver.openOutputStream(target.uri, "wt")
                    ?: error("Could not write $relativePath")
                output.use { stream ->
                    FileInputStream(child).use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            stream.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            byteCount += read
                        }
                    }
                    stream.flush()
                }
                files += FileRecord(relativePath, byteCount, digest.hex())
            }
        }
    }

    private fun findOrCreateDirectory(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing != null) {
            require(existing.isDirectory) { "Cannot overwrite file $name" }
            return existing
        }
        return parent.createDirectory(name) ?: error("Could not create directory $name")
    }

    private fun manifestHash(files: List<FileRecord>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.relativePath }.forEach { file ->
            digest.update(file.relativePath.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(file.sizeBytes.toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(file.sha256.toByteArray(Charsets.US_ASCII))
            digest.update(0)
        }
        return digest.hex()
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

    private companion object {
        const val PREFERENCES = "opensave_mobile"
        const val TREE_URI_KEY = "tracked_tree_uri"
        const val WORKSPACE_DIRECTORY = "source-workspace"
        const val BUFFER_SIZE = 1024 * 1024
    }
}
