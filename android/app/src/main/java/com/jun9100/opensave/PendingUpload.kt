package com.jun9100.opensave

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jun9100.opensave.activity.ActivityEntry
import com.jun9100.opensave.activity.ActivityKind
import com.jun9100.opensave.activity.ActivityLevel
import com.jun9100.opensave.activity.ActivityRecorder
import com.jun9100.opensave.ops.GameOperations
import com.jun9100.opensave.ops.PendingUpload
import com.jun9100.opensave.ops.ready
import java.util.concurrent.TimeUnit

/**
 * Games whose newest backup was saved but never reached game-save, kept on
 * this device only. A failed manual upload is retried by [PendingUploadWorker]
 * once the network is back, instead of waiting for the next manual backup.
 *
 * The keys live here rather than in WorkManager input data, whose 10 KB limit
 * a large library can exceed (the same reason as
 * [com.jun9100.opensave.batch.BatchRequests] keeps its list in a file).
 */
class PendingUploadStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun keys(): Set<String> = synchronized(LOCK) { read() }

    fun add(libraryKeys: Collection<String>) = synchronized(LOCK) { write(read() + libraryKeys) }

    /**
     * Ends one attempt: [attempted] keys are dropped unless [remaining] still
     * needs them, while keys a manual failure added meanwhile stay for the
     * request WorkManager appended for them.
     */
    fun finish(attempted: Collection<String>, remaining: Collection<String>) = synchronized(LOCK) {
        write((read() - attempted.toSet()) + remaining)
    }

    fun clear() = synchronized(LOCK) { write(emptySet()) }

    private fun read(): Set<String> = preferences.getStringSet(KEYS_KEY, emptySet()).orEmpty().toSet()

    private fun write(libraryKeys: Collection<String>) {
        preferences.edit().apply {
            if (libraryKeys.isEmpty()) remove(KEYS_KEY) else putStringSet(KEYS_KEY, libraryKeys.toSet())
        }.apply()
    }

    private companion object {
        /** Process-wide: the ViewModel, the batch and the worker share the same file. */
        private val LOCK = Any()
        const val PREFERENCES = "opensave_pending_upload"
        const val KEYS_KEY = "pending_game_keys"
    }
}

/**
 * Queues the background retry of games a manual backup saved but could not
 * upload. Only retryable failures are queued ([PendingUploadPolicy.keysToRetry])
 * and only while 新版本自动上传 is on and the cloud is configured.
 */
internal object PendingUploadScheduler {
    private const val WORK_NAME = "opensave_pending_upload"
    private const val INITIAL_BACKOFF_SECONDS = 30L

