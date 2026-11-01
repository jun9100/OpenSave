package com.jun9100.opensave.batch

import com.jun9100.opensave.BatchSummary

/** The batch operations of plan §5.4 (first four). */
enum class BatchOperation(
    val label: String,
    /** Network batches run in [BatchWorker]; local ones in the ViewModel. */
    val usesNetwork: Boolean,
    /** Summary wording of [BatchItemStatus.SUCCESS] and [BatchItemStatus.UNCHANGED]. */
    val successLabel: String,
    val unchangedLabel: String,
) {
    BACKUP("批量备份", usesNetwork = false, successLabel = "新建版本", unchangedLabel = "无变化"),
    UPLOAD_PENDING("上传待传", usesNetwork = true, successLabel = "已上传", unchangedLabel = "无需上传"),
    CHECK_CLOUD("检查云端更新", usesNetwork = true, successLabel = "云端较新", unchangedLabel = "已是最新"),
    RESTORE_CLOUD_NEWER("安全恢复云端较新", usesNetwork = true, successLabel = "已恢复", unchangedLabel = "已是最新"),
    ;

    fun statusLabel(status: BatchItemStatus): String = when (status) {
        BatchItemStatus.SUCCESS -> successLabel
        BatchItemStatus.UNCHANGED -> unchangedLabel
        else -> status.label
    }
}

/** Per-game result of a batch (plan §5.6 groups). */
enum class BatchItemStatus(val label: String) {
    SUCCESS("成功"),
    UNCHANGED("无变化"),
    SKIPPED("跳过"),

    /** Needs a decision by the user (a sync notice, a cloud conflict); not an error. */
    NEEDS_REVIEW("需确认"),
    FAILED("失败"),

    /** Not reached: cancelled, interrupted or stopped by a refused sync key. */
    NOT_RUN("未执行"),
}

/** What one step of a batch reports for its game. */
data class BatchStepResult(val status: BatchItemStatus, val detail: String = "")

data class BatchItemResult(
    /** libraryKey (`sourceId::gameKey`), the key of selections and retries. */
    val key: String,
    val name: String,
    val status: BatchItemStatus,
    val detail: String = "",
)

data class BatchReport(
    val id: String,
    val operation: BatchOperation,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val items: List<BatchItemResult>,
    /** Why it ended before the last game ("已取消", "被中断", a refused sync key); null when it ran to the end. */
    val endReason: String? = null,
) {
    fun count(status: BatchItemStatus): Int = items.count { it.status == status }

    /** The games "重试失败项" runs again. */
    val failedKeys: List<String>
        get() = items.filter { it.status == BatchItemStatus.FAILED }.map(BatchItemResult::key)

    /** The non-empty groups as the report sheet lists them: what needs attention first. */
    fun groups(): List<Pair<BatchItemStatus, List<BatchItemResult>>> = REPORT_ORDER.mapNotNull { status ->
        items.filter { it.status == status }.takeIf { it.isNotEmpty() }?.let { status to it }
    }

    /** "上传待传：已上传 3 款，失败 1 款"; only the counts that are not zero. */
    val summary: String
        get() {
            val counts = BatchSummary.counts(
                BatchItemStatus.entries.map { status -> operation.statusLabel(status) to count(status) },
            )
            return buildString {
                append(operation.label)
                append(if (endReason == null) "完成：" else "已停止：")
                append(counts.ifEmpty { "没有处理任何游戏" })
                endReason?.let { append("（$it）") }
            }
        }

    private companion object {
        val REPORT_ORDER = listOf(
            BatchItemStatus.FAILED,
            BatchItemStatus.NEEDS_REVIEW,
            BatchItemStatus.NOT_RUN,
            BatchItemStatus.SKIPPED,
            BatchItemStatus.SUCCESS,
            BatchItemStatus.UNCHANGED,
        )
    }
}

/** A batch while it runs; one at a time per process ([BatchCenter]). */
data class BatchProgress(
    val id: String,
    val operation: BatchOperation,
    val total: Int,
    val done: Int = 0,
    val currentName: String = "",
    /** Who holds the storage lock this batch waits for, e.g. "自动备份". */
    val waitingFor: String? = null,
    val cancelRequested: Boolean = false,
) {
    /** "上传待传 3/12 · 塞尔达传说"; the wait or the pending cancel replaces the game. */
    val text: String
        get() = buildString {
            append("${operation.label} ${minOf(done + 1, total)}/$total")
            when {
                cancelRequested -> append(" · 正在取消…")
                waitingFor != null -> append(" · 等待${waitingFor}完成…")
                currentName.isNotBlank() -> append(" · $currentName")
            }
        }

    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
}
