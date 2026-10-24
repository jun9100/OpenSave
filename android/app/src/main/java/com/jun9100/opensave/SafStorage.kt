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
    val detected: List<DetectedSave> = emptyList(),
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

    suspend fun scanTree(uri: Uri): Result<SaveScanReport> = withContext(Dispatchers.IO) {
        runCatching {
            val root = DocumentFile.fromTreeUri(context, uri)
                ?: error("The selected storage provider is unavailable")
            require(root.isDirectory) { "The selected URI is not a directory" }

            val rootName = PathPolicy.requireSafeDocumentName(root.name ?: "authorized-storage")
            val directories = mutableListOf<String>()
            val unreadable = intArrayOf(0)
            collectScanDirectories(root, rootName, directories, unreadable)
            val truncated = directories.size > MAX_SCAN_DIRECTORIES
            SaveSourceDetector.scanDirectories(
                rootPath = rootName,
                directoryPaths = directories.take(MAX_SCAN_DIRECTORIES),
                selectedRootName = rootName,
                unreadableDirectoryCount = unreadable[0],
                truncated = truncated,
            )
        }
    }

    suspend fun copyGameLocations(
        uri: Uri,
        locations: List<GameSnapshotLocation>,
        destinationRoot: File,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = DocumentFile.fromTreeUri(context, uri)
                ?: error("The selected storage provider is unavailable")
            require(root.isDirectory) { "The selected URI is not a directory" }
            require(locations.isNotEmpty()) { "游戏没有可备份的存档位置" }
            if (destinationRoot.exists() && !destinationRoot.deleteRecursively()) {
                error("Could not clear temporary game workspace")
            }
            require(destinationRoot.mkdirs() || destinationRoot.isDirectory) {
                "Could not create temporary game workspace"
            }
            locations.forEach { location ->
                val source = resolveDirectory(root, location.relativePath)
                val destination = File(destinationRoot, PathPolicy.requireSafeDocumentName(location.archivePath))
                require(destination.mkdirs() || destination.isDirectory) { "Could not stage ${location.relativePath}" }
                copyDirectory(source, destination, "", mutableListOf())
            }
        }
    }

    suspend fun restoreGameLocations(
        uri: Uri,
        locations: List<GameSnapshotLocation>,
        extractedRoot: File,
        safetyRoot: File,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = DocumentFile.fromTreeUri(context, uri)
                ?: error("The selected storage provider is unavailable")
            require(root.isDirectory) { "The selected URI is not a directory" }
            val targets = locations.map { location ->
                val target = resolveDirectory(root, location.relativePath)
                val archivePath = PathPolicy.requireSafeDocumentName(location.archivePath)
                val staged = File(extractedRoot, archivePath)
                val safety = File(safetyRoot, archivePath)
                require(staged.isDirectory && safety.isDirectory) { "游戏快照或安全副本不完整" }
                Triple(target, staged, safety)
            }
            try {
                targets.forEach { (target, staged, _) ->
                    deleteChildren(target)
                    copyLocalDirectory(staged, target)
                }
            } catch (error: Throwable) {
                val rollbackErrors = mutableListOf<Throwable>()
                targets.forEach { (target, _, safety) ->
                    runCatching {
                        deleteChildren(target)
                        copyLocalDirectory(safety, target)
                    }.onFailure(rollbackErrors::add)
                }
                if (rollbackErrors.isNotEmpty()) {
                    throw IllegalStateException(
                        "SAF 恢复失败，自动回滚也有 ${rollbackErrors.size} 个位置失败；安全副本仍保存在应用内",
                        error,
                    )
                }
                throw error
            }
        }
    }

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
                detected = SaveSourceDetector.detect(workspace, root.name),
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
                detected = SaveSourceDetector.detect(workspace),
            )
        }
    }

    suspend fun replaceTreeFromWorkspace(uri: Uri): Result<CopyReport> = withContext(Dispatchers.IO) {
        runCatching {
            val root = DocumentFile.fromTreeUri(context, uri)
                ?: error("The selected storage provider is unavailable")
            require(root.isDirectory) { "The selected URI is not a directory" }
            val workspace = workspaceDirectory()
            require(workspace.isDirectory) { "Restore a snapshot before writing it back" }

            deleteChildren(root)
            val files = mutableListOf<FileRecord>()
            writeDirectory(root, workspace, workspace, files)
            CopyReport(
                fileCount = files.size,
                byteCount = files.sumOf { it.sizeBytes },
                manifestHash = manifestHash(files),
                files = files,
                detected = SaveSourceDetector.detect(workspace),
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

    private fun collectScanDirectories(
        source: DocumentFile,
        relativePath: String,
        directories: MutableList<String>,
        unreadable: IntArray,
    ) {
        directories += relativePath
        if (directories.size > MAX_SCAN_DIRECTORIES) return
        val children = runCatching { source.listFiles().toList() }.getOrElse {
            unreadable[0]++
            return
        }
        children.filter { it.isDirectory }.sortedBy { it.name.orEmpty() }.forEach { child ->
            if (directories.size > MAX_SCAN_DIRECTORIES) return@forEach
            val name = runCatching { PathPolicy.requireSafeDocumentName(child.name ?: error("Document has no name")) }
                .getOrElse {
                    unreadable[0]++
                    return@forEach
                }
            collectScanDirectories(child, "$relativePath/$name", directories, unreadable)
        }
    }

    private fun resolveDirectory(root: DocumentFile, rawRelativePath: String): DocumentFile {
        val relativePath = GameSnapshotManifest.safeRelativePath(rawRelativePath)
        return relativePath.split('/').fold(root) { parent, name ->
            val child = parent.findFile(name) ?: error("存档位置不存在：$relativePath")
            require(child.isDirectory) { "存档位置不是目录：$relativePath" }
            child
        }
    }

    private fun copyLocalDirectory(source: File, destination: DocumentFile) {
        source.listFiles()?.sortedBy { it.name }?.forEach { child ->
            val name = PathPolicy.requireSafeDocumentName(child.name)
            if (child.isDirectory) {
                val directory = findOrCreateDirectory(destination, name)
                copyLocalDirectory(child, directory)
            } else if (child.isFile) {
                val target = destination.findFile(name)?.also {
                    require(!it.isDirectory) { "Cannot overwrite directory $name" }
                } ?: destination.createFile("application/octet-stream", name)
                    ?: error("Could not create $name")
                val output = context.contentResolver.openOutputStream(target.uri, "wt")
                    ?: error("Could not write $name")
                output.use { stream ->
                    FileInputStream(child).use { input -> input.copyTo(stream, BUFFER_SIZE) }
                    stream.flush()
                }
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

    private fun deleteChildren(parent: DocumentFile) {
        for (child in parent.listFiles()) {
            if (child.isDirectory) deleteChildren(child)
            require(child.delete()) { "Could not remove ${child.name ?: "selected item"}" }
        }
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
        const val MAX_SCAN_DIRECTORIES = 50_000
    }
}
