package com.jun9100.opensave.batch

import android.content.Context
import com.jun9100.opensave.GameLibrary
import com.jun9100.opensave.GameSaveCloudUpdate
import com.jun9100.opensave.GameSaveRemoteAction
import com.jun9100.opensave.GameSnapshotManifest
import com.jun9100.opensave.RemoteSnapshot
import com.jun9100.opensave.SourceGameEntry
import com.jun9100.opensave.StorageOperationLock
import com.jun9100.opensave.activity.ActivityEntry
import com.jun9100.opensave.activity.ActivityKind
import com.jun9100.opensave.activity.ActivityLevel
import com.jun9100.opensave.activity.ActivityRecorder
import com.jun9100.opensave.ops.GameCaptureOutcome
import com.jun9100.opensave.ops.GameOperations
import com.jun9100.opensave.ops.PendingUpload
import com.jun9100.opensave.ops.PublishOutcome
import com.jun9100.opensave.ops.SafeRestore
import com.jun9100.opensave.ops.ready

/**
 * Runs one batch to the end: the ViewModel calls it for local batches, the
 * [BatchWorker] for network ones. Each game takes the storage lock on its own
 * (never the whole batch), so the auto-backup worker and single operations
 * interleave between games. The report is stored and announced through
 * [BatchCenter] even when the batch is cancelled or interrupted.
 */
internal object BatchJobs {
    suspend fun execute(
        context: Context,
        id: String,
        operation: BatchOperation,
        entries: List<SourceGameEntry>,
        /** Requested games the scan no longer found, as (key, name). */
        missing: List<Pair<String, String>> = emptyList(),
    ): BatchReport {
        val appContext = context.applicationContext
        val store = BatchReportStore.forApp(appContext)
        val execution = BatchExecution(id, operation, GameOperations(appContext))
        var report: BatchReport? = null
        try {
            val ran = BatchRunner.run(
                id = id,
                operation = operation,
                items = entries,
                key = SourceGameEntry::libraryKey,
                name = { it.game.presentation.primaryName },
                isCancelled = { BatchCenter.isCancelRequested(id) },
                onProgress = { done, current -> BatchCenter.progress(id, done, current) },
                onInterrupted = { partial ->
                    report = partial
                    runCatching { store.add(partial) }
                },
                step = execution::run,
            )
            val complete = ran.copy(
                items = ran.items + missing.map { (key, name) ->
                    BatchItemResult(key, name, BatchItemStatus.SKIPPED, "来源中已找不到这款游戏")
                },
            )
            report = complete
            runCatching { store.add(complete) }
            return complete
        } finally {
            execution.findings()?.let(BatchCenter::publishFindings)
            report?.let { ActivityRecorder.record(appContext, BatchActivity.entry(it)) }
            BatchCenter.finish(id, report)
        }
    }
}

/** The 动态 line of a finished or stopped batch: its summary, and the first failures. */
internal object BatchActivity {
    private const val MAX_DETAIL_ITEMS = 5

    fun entry(report: BatchReport): ActivityEntry {
        val failed = report.items.filter { it.status == BatchItemStatus.FAILED }
        val level = when {
            failed.isNotEmpty() -> ActivityLevel.WARNING
            report.endReason != null -> ActivityLevel.WARNING
            report.count(BatchItemStatus.NEEDS_REVIEW) > 0 -> ActivityLevel.WARNING
            else -> ActivityLevel.SUCCESS
        }
        val detail = failed.take(MAX_DETAIL_ITEMS).joinToString("；") { item ->
            if (item.detail.isBlank()) item.name else "${item.name}：${item.detail}"
        }
        return ActivityEntry.create(ActivityKind.BATCH, level, report.summary, detail, atMillis = report.finishedAtMillis)
    }
}

