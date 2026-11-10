package com.jun9100.opensave.ui.library

import com.jun9100.opensave.CrossDeviceArchivePart
import com.jun9100.opensave.GameSaveCloudGameMember
import com.jun9100.opensave.ui.formatShortTime
import com.jun9100.opensave.ui.kindLabel
import java.time.Instant

/** One-line descriptions of the other devices' save locations; no Android or storage calls. */
internal object OtherDeviceText {
    /** "MAR-AL00"; falls back to the first 8 chars of the device id, then "未知设备". */
    fun deviceName(member: GameSaveCloudGameMember): String {
        val head = member.head
        head?.deviceName?.takeIf(String::isNotBlank)?.let { return it }
        head?.device?.takeIf(String::isNotBlank)?.let { return it.take(DEVICE_ID_PREFIX) }
        return UNKNOWN_DEVICE
    }

    /** platform containing "android" → "Android"; PC-like → "PC"; blank → "未知平台"; else as is. */
    fun platformLabel(platform: String): String {
        val value = platform.trim()
        if (value.isBlank()) return UNKNOWN_PLATFORM
        val lower = value.lowercase()
        return when {
            "android" in lower -> "Android"
            PC_PLATFORM_HINTS.any { it in lower } -> "PC"
            else -> value
        }
    }

    /**
     * "PC · 11-08 20:13 · 3 个版本" (+ " · 冲突 1", + " · 手动关联").
     * The time comes from head.receivedAt (fallback createdAt); an unparsable
     * time is left out instead of guessing.
     */
    fun detail(member: GameSaveCloudGameMember): String {
        val head = member.head
        return buildString {
            if (head == null) {
                append("云端还没有版本")
            } else {
                append(platformLabel(head.platform))
                val time = parseInstant(head.receivedAt) ?: parseInstant(head.createdAt)
                time?.let { append(" · ${formatShortTime(it)}") }
                append(" · ${member.snapshotCount} 个版本")
                if (member.conflictCount > 0) append(" · 冲突 ${member.conflictCount}")
                if (member.link == "manual") append(" · 手动关联")
            }
            // The cloud merged this scope into another game; it only shows its history now.
            if (member.retired) append(" · 已合并")
        }
    }

    /** Label of one archive part in the dialog: kindLabel(kind) for Android parts, the PC folder otherwise. */
    fun partLabel(part: CrossDeviceArchivePart): String = part.kind?.let(::kindLabel) ?: PC_ARCHIVE_LABEL

    private fun parseInstant(value: String): Long? =
        runCatching { Instant.parse(value.trim()).toEpochMilli() }.getOrNull()

    private const val DEVICE_ID_PREFIX = 8
    private const val UNKNOWN_DEVICE = "未知设备"
    private const val UNKNOWN_PLATFORM = "未知平台"
    private const val PC_ARCHIVE_LABEL = "存档文件夹"
    private val PC_PLATFORM_HINTS = listOf("pc", "windows", "desktop", "mac", "linux")
}
