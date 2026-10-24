package com.jun9100.opensave

import java.io.File
import java.util.Base64

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
)

/** Small atomic catalog; the archives remain ordinary files and are never put in preferences. */
class SnapshotRepository(private val root: File) {
    private val indexFile = File(root, "index.tsv")

    init {
        require(root.exists() || root.mkdirs()) { "Could not create snapshot directory" }
    }

    @Synchronized
    fun list(): List<SnapshotRecord> = readRecords().sortedByDescending { it.createdAtMillis }

    @Synchronized
    fun add(record: SnapshotRecord) {
        val records = readRecords().filterNot { it.id == record.id } + record
        writeRecords(records)
    }

    @Synchronized
    fun markUploaded(id: String, uploaded: Boolean = true) {
        val records = readRecords().map { record ->
            if (record.id == id) record.copy(uploaded = uploaded) else record
        }
        writeRecords(records)
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

    private fun writeRecords(records: List<SnapshotRecord>) {
        val temporary = File(root, ".index-${System.nanoTime()}.part")
        temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
            records.sortedBy { it.createdAtMillis }.forEach { record ->
                writer.appendLine(encode(record))
            }
        }
        if (indexFile.exists() && !indexFile.delete()) {
            temporary.delete()
            error("Could not replace snapshot catalog")
        }
        require(temporary.renameTo(indexFile)) { "Could not publish snapshot catalog" }
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
    ).joinToString("\t", transform = ::encodeField)

    private fun decode(line: String): SnapshotRecord? {
        return runCatching {
            val fields = line.split('\t').map(::decodeField)
            if (fields.size != 10 && fields.size != 12) return null
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
                gameKey = fields.getOrNull(10)?.takeIf(String::isNotBlank),
                gameName = fields.getOrNull(11).orEmpty(),
            )
        }.getOrNull()
    }

    private fun encodeField(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeField(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}
