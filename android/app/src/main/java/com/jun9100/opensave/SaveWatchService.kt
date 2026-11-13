// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package com.jun9100.opensave

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Watches the save directories the library already knows about and asks for a
 * backup as soon as one of them changes, so a save reaches the cloud seconds
 * after the emulator writes it instead of waiting for the next 15-minute round.
 *
 * The periodic worker stays in place as the fallback: this service only runs
 * while the user keeps "存档变化立即上传" on, and Android may stop it at any
 * time, after which the periodic round picks the change up again.
 */
class SaveWatchService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val observers = mutableListOf<FileObserver>()
    private var watched = listOf<String>()

    private val flush = Runnable {
        if (!SaveWatchPolicy.shouldUpload(applicationContext)) return@Runnable
        AutoBackupScheduler(applicationContext).requestImmediateBackup(REASON_WATCH)
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val dirs = intent?.getStringArrayListExtra(EXTRA_DIRS).orEmpty()
                if (dirs != watched) {
                    stopObservers()
                    watched = dirs
                    startObservers(dirs)
                }
                startForegroundWithNotification()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopObservers()
        handler.removeCallbacks(flush)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startObservers(dirs: List<String>) {
        for (dir in dirs) {
            val observer = object : FileObserver(dir, EVENTS) {
                override fun onEvent(event: Int, path: String?) {
                    // Any write, rename or delete inside a save directory means
                    // the game is saving; debounce so a burst becomes one upload.
                    handler.removeCallbacks(flush)
                    handler.postDelayed(flush, DEBOUNCE_MILLIS)
                }
            }
            runCatching { observer.startWatching() }.onSuccess { observers += observer }
        }
    }

    private fun stopObservers() {
        observers.forEach { runCatching { it.stopWatching() } }
        observers.clear()
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager?.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "实时备份", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_savevault)
            .setContentTitle("SaveVault 实时备份")
            .setContentText("正在监视 ${watched.size} 个存档位置，存档变化后自动上传")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "opensave_save_watch"
        private const val NOTIFICATION_ID = 4713
        private const val DEBOUNCE_MILLIS = 15_000L
        private const val REASON_WATCH = "watch"
        const val ACTION_STOP = "com.jun9100.opensave.STOP_SAVE_WATCH"
        const val EXTRA_DIRS = "com.jun9100.opensave.SAVE_WATCH_DIRS"

        private const val EVENTS = FileObserver.CREATE or FileObserver.MODIFY or
            FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.DELETE or
            FileObserver.MOVED_FROM

        fun start(context: Context, directories: Collection<String>) {
            val intent = Intent(context, SaveWatchService::class.java)
                .putStringArrayListExtra(EXTRA_DIRS, ArrayList(directories))
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SaveWatchService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}

/** Keeps the service honest about the switches on the settings page. */
internal object SaveWatchPolicy {
    private const val PREFERENCES = "opensave_automation"
    private const val WATCH_KEY = "save_watch_enabled"
    private const val BACKUP_KEY = "auto_backup_enabled"

    fun isEnabled(context: Context): Boolean = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(WATCH_KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(WATCH_KEY, enabled).apply()
    }

    /** A watch event only turns into an upload when both switches are on. */
    fun shouldUpload(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return preferences.getBoolean(WATCH_KEY, false) &&
            preferences.getBoolean(BACKUP_KEY, false)
    }
}
