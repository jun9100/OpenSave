package com.jun9100.opensave

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class SnapshotRecord(
    val id: String,
    val sourceId: String,
    val remoteName: String,
    val localPath: String,
    val createdAtMillis: Long,
    val sizeBytes: Long,
    val sha256: String,
    val manifestHash: String,
    val uploaded: Boolean = false,
    val comment: String = "",
    val gameKey: String? = null,
    val gameName: String = "",
    val saveType: String = GameSaveTypes.PC.id,
    val cloudGameId: String = "",
    /** [ContentHash] of the captured saves; blank for safety copies and records from older builds. */
    val contentHash: String = "",
    /** [ContentHash.VERSION] that produced [contentHash]; 0 when there is none. */
    val hashVersion: Int = 0,
    /** Encoded [SaveSignature] of the game's locations when it was captured; blank when unknown. */
    val saveSignature: String = "",
    /**
     * Uploaded, but not as the game's current cloud save: kept as a native
     * conflict copy, or a legacy ZIP whose head could not be advanced. Never
     * shown as "已同步".
     */
    val notCloudHead: Boolean = false,
) {
    /** A pre-restore / pre-write-back copy, not a backup the user made; it carries a comment. */
    val isSafetyCopy: Boolean get() = comment.isNotBlank()
}

/**
 * Small atomic catalog; the archives remain ordinary files and are never put in preferences.
 *
 * The ViewModel and the auto-backup worker share one instance per directory
 * ([shared]); every read-modify-write runs under a lock keyed by the canonical
 * directory, so even separately constructed instances cannot interleave writes.
 */
class SnapshotRepository(private val root: File) {
    private val indexFile = File(root, INDEX_FILE_NAME)
    private val lock: Any = locks.computeIfAbsent(root.absoluteFile.normalize().path) { Any() }

    init {
        require(root.exists() || root.mkdirs()) { "Could not create snapshot directory" }
        synchronized(lock) { deleteAbandonedTemporaryIndexes() }
    }

    fun list(): List<SnapshotRecord> = synchronized(lock) {
        readRecords().sortedByDescending { it.createdAtMillis }
    }

    fun add(record: SnapshotRecord) = synchronized(lock) {
        val records = readRecords().filterNot { it.id == record.id } + record
        writeRecords(records)
    }

    /** [asCloudHead] false: the archive reached game-save without becoming its current save. */
    fun markUploaded(id: String, uploaded: Boolean = true, asCloudHead: Boolean = true) = synchronized(lock) {
        val records = readRecords().map { record ->
            if (record.id == id) record.copy(uploaded = uploaded, notCloudHead = uploaded && !asCloudHead) else record
        }
        writeRecords(records)
    }

    /** Remembers what the save locations looked like when [id] was captured or last matched. */
    fun setSaveSignature(id: String, signature: String) = synchronized(lock) {
        val records = readRecords()
        if (records.none { it.id == id && it.saveSignature != signature }) return@synchronized
        writeRecords(records.map { record -> if (record.id == id) record.copy(saveSignature = signature) else record })
    }

    /**
     * The snapshot a backup of unchanged content should reuse instead of
     * writing a new one: the newest ordinary (non-safety) snapshot of this game
     * from this source, if its archive still exists and it holds exactly
     * [contentHash]. Only the newest counts, so content that changes back to an
     * older state still gets a new version on the timeline.
     *
     * [currentManifestHash] is the manifest hash of the capture asking. A
     * record from an older build has no content hash; when its
     * [SnapshotRecord.manifestHash] equals it (same data and same manifest) it
     * is the same content, and the hash is filled in on it. A record captured
     * by another path (manual backup vs. worker) can hold the same content
     * under another manifest hash; it is rebased onto [currentManifestHash],
     * because the sync head published from it is later compared against this
     * path's manifest hash and would otherwise read as a local change.
     */
    fun findReusableRecord(
        sourceId: String,
        gameKey: String,
        contentHash: String,
        currentManifestHash: String? = null,
    ): SnapshotRecord? = synchronized(lock) {
        if (contentHash.isBlank()) return@synchronized null
        val latest = readRecords()
            .filter { it.sourceId == sourceId && it.gameKey == gameKey && !it.isSafetyCopy }
            .maxByOrNull { it.createdAtMillis }
            ?: return@synchronized null
        if (!fileFor(latest).isFile) return@synchronized null
        val reused = when {
            latest.hashVersion == ContentHash.VERSION && latest.contentHash == contentHash -> latest
            latest.hashVersion == 0 && currentManifestHash != null && latest.manifestHash == currentManifestHash ->
                latest.copy(contentHash = contentHash, hashVersion = ContentHash.VERSION)
            else -> return@synchronized null
        }.let { record ->
            if (currentManifestHash.isNullOrBlank() || record.manifestHash == currentManifestHash) record
            else record.copy(manifestHash = currentManifestHash)
        }
        if (reused != latest) {
            writeRecords(readRecords().map { if (it.id == latest.id) reused else it })
        }
        reused
    }

