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

/** A durable system-granted tree that can participate in the game library. */
data class AuthorizedSafTree(
    val uri: Uri,
    val label: String,
    val readable: Boolean,
    val writable: Boolean,
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
        val active = preferences.getString(TREE_URI_KEY, null)
            ?.let { value -> runCatching { Uri.parse(value) }.getOrNull() }
        return active?.takeIf { uri -> persistedTreeUris().any { it == uri } }
            ?: persistedTreeUris().firstOrNull()
    }

    fun persistedTreeUris(): List<Uri> {
        val current = preferences.getStringSet(TREE_URIS_KEY, null).orEmpty()
        val legacy = preferences.getString(TREE_URI_KEY, null)
        return (current + listOfNotNull(legacy))
            .asSequence()
            .mapNotNull { value -> runCatching { Uri.parse(value) }.getOrNull() }
            .distinct()
            .toList()
    }

    fun persistedTrees(): List<AuthorizedSafTree> = persistedTreeUris()
        .map { uri ->
            AuthorizedSafTree(
                uri = uri,
                label = treeLabel(uri),
                readable = hasReadPermission(uri),
                writable = hasWritePermission(uri),
            )
        }
        .sortedBy { it.label.lowercase() }

    fun persistTreePermission(uri: Uri): Result<Unit> {
        return runCatching {
            val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, readWriteFlags)
            }.getOrElse {
                // Read-only providers can still supply an offline backup source.
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val savedUris = persistedTreeUris().mapTo(linkedSetOf()) { it.toString() }
            savedUris += uri.toString()
            preferences.edit()
                .putStringSet(TREE_URIS_KEY, savedUris)
                // Whole-source backup keeps targeting the last directory selected.
                .putString(TREE_URI_KEY, uri.toString())
                .apply()
        }
    }

    fun forgetTree(uri: Uri): Result<Unit> = runCatching {
        val active = persistedTreeUri()
        val remaining = persistedTreeUris().filterNot { it == uri }
        val editor = preferences.edit()
        if (remaining.isEmpty()) {
            editor.remove(TREE_URIS_KEY)
        } else {
            editor.putStringSet(TREE_URIS_KEY, remaining.mapTo(linkedSetOf()) { it.toString() })
        }
        if (active == uri) {
            remaining.firstOrNull()?.let { editor.putString(TREE_URI_KEY, it.toString()) }
                ?: editor.remove(TREE_URI_KEY)
        }
        editor.apply()

        val flags = buildPermissionFlags(uri)
        if (flags != 0) context.contentResolver.releasePersistableUriPermission(uri, flags)
    }

    fun hasReadPermission(uri: Uri): Boolean = persistedPermission(uri)?.isReadPermission == true

    fun hasWritePermission(uri: Uri): Boolean = persistedPermission(uri)?.isWritePermission == true

    fun hasReadWritePermission(uri: Uri): Boolean = hasReadPermission(uri) && hasWritePermission(uri)

    fun workspaceDirectory(): File = File(context.filesDir, WORKSPACE_DIRECTORY)

    suspend fun scanTree(
        uri: Uri,
        androidGames: Map<String, AndroidGameMetadata> = emptyMap(),
    ): Result<SaveScanReport> = withContext(Dispatchers.IO) {
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
                androidGames = androidGames,
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

    private fun persistedPermission(uri: Uri) = context.contentResolver.persistedUriPermissions
        .firstOrNull { permission -> permission.uri == uri }

    private fun buildPermissionFlags(uri: Uri): Int {
        val permission = persistedPermission(uri) ?: return 0
        return (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
    }

    private fun treeLabel(uri: Uri): String {
        if (uri.authority == "dev.eden.eden_emulator.user") return "Eden 用户数据（系统授权）"
        val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name?.trim() }.getOrNull()
        return name?.takeIf { it.isNotBlank() && !it.equals("root", ignoreCase = true) }
            ?.let { "系统授权：$it" }
            ?: "系统授权目录"
    }

    private companion object {
        const val PREFERENCES = "opensave_mobile"
        const val TREE_URI_KEY = "tracked_tree_uri"
        const val TREE_URIS_KEY = "tracked_tree_uris"
        const val WORKSPACE_DIRECTORY = "source-workspace"
        const val BUFFER_SIZE = 1024 * 1024
        const val MAX_SCAN_DIRECTORIES = 50_000
    }
}