/** The step of each operation for one game, and the cloud verdicts it collects. */
internal class BatchExecution(
    private val id: String,
    private val operation: BatchOperation,
    private val ops: GameOperations,
) {
    private val checkedKeys = linkedSetOf<String>()
    private val updates = mutableListOf<GameSaveCloudUpdate>()
    private val config by lazy { ops.loadConfig() }
    private val client by lazy { ops.client(config) }
    private var remoteList: List<RemoteSnapshot>? = null

    /** The cloud file list, read once and only when the legacy protocol needs it. */
    private fun remoteSnapshots(): List<RemoteSnapshot> =
        remoteList ?: client.list().getOrThrow().also { remoteList = it }

    fun findings(): CloudFindings? =
        if (checkedKeys.isEmpty() && remoteList == null) null else CloudFindings(id, checkedKeys.toSet(), updates.toList(), remoteList)

    suspend fun run(entry: SourceGameEntry): BatchStepResult = when (operation) {
        BatchOperation.BACKUP -> backup(entry)
        BatchOperation.UPLOAD_PENDING -> uploadPending(entry)
        BatchOperation.CHECK_CLOUD -> checkCloud(entry)
        BatchOperation.RESTORE_CLOUD_NEWER -> restoreCloudNewer(entry)
    }

    private suspend fun <T> locked(block: suspend () -> T): T = StorageOperationLock.withLock(
        owner = operation.label,
        onWait = { holder -> BatchCenter.waiting(id, holder) },
    ) {
        BatchCenter.waiting(id, holder = null)
        block()
    }

    private suspend fun backup(entry: SourceGameEntry): BatchStepResult {
        val manifest = GameSnapshotManifest.from(entry.game, GameLibrary.batchBackupLocations(entry.game))
        val captured = locked { ops.captureLocked(entry, manifest) }
        if (captured.published) remember(entry, captured.cloudUpdate)
        return captured.toResult()
    }

    private suspend fun uploadPending(entry: SourceGameEntry): BatchStepResult {
        require(config.ready) { "未启用 SaveVault 云端同步" }
        return when (val result = locked { ops.uploadPendingLocked(entry, config) }) {
            PendingUpload.NothingPending -> BatchStepResult(BatchItemStatus.UNCHANGED, "最新版本已在云端")
            PendingUpload.LocalChanged -> BatchStepResult(BatchItemStatus.SKIPPED, "存档在备份后又有变更，请先备份")
            is PendingUpload.Published -> {
                if (result.outcome.uploaded) remember(entry, result.outcome.cloudUpdate)
                result.outcome.toResult()
            }
        }
    }

    private suspend fun checkCloud(entry: SourceGameEntry): BatchStepResult {
        require(config.ready) { "未启用 SaveVault 云端同步" }
        val update = locked { ops.checkCloudLocked(entry, config, client, ::remoteSnapshots) }
        checkedKeys += entry.libraryKey
        update ?: return BatchStepResult(BatchItemStatus.UNCHANGED)
        updates += update
        return when (update.action) {
            GameSaveRemoteAction.AUTO_RESTORE -> BatchStepResult(BatchItemStatus.SUCCESS, "可安全恢复")
            GameSaveRemoteAction.REVIEW_REQUIRED -> BatchStepResult(BatchItemStatus.NEEDS_REVIEW, update.message)
            GameSaveRemoteAction.WAIT_FOR_REMOTE -> BatchStepResult(BatchItemStatus.SKIPPED, update.message.ifBlank { "远端未就绪" })
            GameSaveRemoteAction.UP_TO_DATE -> BatchStepResult(BatchItemStatus.UNCHANGED)
        }
    }

    private suspend fun restoreCloudNewer(entry: SourceGameEntry): BatchStepResult {
        require(config.ready) { "未启用 SaveVault 云端同步" }
        val result = locked { ops.safeRestoreLocked(entry, config, client, ::remoteSnapshots) }
        checkedKeys += entry.libraryKey
        return when (result) {
            SafeRestore.UpToDate -> BatchStepResult(BatchItemStatus.UNCHANGED)
            SafeRestore.ReadOnly -> BatchStepResult(BatchItemStatus.SKIPPED, "只读来源，不能恢复；请重新授予读写权限")
            is SafeRestore.NeedsReview -> {
                updates += result.update
                BatchStepResult(BatchItemStatus.NEEDS_REVIEW, result.update.message.ifBlank { "云端版本需要确认" })
            }
            is SafeRestore.Waiting -> {
                updates += result.update
                BatchStepResult(BatchItemStatus.SKIPPED, result.update.message.ifBlank { "远端未就绪" })
            }
            is SafeRestore.Restored -> BatchStepResult(BatchItemStatus.SUCCESS, "恢复前的存档已保存为安全快照")
        }
    }

    /**
     * A backup or upload reached game-save: its verdict ([update], none when
     * the publish became the head) replaces what was known for the game.
     */
    private fun remember(entry: SourceGameEntry, update: GameSaveCloudUpdate?) {
        checkedKeys += entry.libraryKey
        updates.removeAll { it.entry.libraryKey == entry.libraryKey }
        update?.let(updates::add)
    }

    private fun GameCaptureOutcome.toResult(): BatchStepResult {
        uploadException?.takeIf(BatchRunner::isAuthFailure)?.let { throw BatchAbortException(BatchRunner.AUTH_REFUSED) }
        return when {
            uploadError != null -> BatchStepResult(
                BatchItemStatus.FAILED,
                if (reused) uploadError else "快照已保存，$uploadError",
            )
            syncNotice != null -> BatchStepResult(BatchItemStatus.NEEDS_REVIEW, syncNotice)
            reused -> BatchStepResult(BatchItemStatus.UNCHANGED, if (uploadedReused) "已补传云端" else "")
            else -> BatchStepResult(BatchItemStatus.SUCCESS)
        }
    }

    private fun PublishOutcome.toResult(): BatchStepResult {
        uploadException?.takeIf(BatchRunner::isAuthFailure)?.let { throw BatchAbortException(BatchRunner.AUTH_REFUSED) }
        return when {
            uploadError != null -> BatchStepResult(BatchItemStatus.FAILED, uploadError)
            syncNotice != null -> BatchStepResult(BatchItemStatus.NEEDS_REVIEW, syncNotice)
            else -> BatchStepResult(BatchItemStatus.SUCCESS)
        }
    }
}
