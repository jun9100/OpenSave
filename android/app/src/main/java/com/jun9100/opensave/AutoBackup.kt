package com.jun9100.opensave

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

class AutoBackupScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(ENABLED_KEY, false)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(ENABLED_KEY, enabled).apply()
        if (enabled) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        } else {
            WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME)
        }
    }

    private companion object {
        const val PREFERENCES = "opensave_automation"
        const val ENABLED_KEY = "auto_backup_enabled"
        const val WORK_NAME = "opensave_auto_backup"
    }
}

class AutoBackupWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val storage = SafStorage(applicationContext)
        val uri = storage.persistedTreeUri() ?: return Result.success()
        if (!storage.hasReadWritePermission(uri)) return Result.success()

        val config = WebDavConfigStore(applicationContext).load()
        if (!config.enabled || config.url.isBlank()) return Result.success()

        val report = storage.importTree(uri).getOrElse { error ->
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
        val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val sourceId = SnapshotNaming.sourceId(uri.toString())
        if (preferences.getString(LAST_MANIFEST_KEY, null) == report.manifestHash) {
            return Result.success()
        }

        val timestamp = System.currentTimeMillis()
        val remoteName = SnapshotNaming.remoteName(sourceId, timestamp)
        val archive = File(applicationContext.filesDir, "snapshots/$remoteName")
        val build = SnapshotEngine().createSnapshot(storage.workspaceDirectory(), archive).getOrElse {
            return Result.failure()
        }
        val record = SnapshotRecord(
            id = "snap_$timestamp",
            sourceId = sourceId,
            remoteName = remoteName,
            localPath = remoteName,
            createdAtMillis = timestamp,
            sizeBytes = build.archive.length(),
            sha256 = build.archiveSha256,
            manifestHash = report.manifestHash,
        )
        val repository = SnapshotRepository(File(applicationContext.filesDir, "snapshots"))
        repository.add(record)
        val upload = WebDavClient(config).upload(archive, remoteName)
        if (upload.isFailure) {
            val uploadError = upload.exceptionOrNull() as? WebDavException
            return if (uploadError?.retryable == true) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
        repository.markUploaded(record.id)
        preferences.edit().putString(LAST_MANIFEST_KEY, report.manifestHash).apply()
        return Result.success()
    }

    private companion object {
        const val PREFERENCES = "opensave_automation"
        const val LAST_MANIFEST_KEY = "last_uploaded_manifest"
    }
}
