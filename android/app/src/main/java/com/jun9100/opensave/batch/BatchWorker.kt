package com.jun9100.opensave.batch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jun9100.opensave.GameLibrary
import com.jun9100.opensave.R
import com.jun9100.opensave.ScheduledSources
import com.jun9100.opensave.SourceGameEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Runs a network batch (上传待传 / 检查云端更新 / 安全恢复云端较新) outside the
 * ViewModel (plan §5.5): WorkManager restarts it after the process is killed,
 * and every operation is idempotent, so the re-run only finishes what is left.
 * It asks to run in the foreground with a notification; when Android refuses
 * that (started from the background), it continues as ordinary work.
 */
class BatchWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val operation = BatchRequests.read(applicationContext, inputData.getString(KEY_ID).orEmpty())?.operation
        return BatchNotifications.foregroundInfo(applicationContext, operation)
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val request = BatchRequests.read(applicationContext, id) ?: return Result.success()
        // Another batch of this process (e.g. a local backup) runs: come back later.
        if (!BatchCenter.tryBegin(id, request.operation, request.keys.size)) return Result.retry()
        try {
            runCatching { setForeground(BatchNotifications.foregroundInfo(applicationContext, request.operation)) }
            val entries = BatchCenter.takeEntries(id)
                ?: GameLibrary.sourceGames(ScheduledSources.scan(applicationContext))
            val byKey = GameLibrary.batchBackupEligibleEntries(entries).associateBy(SourceGameEntry::libraryKey)
            val selected = request.keys.mapNotNull(byKey::get)
            val missing = request.keys.filter { it !in byKey }.map { key -> key to request.names[key].orEmpty().ifBlank { key } }
            BatchJobs.execute(applicationContext, id, request.operation, selected, missing)
        } finally {
            // execute() already released it; this covers a failure before it ran.
            BatchCenter.finish(id, null)
            // A stopped worker is re-run by WorkManager and needs its request again.
            if (!isStopped) BatchRequests.delete(applicationContext, id)
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "opensave_batch"
        private const val KEY_ID = "batch_id"

        /**
         * Queues the batch. The caller reserved [id] in [BatchCenter]; with
         * KEEP a second request would be dropped silently, so callers check
         * [hasPendingWork] first and refuse with a clear message.
         */
        fun enqueue(context: Context, id: String, operation: BatchOperation, entries: List<SourceGameEntry>) {
            BatchRequests.write(context, id, operation, entries)
            val request = OneTimeWorkRequestBuilder<BatchWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** True while a queued or running batch exists, e.g. one resumed after the process was killed. Blocking. */
        fun hasPendingWork(context: Context): Boolean = runCatching {
            WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWork(UNIQUE_WORK_NAME)
                .get()
                .any { !it.state.isFinished && it.state != WorkInfo.State.CANCELLED }
        }.getOrDefault(false)
    }
}

/** What a queued network batch should do, kept in a file: the key list can exceed WorkManager's 10 KB input limit. */
internal data class BatchRequest(
    val operation: BatchOperation,
    val keys: List<String>,
    val names: Map<String, String>,
)

internal object BatchRequests {
    private const val DIRECTORY = "batch-requests"

    private fun file(context: Context, id: String): File =
        File(context.applicationContext.filesDir, "$DIRECTORY/${id.filter { it.isLetterOrDigit() || it == '-' }}.json")

    fun write(context: Context, id: String, operation: BatchOperation, entries: List<SourceGameEntry>) {
        val target = file(context, id)
        target.parentFile?.mkdirs()
        val json = JSONObject()
            .put("operation", operation.name)
            .put(
                "games",
                JSONArray().apply {
                    entries.forEach { entry ->
                        put(JSONObject().put("key", entry.libraryKey).put("name", entry.game.presentation.primaryName))
                    }
                },
            )
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(json.toString())
        require(temporary.renameTo(target)) { "无法保存批量任务" }
    }

    fun read(context: Context, id: String): BatchRequest? = runCatching {
        val json = JSONObject(file(context, id).readText())
        val games = json.getJSONArray("games")
        val pairs = (0 until games.length()).map { index ->
            val game = games.getJSONObject(index)
            game.getString("key") to game.optString("name")
        }
        BatchRequest(BatchOperation.valueOf(json.getString("operation")), pairs.map { it.first }, pairs.toMap())
    }.getOrNull()

    fun delete(context: Context, id: String) {
        file(context, id).delete()
    }
}

internal object BatchNotifications {
    private const val CHANNEL_ID = "opensave_batch"
    private const val NOTIFICATION_ID = 4107

    fun foregroundInfo(context: Context, operation: BatchOperation?): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "批量任务", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_opensave)
            .setContentTitle("OpenSave")
            .setContentText("正在${operation?.label ?: "执行批量任务"}，可在应用内查看进度或取消")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
