package com.jun9100.opensave.ui

import com.jun9100.opensave.CrossDeviceArchivePart
import com.jun9100.opensave.GameSaveCloudGameMember
import com.jun9100.opensave.GameSaveScopeSnapshot
import com.jun9100.opensave.ui.library.OtherDeviceText
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class OtherDeviceTextTest {
    @Test
    fun deviceNamePrefersTheReportedNameThenTheDeviceIdPrefix() {
        assertEquals(
            "MAR-AL00",
            OtherDeviceText.deviceName(member(snapshot(deviceName = "MAR-AL00", device = "0123456789abcdef"))),
        )
        assertEquals(
            "01234567",
            OtherDeviceText.deviceName(member(snapshot(device = "0123456789abcdef"))),
        )
        assertEquals("未知设备", OtherDeviceText.deviceName(member(snapshot())))
        assertEquals("未知设备", OtherDeviceText.deviceName(member(head = null)))
    }

    @Test
    fun platformLabelNormalisesAndroidAndPcFamilies() {
        assertEquals("Android", OtherDeviceText.platformLabel("opensave-android"))
        assertEquals("Android", OtherDeviceText.platformLabel("Android 14"))
        assertEquals("PC", OtherDeviceText.platformLabel("pc"))
        assertEquals("PC", OtherDeviceText.platformLabel("Windows 11"))
        assertEquals("PC", OtherDeviceText.platformLabel("desktop"))
        assertEquals("PC", OtherDeviceText.platformLabel("macOS"))
        assertEquals("PC", OtherDeviceText.platformLabel("linux"))
        assertEquals("未知平台", OtherDeviceText.platformLabel(""))
        assertEquals("未知平台", OtherDeviceText.platformLabel("   "))
        assertEquals("switch", OtherDeviceText.platformLabel("switch"))
    }

    @Test
    fun detailShowsPlatformTimeVersionCountConflictAndManualLink() {
        val receivedAt = "2025-11-08T20:13:00Z"
        val time = formatShortTime(Instant.parse(receivedAt).toEpochMilli())
        val head = snapshot(receivedAt = receivedAt, platform = "opensave-android")

        assertEquals("Android · $time · 3 个版本", OtherDeviceText.detail(member(head)))
        assertEquals(
            "Android · $time · 3 个版本 · 冲突 1 · 手动关联",
            OtherDeviceText.detail(member(head, conflictCount = 1, link = "manual")),
        )
    }

    @Test
    fun detailFallsBackToCreatedAtAndOmitsUnparsableTimes() {
        val createdAt = "2025-11-08T20:00:00Z"
        val createdTime = formatShortTime(Instant.parse(createdAt).toEpochMilli())

        assertEquals(
            "Android · $createdTime · 3 个版本",
            OtherDeviceText.detail(member(snapshot(receivedAt = "not-a-time", createdAt = createdAt, platform = "android"))),
        )
        assertEquals(
            "未知平台 · 3 个版本",
            OtherDeviceText.detail(
                member(snapshot(receivedAt = "not-a-time", createdAt = "also-not-a-time", platform = "")),
            ),
        )
    }

    @Test
    fun detailWithoutAHeadSaysSo() {
        assertEquals("云端还没有版本", OtherDeviceText.detail(member(head = null)))
    }

    @Test
    fun detailMarksARetiredMemberWithOrWithoutAHead() {
        val receivedAt = "2025-11-08T20:13:00Z"
        val time = formatShortTime(Instant.parse(receivedAt).toEpochMilli())

        assertEquals("云端还没有版本 · 已合并", OtherDeviceText.detail(member(head = null, retired = true)))
        assertEquals(
            "Android · $time · 3 个版本 · 已合并",
            OtherDeviceText.detail(member(snapshot(receivedAt = receivedAt, platform = "opensave-android"), retired = true)),
        )
    }

    @Test
    fun partLabelUsesTheKindOrThePcFolderLabel() {
        assertEquals("Yuzu/Eden Switch", OtherDeviceText.partLabel(CrossDeviceArchivePart("part-0", "yuzu-switch")))
        assertEquals("存档文件夹", OtherDeviceText.partLabel(CrossDeviceArchivePart("", null)))
    }

    private fun snapshot(
        receivedAt: String = "2025-11-08T20:13:00Z",
        createdAt: String = "2025-11-08T20:00:00Z",
        deviceName: String = "",
        device: String = "",
        platform: String = "",
    ) = GameSaveScopeSnapshot(
        id = "snap-1",
        archive = "switch-test.zip",
        parent = "",
        contentDigest = "a".repeat(64),
        createdAt = createdAt,
        receivedAt = receivedAt,
        device = device,
        deviceName = deviceName,
        platform = platform,
    )

    private fun member(
        head: GameSaveScopeSnapshot?,
        conflictCount: Int = 0,
        link: String = "auto",
        snapshotCount: Int = 3,
        retired: Boolean = false,
    ) = GameSaveCloudGameMember(
        type = "switch",
        gameId = "game",
        link = link,
        name = "name",
        head = head,
        snapshotCount = snapshotCount,
        conflictCount = conflictCount,
        retired = retired,
    )
}
