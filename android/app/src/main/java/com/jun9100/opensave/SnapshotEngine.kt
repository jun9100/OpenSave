package com.jun9100.opensave

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class SnapshotBuild(
    val archive: File,
    val fileCount: Int,
    val byteCount: Long,
    val archiveSha256: String,
    val skipped: List<String>,
)

/** Streaming ZIP implementation matching OpenSave's portable snapshot format. */
class SnapshotEngine {
    fun createSnapshot(workspace: File, output: File): Result<SnapshotBuild> = runCatching {
        require(workspace.isDirectory) { "Import a source directory before creating a snapshot" }
        require(output.parentFile?.exists() == true || output.parentFile?.mkdirs() == true) {
            "Could not create snapshot directory"
        }

        val temporary = File(output.parentFile, ".${output.name}.${UUID.randomUUID()}.part")
        val files = mutableListOf<FileRecord>()
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { zip ->
                archiveDirectory(workspace, workspace, zip, files)
            }
            require(temporary.renameTo(output)) { "Could not publish snapshot archive" }
        } finally {
            temporary.delete()
        }

        SnapshotBuild(
            archive = output,
            fileCount = files.size,
            byteCount = files.sumOf { it.sizeBytes },
            archiveSha256 = Hashing.sha256(output),
            skipped = emptyList(),
        )
    }

    fun restoreSnapshot(archive: File, target: File): Result<Unit> = runCatching {
        require(archive.isFile) { "Snapshot archive does not exist" }
        val parent = target.absoluteFile.parentFile ?: error("Snapshot target has no parent")
        require(parent.exists() || parent.mkdirs()) { "Could not create restore parent" }
        val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.restore")
        require(temporary.mkdirs()) { "Could not create restore workspace" }
        try {
            extractArchive(archive, temporary)
            if (target.exists() && !target.deleteRecursively()) {
                error("Could not replace restore workspace")
            }
            require(temporary.renameTo(target)) { "Could not publish restore workspace" }
        } finally {
            temporary.deleteRecursively()
        }
    }

    private fun archiveDirectory(
        root: File,
        directory: File,
        zip: ZipOutputStream,
        files: MutableList<FileRecord>,
    ) {
        val children = directory.listFiles()?.sortedBy { it.name } ?: return
        val rootPath = root.canonicalPath + File.separator
        for (child in children) {
            require(child.canonicalPath.startsWith(rootPath)) {
                "Refusing a linked file outside the workspace: ${child.name}"
            }
            val name = PathPolicy.requireSafeDocumentName(child.name)
            val relative = child.relativeTo(root).invariantSeparatorsPath
            if (child.isDirectory) {
                val directoryEntry = ZipEntry("$relative/")
                directoryEntry.time = child.lastModified()
                zip.putNextEntry(directoryEntry)
                zip.closeEntry()
                archiveDirectory(root, child, zip, files)
            } else if (child.isFile) {
                val record = streamFileToZip(child, relative, zip)
                files += record
            }
        }
    }

    private fun streamFileToZip(file: File, relative: String, zip: ZipOutputStream): FileRecord {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        val entry = ZipEntry(relative)
        entry.time = file.lastModified()
        zip.putNextEntry(entry)
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                zip.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                bytes += read
            }
        }
        zip.closeEntry()
        return FileRecord(relative, bytes, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun extractArchive(archive: File, target: File) {
        val root = target.canonicalFile
        val rootPrefix = root.path + File.separator
        val directoryTimes = mutableListOf<Pair<File, Long>>()
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val relative = entry.name.replace('\\', '/')
                require(isSafeArchivePath(relative)) { "Unsafe snapshot path: ${entry.name}" }
                val destination = File(root, relative).canonicalFile
                require(destination.path == root.path || destination.path.startsWith(rootPrefix)) {
                    "Snapshot path escapes restore workspace"
                }
                if (entry.isDirectory) {
                    require(destination.mkdirs() || destination.isDirectory) {
                        "Could not create ${entry.name}"
                    }
                    entry.time.takeIf { it > 0L }?.let { time -> directoryTimes += destination to time }
                } else {
                    require(destination.parentFile?.let { it.isDirectory || it.mkdirs() } != false) {
                        "Could not create parent for ${entry.name}"
                    }
                    BufferedOutputStream(FileOutputStream(destination)).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                        }
                    }
                    entry.time.takeIf { it > 0L }?.let { time ->
                        require(destination.setLastModified(time)) { "Could not preserve ${entry.name} modification time" }
                    }
                }
                input.closeEntry()
            }
        }
        // Child creation changes directory mtimes, so restore them only after
        // every ZIP entry is in place. This keeps the next backup from
        // mistaking a cross-device restore for newly created progress.
        directoryTimes.asReversed().forEach { (directory, time) ->
            require(directory.setLastModified(time)) { "Could not preserve ${directory.name} modification time" }
        }
    }

    private fun isSafeArchivePath(path: String): Boolean {
        if (path.isEmpty() || path.startsWith('/') || path.contains('\u0000')) return false
        val components = path.trimEnd('/').split('/')
        return components.isNotEmpty() && components.all { PathPolicy.isSafeDocumentName(it) }
    }

    private companion object {
        const val BUFFER_SIZE = 1024 * 1024
    }
}

object SnapshotNaming {
    fun sourceId(treeUri: String): String = "android-${Hashing.sha256(treeUri).take(16)}"

    fun remoteName(sourceId: String, timestampMillis: Long): String =
        "${sanitize(sourceId)}__main__snap_$timestampMillis.zip"

    fun remotePrefix(sourceId: String): String = "${sanitize(sourceId)}__main__snap_"

    fun gameRemoteName(sourceId: String, gameKey: String, timestampMillis: Long): String =
        "${sanitize(sourceId)}__game-${Hashing.sha256(gameKey).take(12)}__snap_$timestampMillis.zip"

    fun gameRemotePrefix(sourceId: String, gameKey: String): String =
        "${sanitize(sourceId)}__game-${Hashing.sha256(gameKey).take(12)}__snap_"

    private fun sanitize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-').ifEmpty { "android-source" }
}