    fun fileFor(record: SnapshotRecord): File = File(
        root,
        PathPolicy.requireSafeDocumentName(record.localPath),
    )

    private fun readRecords(): List<SnapshotRecord> {
        if (!indexFile.isFile) return emptyList()
        return indexFile.useLines { lines ->
            lines.mapNotNull { line -> decode(line) }.toList()
        }
    }

    /**
     * Writes a complete temporary index, flushes it to disk and publishes it
     * with one atomic rename over the old index. A process killed at any point
     * leaves either the previous or the new index, never neither.
     */
    private fun writeRecords(records: List<SnapshotRecord>) {
        val temporary = File(root, "$TEMPORARY_PREFIX${System.nanoTime()}$TEMPORARY_SUFFIX")
        try {
            FileOutputStream(temporary).use { output ->
                val writer = output.bufferedWriter(Charsets.UTF_8)
                records.sortedBy { it.createdAtMillis }.forEach { record ->
                    writer.appendLine(encode(record))
                }
                writer.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    indexFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Same-directory renames are atomic on Android; this only guards
                // unusual test filesystems. rename(2) still replaces in one step.
                require(temporary.renameTo(indexFile)) { "Could not publish snapshot catalog" }
            }
        } finally {
            temporary.delete()
        }
    }

    private fun deleteAbandonedTemporaryIndexes() {
        root.listFiles { file ->
            file.isFile && file.name.startsWith(TEMPORARY_PREFIX) && file.name.endsWith(TEMPORARY_SUFFIX)
        }?.forEach { it.delete() }
    }

    private fun encode(record: SnapshotRecord): String = listOf(
        record.id,
        record.sourceId,
        record.remoteName,
        record.localPath,
        record.createdAtMillis.toString(),
        record.sizeBytes.toString(),
        record.sha256,
        record.manifestHash,
        record.uploaded.toString(),
        record.comment,
        record.gameKey.orEmpty(),
        record.gameName,
        record.saveType,
        record.cloudGameId,
        record.contentHash,
        record.hashVersion.toString(),
        record.saveSignature,
        record.notCloudHead.toString(),
    ).joinToString("\t", transform = ::encodeField)

    private fun decode(line: String): SnapshotRecord? {
        return runCatching {
            val fields = line.split('\t').map(::decodeField)
            if (fields.size !in RECORD_FIELD_COUNTS) return null
            val gameKey = fields.getOrNull(10)?.takeIf(String::isNotBlank)
            SnapshotRecord(
                id = fields[0],
                sourceId = fields[1],
                remoteName = fields[2],
                localPath = fields[3],
                createdAtMillis = fields[4].toLong(),
                sizeBytes = fields[5].toLong(),
                sha256 = fields[6],
                manifestHash = fields[7],
                uploaded = fields[8].toBooleanStrict(),
                comment = fields[9],
                gameKey = gameKey,
                gameName = fields.getOrNull(11).orEmpty(),
                saveType = fields.getOrNull(12)?.takeIf(GameSaveTypes::isValid)
                    ?: if (gameKey?.startsWith("switch:") == true) GameSaveTypes.SWITCH.id else GameSaveTypes.PC.id,
                cloudGameId = fields.getOrNull(13).orEmpty(),
                contentHash = fields.getOrNull(14).orEmpty(),
                hashVersion = fields.getOrNull(15)?.toIntOrNull() ?: 0,
                saveSignature = fields.getOrNull(16).orEmpty(),
                notCloudHead = fields.getOrNull(17) == "true",
            )
        }.getOrNull()
    }

    private fun encodeField(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeField(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

    companion object {
        const val DIRECTORY = "snapshots"
        private const val INDEX_FILE_NAME = "index.tsv"
        private const val TEMPORARY_PREFIX = ".index-"
        private const val TEMPORARY_SUFFIX = ".part"

        /** Older builds wrote 10, 12, 14 or 16 columns; 16 added the content hash, 17 the save signature, 18 the head flag. */
        private val RECORD_FIELD_COUNTS = setOf(10, 12, 14, 16, 17, 18)

        private val locks = ConcurrentHashMap<String, Any>()
        private val instances = ConcurrentHashMap<String, SnapshotRepository>()

        /** The process-wide catalog for [directory]; callers must not keep their own copies. */
        fun shared(directory: File): SnapshotRepository =
            instances.computeIfAbsent(directory.absoluteFile.normalize().path) { SnapshotRepository(File(it)) }

        fun forApp(context: Context): SnapshotRepository =
            shared(File(context.applicationContext.filesDir, DIRECTORY))
    }
}
