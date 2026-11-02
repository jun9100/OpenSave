package com.jun9100.opensave.activity

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jun9100.opensave.MainActivity
import com.jun9100.opensave.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** What a 动态 line is about (plan §3.1 "同步与任务 → 动态日志"). */
enum class ActivityKind(val label: String) {
    SCAN("扫描"),
    BACKUP("备份"),
    UPLOAD("上传"),
    RESTORE("恢复"),
    CLOUD_CHECK("检查云端"),
    BATCH("批量任务"),
    AUTO_BACKUP("自动备份"),
    PERMISSION("权限"),
}

enum class ActivityLevel { INFO, SUCCESS, WARNING, ERROR }

/**
 * One line of 动态. [needsAction] marks what the user has to handle (a conflict
 * found in the background, su refused to a worker); [repeats] counts identical
 * lines folded into this one, so a worker that skips every 15 minutes does not
 * push everything else out.
 */
data class ActivityEntry(
    val id: String,
    val atMillis: Long,
    val kind: ActivityKind,
    val level: ActivityLevel,
    val text: String,
    val detail: String = "",
    val needsAction: Boolean = false,
    val repeats: Int = 1,
) {
    fun sameAs(other: ActivityEntry): Boolean =
        kind == other.kind && level == other.level && text == other.text && detail == other.detail && needsAction == other.needsAction

    companion object {
        fun create(
            kind: ActivityKind,
            level: ActivityLevel,
            text: String,
            detail: String = "",
            needsAction: Boolean = false,
            atMillis: Long = System.currentTimeMillis(),
        ) = ActivityEntry(UUID.randomUUID().toString(), atMillis, kind, level, text, detail, needsAction)
    }
}

/** The pure rules of the log: newest first, identical recent lines folded, at most [MAX_ENTRIES]. */
object ActivityLog {
    const val MAX_ENTRIES = 200

    /** How many of the newest lines an identical new one may fold into. */
    const val FOLD_WINDOW = 20

    data class Appended(val entries: List<ActivityEntry>, val stored: ActivityEntry, val folded: Boolean)

    fun append(entries: List<ActivityEntry>, entry: ActivityEntry, max: Int = MAX_ENTRIES): Appended {
        val match = entries.take(FOLD_WINDOW).firstOrNull { it.sameAs(entry) }
        val stored = match?.copy(atMillis = maxOf(match.atMillis, entry.atMillis), repeats = match.repeats + 1) ?: entry
        val rest = if (match == null) entries else entries.filterNot { it.id == match.id }
        return Appended((listOf(stored) + rest).take(max), stored, folded = match != null)
    }

    /** The lines that still ask for the user, newest first. */
    fun needingAction(entries: List<ActivityEntry>): List<ActivityEntry> = entries.filter(ActivityEntry::needsAction)

    fun encode(entries: List<ActivityEntry>): String = JSONArray().apply {
        entries.forEach { entry ->
            put(
                JSONObject()
                    .put("id", entry.id)
                    .put("at", entry.atMillis)
                    .put("kind", entry.kind.name)
                    .put("level", entry.level.name)
                    .put("text", entry.text)
                    .put("detail", entry.detail)
                    .put("action", entry.needsAction)
                    .put("repeats", entry.repeats),
            )
        }
    }.toString()

    /** Lines of kinds a newer build added are dropped, not fatal. */
    fun decode(text: String): List<ActivityEntry> {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val json = array.getJSONObject(index)
                ActivityEntry(
                    id = json.getString("id"),
                    atMillis = json.getLong("at"),
                    kind = ActivityKind.valueOf(json.getString("kind")),
                    level = runCatching { ActivityLevel.valueOf(json.getString("level")) }.getOrDefault(ActivityLevel.INFO),
                    text = json.getString("text"),
                    detail = json.optString("detail"),
                    needsAction = json.optBoolean("action"),
                    repeats = json.optInt("repeats", 1).coerceAtLeast(1),
                )
            }.getOrNull()
        }
    }
}

/**
 * 动态 as one JSON file in the app's private files, written like
 * [com.jun9100.opensave.batch.BatchReportStore] (temporary file, then rename).
 * The ViewModel and the workers share the process, so [revision] tells the UI
 * when a worker added a line.
 */
class ActivityLogStore(private val file: File) {
    fun list(): List<ActivityEntry> = synchronized(lock) {
        if (!file.isFile) return emptyList()
        runCatching { ActivityLog.decode(file.readText()) }.getOrDefault(emptyList())
    }

    fun add(entry: ActivityEntry): ActivityLog.Appended = synchronized(lock) {
        val appended = ActivityLog.append(list(), entry)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(ActivityLog.encode(appended.entries))
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        changes.update { it + 1 }
        appended
    }

    companion object {
        private const val FILE_NAME = "activity-log.json"
        private val lock = Any()
        private val changes = MutableStateFlow(0L)

        /** Grows with every stored line in this process. */
        val revision: StateFlow<Long> = changes.asStateFlow()

        fun forApp(context: Context): ActivityLogStore =
            ActivityLogStore(File(context.applicationContext.filesDir, FILE_NAME))
    }
}

/** Stores lines and, for background ones that need the user, posts a notification. */
object ActivityRecorder {
    /**
     * Never throws: a full disk must not fail the backup it describes. A line
     * folded into an earlier identical one does not notify again.
     */
    fun record(context: Context, entry: ActivityEntry, notify: Boolean = false) {
        val appended = runCatching { ActivityLogStore.forApp(context).add(entry) }.getOrNull()
        if (notify && entry.needsAction && appended?.folded != true) {
            runCatching { ActivityNotifications.post(context, entry) }
        }
    }
}

/**
 * Notices for what needs the user. Only posted when notifications are allowed:
 * the permission is asked once, from the first-run wizard or 设置, never here.
 */
object ActivityNotifications {
    private const val CHANNEL_ID = "opensave_attention"
    private const val NOTIFICATION_ID_BASE = 5200
    const val EXTRA_OPEN_PAGE = "com.jun9100.opensave.OPEN_PAGE"
    const val PAGE_SYNC = "SYNC"

    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun post(context: Context, entry: ActivityEntry) {
        if (!allowed(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_attention), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_PAGE, PAGE_SYNC)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_savevault)
            .setContentTitle(context.getString(R.string.notification_attention_title, entry.kind.label))
            .setContentText(entry.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(entry.text, entry.detail).filter(String::isNotBlank).joinToString("\n")))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // One notice per kind: a newer conflict replaces the older notice instead of stacking.
        manager.notify(NOTIFICATION_ID_BASE + entry.kind.ordinal, notification)
    }
}
