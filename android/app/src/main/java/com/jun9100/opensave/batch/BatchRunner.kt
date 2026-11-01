package com.jun9100.opensave.batch

import com.jun9100.opensave.GameSaveException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Thrown by a step to stop the whole batch, e.g. when game-save refuses the sync key. */
class BatchAbortException(message: String) : Exception(message)

/**
 * The sequential batch engine (plan §5.5), free of Android so it is unit
 * tested: every game runs through [step] one after another, a failure is
 * recorded and the next game runs, a cancel takes effect between games, and
 * a refused sync key (HTTP 401/403) stops the batch because every later game
 * would fail the same way.
 */
object BatchRunner {
    const val CANCELLED = "已取消"
    const val INTERRUPTED = "被中断"

    suspend fun <T> run(
        id: String,
        operation: BatchOperation,
        items: List<T>,
        key: (T) -> String,
        name: (T) -> String,
        isCancelled: () -> Boolean,
        onProgress: (done: Int, current: String) -> Unit = { _, _ -> },
        /** Called with the partial report when the coroutine itself is cancelled (worker stopped, app closed). */
        onInterrupted: suspend (BatchReport) -> Unit = {},
        clock: () -> Long = System::currentTimeMillis,
        step: suspend (T) -> BatchStepResult,
    ): BatchReport {
        val startedAt = clock()
        val results = mutableListOf<BatchItemResult>()
        fun report(endReason: String?): BatchReport {
            val remaining = items.drop(results.size).map { item ->
                BatchItemResult(key(item), name(item), BatchItemStatus.NOT_RUN)
            }
            return BatchReport(id, operation, startedAt, clock(), results + remaining, endReason)
        }
        try {
            for ((index, item) in items.withIndex()) {
                if (isCancelled()) return report(CANCELLED)
                onProgress(index, name(item))
                val result = try {
                    step(item)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (abort: BatchAbortException) {
                    results += BatchItemResult(key(item), name(item), BatchItemStatus.FAILED, abort.message.orEmpty())
                    return report(abort.message ?: "已中止")
                } catch (error: Throwable) {
                    val message = error.message ?: "操作失败"
                    if (isAuthFailure(error)) {
                        results += BatchItemResult(key(item), name(item), BatchItemStatus.FAILED, message)
                        return report(AUTH_REFUSED)
                    }
                    BatchStepResult(BatchItemStatus.FAILED, message)
                }
                results += BatchItemResult(key(item), name(item), result.status, result.detail)
            }
            onProgress(items.size, "")
            return report(null)
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { onInterrupted(report(INTERRUPTED)) }
            throw cancel
        }
    }

    /** True when game-save refused the credentials; anywhere in the cause chain. */
    fun isAuthFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(8).any { it is GameSaveException && it.httpStatus in AUTH_STATUSES }

    /**
     * The games "重试失败项" runs again: the failed ones of [report] that are
     * still in the library ([available], in library order).
     */
    fun retryKeys(report: BatchReport, available: Collection<String>): List<String> {
        val failed = report.failedKeys.toSet()
        return available.filter { it in failed }
    }

    const val AUTH_REFUSED = "game-save 拒绝了同步密钥，已停止"
    private val AUTH_STATUSES = setOf(401, 403)
}
