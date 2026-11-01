package com.jun9100.opensave.batch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The last [MAX_REPORTS] batch reports, newest first, as one JSON file in the
 * app's private files (plan S7). Written to a temporary file and renamed over
 * the old one, so a kill never leaves half a file.
 */
class BatchReportStore(private val file: File) {
    fun list(): List<BatchReport> = synchronized(lock) {
        if (!file.isFile) return emptyList()
        runCatching { decode(file.readText()) }.getOrDefault(emptyList())
    }

    fun latest(): BatchReport? = list().firstOrNull()

    fun add(report: BatchReport) = synchronized(lock) {
        val reports = (listOf(report) + list().filterNot { it.id == report.id }).take(MAX_REPORTS)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(encode(reports))
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val MAX_REPORTS = 20
        private const val FILE_NAME = "batch-reports.json"
        private val lock = Any()

        fun forApp(context: Context): BatchReportStore =
            BatchReportStore(File(context.applicationContext.filesDir, FILE_NAME))

        fun encode(reports: List<BatchReport>): String = JSONArray().apply {
            reports.forEach { report ->
                put(
                    JSONObject()
                        .put("id", report.id)
                        .put("operation", report.operation.name)
                        .put("startedAt", report.startedAtMillis)
                        .put("finishedAt", report.finishedAtMillis)
                        .put("endReason", report.endReason ?: JSONObject.NULL)
                        .put(
                            "items",
                            JSONArray().apply {
                                report.items.forEach { item ->
                                    put(
                                        JSONObject()
                                            .put("key", item.key)
                                            .put("name", item.name)
                                            .put("status", item.status.name)
                                            .put("detail", item.detail),
                                    )
                                }
                            },
                        ),
                )
            }
        }.toString()

        /** Reports of unknown operations (a newer build wrote them) are dropped, not fatal. */
        fun decode(text: String): List<BatchReport> {
            val array = JSONArray(text)
            return (0 until array.length()).mapNotNull { index ->
                runCatching {
                    val json = array.getJSONObject(index)
                    val items = json.getJSONArray("items")
                    BatchReport(
                        id = json.getString("id"),
                        operation = BatchOperation.valueOf(json.getString("operation")),
                        startedAtMillis = json.getLong("startedAt"),
                        finishedAtMillis = json.getLong("finishedAt"),
                        endReason = if (json.isNull("endReason")) null else json.getString("endReason"),
                        items = (0 until items.length()).map { itemIndex ->
                            val item = items.getJSONObject(itemIndex)
                            BatchItemResult(
                                key = item.getString("key"),
                                name = item.optString("name"),
                                status = runCatching { BatchItemStatus.valueOf(item.getString("status")) }
                                    .getOrDefault(BatchItemStatus.FAILED),
                                detail = item.optString("detail"),
                            )
                        },
                    )
                }.getOrNull()
            }
        }
    }
}
