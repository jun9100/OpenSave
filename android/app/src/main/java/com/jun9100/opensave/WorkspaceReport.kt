package com.jun9100.opensave

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

/** Builds the same manifest used by SAF after a Root copy reaches the workspace. */
object WorkspaceReport {
    fun build(workspace: File, selectedRootName: String? = null): CopyReport {
        require(workspace.isDirectory) { "The private workspace is unavailable" }
        val files = mutableListOf<FileRecord>()
        collect(workspace.canonicalFile, workspace.canonicalFile, files)
        return CopyReport(
            fileCount = files.size,
            byteCount = files.sumOf { it.sizeBytes },
            manifestHash = Hashing.manifestHash(files),
            files = files,
            detected = SaveSourceDetector.detect(workspace, selectedRootName),
        )
    }

    private fun collect(root: File, directory: File, files: MutableList<FileRecord>) {
        val rootPrefix = root.path + File.separator
        directory.listFiles()?.sortedBy { it.name }?.forEach { child ->
            require(!Files.isSymbolicLink(child.toPath())) {
                "Symbolic links are not supported in a Root source: ${child.name}"
            }
            require(child.canonicalPath.startsWith(rootPrefix)) {
                "Refusing a linked file outside the workspace: ${child.name}"
            }
            val relativePath = child.relativeTo(root).invariantSeparatorsPath
            if (child.isDirectory) {
                collect(root, child, files)
            } else if (child.isFile) {
                files += digestFile(child, relativePath)
            } else {
                error("Unsupported special file in workspace: $relativePath")
            }
        }
    }

    private fun digestFile(file: File, relativePath: String): FileRecord {
        var byteCount = 0L
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                byteCount += read
            }
        }
        return FileRecord(relativePath, byteCount, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private const val BUFFER_SIZE = 1024 * 1024
}
