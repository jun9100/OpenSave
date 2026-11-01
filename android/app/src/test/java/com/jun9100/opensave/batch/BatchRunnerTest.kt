package com.jun9100.opensave.batch

import com.jun9100.opensave.GameSaveException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BatchRunnerTest {
    private val games = listOf("a", "b", "c", "d")

    private suspend fun run(
        isCancelled: () -> Boolean = { false },
        step: suspend (String) -> BatchStepResult,
    ): BatchReport = BatchRunner.run(
        id = "batch",
        operation = BatchOperation.UPLOAD_PENDING,
        items = games,
        key = { "src::$it" },
        name = { it.uppercase() },
        isCancelled = isCancelled,
        clock = { 1_000L },
        step = step,
    )

    @Test
    fun aFailedGameIsRecordedAndTheNextOneStillRuns() = runBlocking {
        val ran = mutableListOf<String>()
        val report = run { game ->
            ran += game
            when (game) {
                "b" -> error("HTTP 502")
                "c" -> BatchStepResult(BatchItemStatus.UNCHANGED, "最新版本已在云端")
                else -> BatchStepResult(BatchItemStatus.SUCCESS)
            }
        }
        assertEquals(games, ran)
        assertEquals(
            listOf(BatchItemStatus.SUCCESS, BatchItemStatus.FAILED, BatchItemStatus.UNCHANGED, BatchItemStatus.SUCCESS),
            report.items.map { it.status },
        )
        assertEquals("HTTP 502", report.items[1].detail)
        assertEquals("B", report.items[1].name)
        assertNull(report.endReason)
        assertEquals(listOf("src::b"), report.failedKeys)
    }

    @Test
    fun cancelStopsBetweenGamesAndMarksTheRestNotRun() = runBlocking {
        var cancelled = false
        val ran = mutableListOf<String>()
        val report = run(isCancelled = { cancelled }) { game ->
            ran += game
            if (game == "b") cancelled = true
            BatchStepResult(BatchItemStatus.SUCCESS)
        }
        // The game that was running when cancel came finishes; nothing after it starts.
        assertEquals(listOf("a", "b"), ran)
        assertEquals(BatchRunner.CANCELLED, report.endReason)
        assertEquals(
            listOf(BatchItemStatus.SUCCESS, BatchItemStatus.SUCCESS, BatchItemStatus.NOT_RUN, BatchItemStatus.NOT_RUN),
            report.items.map { it.status },
        )
        assertEquals(games.size, report.items.size)
    }

    @Test
    fun aRefusedSyncKeyStopsTheWholeBatch() = runBlocking {
        val ran = mutableListOf<String>()
        val report = run { game ->
            ran += game
            if (game == "b") throw GameSaveException("上传失败：HTTP 401", httpStatus = 401)
            BatchStepResult(BatchItemStatus.SUCCESS)
        }
        assertEquals(listOf("a", "b"), ran)
        assertEquals(BatchRunner.AUTH_REFUSED, report.endReason)
        assertEquals(BatchItemStatus.FAILED, report.items[1].status)
        assertEquals(listOf(BatchItemStatus.NOT_RUN, BatchItemStatus.NOT_RUN), report.items.drop(2).map { it.status })
    }

    @Test
    fun authFailureIsFoundInTheCauseChainButNotForOtherStatuses() {
        assertTrue(BatchRunner.isAuthFailure(RuntimeException("wrapped", GameSaveException("x", httpStatus = 403))))
        assertEquals(false, BatchRunner.isAuthFailure(GameSaveException("x", retryable = true, httpStatus = 502)))
        assertEquals(false, BatchRunner.isAuthFailure(IllegalStateException("no network")))
    }

    @Test
    fun anInterruptedBatchHandsOverItsPartialReportAndStillCancels() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var interrupted: BatchReport? = null
        val job = async(Dispatchers.Default) {
            BatchRunner.run(
                id = "batch",
                operation = BatchOperation.BACKUP,
                items = games,
                key = { it },
                name = { it },
                isCancelled = { false },
                onInterrupted = { interrupted = it },
                step = { game ->
                    if (game == "b") {
                        started.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                    BatchStepResult(BatchItemStatus.SUCCESS)
                },
            )
        }
        started.await()
        job.cancelAndJoin()
        try {
            job.await()
            fail("the batch should have been cancelled")
        } catch (_: CancellationException) {
        }
        val partial = checkNotNull(interrupted)
        assertEquals(BatchRunner.INTERRUPTED, partial.endReason)
        assertEquals(BatchItemStatus.SUCCESS, partial.items.first().status)
        assertEquals(3, partial.count(BatchItemStatus.NOT_RUN))
    }

    @Test
    fun summaryListsOnlyNonZeroCountsWithTheOperationWording() {
        val report = BatchReport(
            id = "r",
            operation = BatchOperation.BACKUP,
            startedAtMillis = 0,
            finishedAtMillis = 1,
            items = listOf(
                BatchItemResult("a", "A", BatchItemStatus.SUCCESS),
                BatchItemResult("b", "B", BatchItemStatus.SUCCESS),
                BatchItemResult("c", "C", BatchItemStatus.UNCHANGED),
                BatchItemResult("d", "D", BatchItemStatus.FAILED, "复制失败"),
            ),
        )
        assertEquals("批量备份完成：新建版本 2 款，无变化 1 款，失败 1 款", report.summary)
        assertEquals(
            "上传待传已停止：已上传 1 款，未执行 1 款（已取消）",
            BatchReport(
                id = "r",
                operation = BatchOperation.UPLOAD_PENDING,
                startedAtMillis = 0,
                finishedAtMillis = 1,
                items = listOf(
                    BatchItemResult("a", "A", BatchItemStatus.SUCCESS),
                    BatchItemResult("b", "B", BatchItemStatus.NOT_RUN),
                ),
                endReason = BatchRunner.CANCELLED,
            ).summary,
        )
    }

    @Test
    fun reportGroupsPutFailuresFirstAndSkipEmptyOnes() {
        val report = BatchReport(
            id = "r",
            operation = BatchOperation.CHECK_CLOUD,
            startedAtMillis = 0,
            finishedAtMillis = 1,
            items = listOf(
                BatchItemResult("a", "A", BatchItemStatus.UNCHANGED),
                BatchItemResult("b", "B", BatchItemStatus.FAILED),
                BatchItemResult("c", "C", BatchItemStatus.NEEDS_REVIEW),
            ),
        )
        assertEquals(
            listOf(BatchItemStatus.FAILED, BatchItemStatus.NEEDS_REVIEW, BatchItemStatus.UNCHANGED),
            report.groups().map { it.first },
        )
    }

    @Test
    fun retryTakesOnlyFailedGamesStillInTheLibraryInLibraryOrder() {
        val report = BatchReport(
            id = "r",
            operation = BatchOperation.UPLOAD_PENDING,
            startedAtMillis = 0,
            finishedAtMillis = 1,
            items = listOf(
                BatchItemResult("c", "C", BatchItemStatus.FAILED),
                BatchItemResult("a", "A", BatchItemStatus.FAILED),
                BatchItemResult("b", "B", BatchItemStatus.SUCCESS),
                BatchItemResult("gone", "Gone", BatchItemStatus.FAILED),
                BatchItemResult("d", "D", BatchItemStatus.NOT_RUN),
            ),
        )
        assertEquals(listOf("a", "c"), BatchRunner.retryKeys(report, listOf("a", "b", "c", "d")))
    }

    @Test
    fun progressTextShowsPositionGameWaitAndCancel() {
        val progress = BatchProgress("p", BatchOperation.UPLOAD_PENDING, total = 12, done = 2, currentName = "Hades")
        assertEquals("上传待传 3/12 · Hades", progress.text)
        assertEquals("上传待传 3/12 · 等待自动备份完成…", progress.copy(waitingFor = "自动备份").text)
        assertEquals("上传待传 3/12 · 正在取消…", progress.copy(cancelRequested = true).text)
        assertEquals("上传待传 12/12", progress.copy(done = 12, currentName = "").text)
    }
}
