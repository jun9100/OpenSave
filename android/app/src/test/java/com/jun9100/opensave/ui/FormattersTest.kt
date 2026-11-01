package com.jun9100.opensave.ui

import com.jun9100.opensave.SourceAccessCapability
import com.jun9100.opensave.SourceAccessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {
    @Test
    fun accessLabelsDescribeEachCapability() {
        assertEquals("应用可访问 · 可读写", sourceAccessLabel(SourceAccessStatus.appAccessible()))
        assertEquals("应用可访问 · 只读", sourceAccessLabel(SourceAccessStatus.appAccessible(writable = false)))
        assertEquals("SAF 已授权 · 只读，可备份", sourceAccessLabel(SourceAccessStatus.safAccessible(writable = false)))
        assertEquals("仅 Root 可访问 · 可读写", sourceAccessLabel(SourceAccessStatus.rootOnly()))
    }

    @Test
    fun unavailableLabelNamesTheMissingAccessAndReason() {
        val status = SourceAccessStatus.unavailable(SourceAccessCapability.ROOT, "Root 未授权")

        assertEquals("不可访问 · 需要 Root · Root 未授权", sourceAccessLabel(status))
        assertEquals(
            "不可访问 · 需要 SAF 授权",
            sourceAccessLabel(SourceAccessStatus.unavailable(SourceAccessCapability.SAF, "")),
        )
    }

    @Test
    fun kindLabelFallsBackToTheRawKind() {
        assertEquals("Yuzu/Eden Switch", kindLabel("yuzu-switch"))
        assertEquals("GameHub 本地 PC", kindLabel("gamehub-pc"))
        assertEquals("some-new-kind", kindLabel("some-new-kind"))
    }

    @Test
    fun bytesUseBinaryUnits() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertTrue(formatBytes(1024).endsWith(" KiB"))
        assertTrue(formatBytes(1024L * 1024).endsWith(" MiB"))
    }

    @Test
    fun shortTimeFitsACompactRow() {
        assertTrue(Regex("""\d\d-\d\d \d\d:\d\d""").matches(formatShortTime(1_793_000_000_000)))
    }
}