    fun schedule(context: Context, libraryKeys: Collection<String>) {
        if (libraryKeys.isEmpty()) return
        PendingUploadStore(context).add(libraryKeys)
        val request = OneTimeWorkRequestBuilder<PendingUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: a key queued while an attempt already runs must not
        // be dropped (KEEP would) and its retry has to wait for the attempt.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

/**
 * The decisions of the pending-upload retry, kept free of Android so the unit
 * tests can cover every branch without WorkManager.
 */
internal object PendingUploadPolicy {
    /** Retries after the first attempt; exponential backoff gives 30 s, 60 s, 120 s. */
    const val MAX_RETRIES = 3

    private const val MAX_DETAIL_LINES = 5

    /** A failed upload may be retried in the background only when the cloud may accept it later. */
    fun retryable(error: Throwable?): Boolean = (error as? GameSaveException)?.retryable ?: true

    /**
     * The games of a manual backup's [failures] a background retry may pick up:
     * none while 新版本自动上传 or the cloud is off, and never a 409 conflict or
     * a refused sync key, whose existing records must stand instead of looping.
     */
    fun keysToRetry(config: GameSaveConfig, failures: List<Pair<String, Throwable?>>): List<String> =
        if (!config.ready) emptyList() else failures.filter { retryable(it.second) }.map { it.first }.distinct()

    /** What retrying one game did. */
    sealed interface Outcome {
        /** The newest saved backup reached game-save now (or already had before). */
        data object Uploaded : Outcome

        /** Nothing was published: the cloud already had the version, or the saves moved on. */
        data object NothingToDo : Outcome

        /** Not published; [retryable] decides whether the next attempt keeps the game. */
        data class Failed(val name: String, val reason: String, val retryable: Boolean) : Outcome

        /**
         * The cloud head moved first: the local version was kept as a conflict
         * copy and the cloud head was not overwritten. Not retried; the user
         * decides in "同步与任务" which copy to keep.
         */
        data class Conflict(val name: String, val notice: String) : Outcome
    }

    /** What one worker attempt did and what the next one still has to do. */
    data class Decision(
        val retry: Boolean = false,
        /** Library keys the next attempt must retry; empty when the queue is finished or given up. */
        val keepKeys: List<String> = emptyList(),
        val uploaded: Int = 0,
        /** (game, reason) of every failure of this attempt, kept for the 动态 line. */
        val failures: List<Pair<String, String>> = emptyList(),
        /** (game, notice) of conflicts kept as copies; the cloud head was not overwritten. */
        val conflicts: List<Pair<String, String>> = emptyList(),
    )

    /**
     * [attempt] is WorkManager's runAttemptCount. A retryable failure is retried
     * while the budget lasts; a conflict or a refused key is not retried at all.
     * Giving up returns success (WorkManager must not keep it alive) but keeps
     * the failure in [Decision.failures] for the activity log.
     */
    fun decide(
        results: List<Pair<String, Outcome>>,
        attempt: Int,
        maxRetries: Int = MAX_RETRIES,
    ): Decision {
        val failures = results.mapNotNull { (_, outcome) ->
            (outcome as? Outcome.Failed)?.let { it.name to it.reason }
        }
        val conflicts = results.mapNotNull { (_, outcome) ->
            (outcome as? Outcome.Conflict)?.let { it.name to it.notice }
        }
        val retryKeys = results.filter { (_, outcome) -> outcome is Outcome.Failed && outcome.retryable }.map { it.first }
        val retry = retryKeys.isNotEmpty() && attempt < maxRetries
        return Decision(
            retry = retry,
            keepKeys = if (retry) retryKeys else emptyList(),
            uploaded = results.count { it.second is Outcome.Uploaded },
            failures = failures,
            conflicts = conflicts,
        )
    }

    /** The 动态 line of one attempt (wording follows [AutoBackupPolicy.runEntry]). */
    fun entry(decision: Decision, atMillis: Long = System.currentTimeMillis()): ActivityEntry {
        val text = when {
            decision.failures.isNotEmpty() -> if (decision.retry) "补传失败，稍后重试" else "补传失败"
            decision.conflicts.isNotEmpty() -> buildString {
                if (decision.uploaded > 0) append("已补传 ${decision.uploaded} 款；")
                append("补传遇到冲突：${decision.conflicts.size} 款已保留为冲突副本")
            }
            decision.uploaded > 0 -> "已补传 ${decision.uploaded} 款"
            else -> "补传：没有需要上传的版本"
        }
        val level = when {
            decision.failures.isNotEmpty() || decision.conflicts.isNotEmpty() -> ActivityLevel.WARNING
            decision.uploaded > 0 -> ActivityLevel.SUCCESS
            else -> ActivityLevel.INFO
        }
        val detail = when {
            decision.failures.isNotEmpty() -> (decision.failures + decision.conflicts)
                .take(MAX_DETAIL_LINES)
                .joinToString("；") { (name, reason) -> "$name：$reason" }
            decision.conflicts.isNotEmpty() -> "请在“同步与任务”中选择保留哪一份；" +
                decision.conflicts.take(MAX_DETAIL_LINES).joinToString("；") { (name, notice) -> "$name：$notice" }
            else -> ""
        }
        return ActivityEntry.create(ActivityKind.UPLOAD, level, text, detail, atMillis = atMillis)
    }

    /** A round that could not run at all, with why. */
    fun failedEntry(reason: String, retrying: Boolean, atMillis: Long = System.currentTimeMillis()): ActivityEntry =
        ActivityEntry.create(
            ActivityKind.UPLOAD,
            ActivityLevel.ERROR,
            if (retrying) "补传失败，稍后重试" else "补传失败",
            reason,
            atMillis = atMillis,
        )

    /** A round whose precondition no longer holds, with why. */
    fun skippedEntry(reason: String, atMillis: Long = System.currentTimeMillis()): ActivityEntry =
        ActivityEntry.create(ActivityKind.UPLOAD, ActivityLevel.INFO, "补传已跳过：$reason", atMillis = atMillis)
}

/**
 * Publishes the newest saved backup of the games a manual backup could not
 * upload once the network is back. It only ever publishes an existing
 * snapshot ([GameOperations.uploadPendingLocked], the same path as "上传待传"),
 * never creates one and never replaces a newer cloud head: a conflict stays a
 * conflict copy/verdict for "同步与任务", like in the auto-backup worker.
 */
class PendingUploadWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val store = PendingUploadStore(applicationContext)
        val keys = store.keys()
        if (keys.isEmpty()) return Result.success()
        val config = GameSaveConfigStore(applicationContext).load()
        if (!config.ready) {
            // "新版本自动上传" was switched off after the failure: nothing may be published now.
            store.clear()
            record(PendingUploadPolicy.skippedEntry("SaveVault 云端未配置或未开启“新版本自动上传”"))
            return Result.success()
        }
        // An unexpected error still fails the run as before; it is only written down first.
        return runCatching { retryPending(store, keys, config) }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            val retrying = runAttemptCount < PendingUploadPolicy.MAX_RETRIES
            record(PendingUploadPolicy.failedEntry(error.message ?: error.javaClass.simpleName, retrying))
            if (retrying) Result.retry() else Result.success()
        }
    }

    private fun record(entry: ActivityEntry) = ActivityRecorder.record(applicationContext, entry, notify = true)

    private suspend fun retryPending(
        store: PendingUploadStore,
        keys: Set<String>,
        config: GameSaveConfig,
    ): Result {
        // The scan only resolves the saved keys to their games; no snapshot is built from it.
        val entries = GameLibrary.batchBackupEligibleEntries(
            GameLibrary.sourceGames(ScheduledSources.scan(applicationContext)),
        ).associateBy(SourceGameEntry::libraryKey)
        val operations = GameOperations(applicationContext)
        val verdicts = mutableMapOf<String, CloudVerdict>()
        val results = keys.map { key -> key to retryOne(key, entries[key], operations, config, verdicts) }
        val decision = PendingUploadPolicy.decide(results, runAttemptCount)
        // Keys queued by a manual failure while this attempt ran were appended as
        // another request; [PendingUploadStore.finish] leaves them for it.
        store.finish(keys, decision.keepKeys)
        recordVerdicts(verdicts)
        record(PendingUploadPolicy.entry(decision))
        return if (decision.retry) Result.retry() else Result.success()
    }

    private suspend fun retryOne(
        key: String,
        entry: SourceGameEntry?,
        operations: GameOperations,
        config: GameSaveConfig,
        verdicts: MutableMap<String, CloudVerdict>,
    ): PendingUploadPolicy.Outcome {
        if (entry == null) return PendingUploadPolicy.Outcome.NothingToDo
        val name = entry.game.presentation.primaryName
        val attempt = runCatching {
            StorageOperationLock.withLock(LOCK_OWNER) { operations.uploadPendingLocked(entry, config) }
        }
        return attempt.fold(
            onSuccess = { pending ->
                when (pending) {
                    PendingUpload.NothingPending -> PendingUploadPolicy.Outcome.NothingToDo
                    // The saves moved on after the saved version; publishing it would
                    // move the cloud head backwards, so the next manual backup wins.
                    PendingUpload.LocalChanged -> PendingUploadPolicy.Outcome.NothingToDo
                    is PendingUpload.Published -> {
                        pending.outcome.cloudUpdate?.let { update -> verdicts[key] = CloudVerdict(update.action, "") }
                        val error = pending.outcome.uploadError
                        val notice = pending.outcome.syncNotice
                        when {
                            error != null -> PendingUploadPolicy.Outcome.Failed(
                                name,
                                error,
                                PendingUploadPolicy.retryable(pending.outcome.uploadException),
                            )
                            notice != null -> PendingUploadPolicy.Outcome.Conflict(name, notice)
                            else -> PendingUploadPolicy.Outcome.Uploaded
                        }
                    }
                }
            },
            onFailure = { error ->
                PendingUploadPolicy.Outcome.Failed(
                    name,
                    error.message ?: "补传失败",
                    PendingUploadPolicy.retryable(error),
                )
            },
        )
    }

    /** Ties conflicts found by this attempt to the game's newest snapshot, like a check in the app. */
    private fun recordVerdicts(verdicts: Map<String, CloudVerdict>) {
        if (verdicts.isEmpty()) return
        val records = runCatching { SnapshotRepository.forApp(applicationContext).list() }.getOrDefault(emptyList())
        val tied = verdicts.mapValues { (key, verdict) ->
            verdict.copy(recordId = GameStatusResolver.latestRecordId(records, key))
        }
        runCatching { CloudVerdictStore(applicationContext).update(verdicts = tied) }
    }

    private companion object {
        /** Shown while another operation waits: "等待补传完成…". */
        const val LOCK_OWNER = "补传"
    }
}
