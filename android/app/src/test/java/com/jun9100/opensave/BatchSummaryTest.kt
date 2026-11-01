package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchSummaryTest {
    @Test
    fun backupSummaryListsOnlyNonZeroCounts() {
        assertEquals(
            "批量备份完成：新建版本 3 款，无变化 2 款",
            BatchSummary.backup(created = 3, unchanged = 2, failed = 0, uploadFailed = 0, cloudPending = 0),
        )
        assertEquals(
            "批量备份完成：失败 1 款，云端上传失败 2 款，跨设备待处理 1 款",
            BatchSummary.backup(created = 0, unchanged = 0, failed = 1, uploadFailed = 2, cloudPending = 1),
        )
        assertEquals(
            "批量备份完成：没有处理任何游戏",
            BatchSummary.backup(created = 0, unchanged = 0, failed = 0, uploadFailed = 0, cloudPending = 0),
        )
    }

    @Test
    fun countsKeepOrderAndUnit() {
        assertEquals("跳过 2 个", BatchSummary.counts(listOf("可读" to 0, "跳过" to 2), unit = "个"))
        assertEquals("", BatchSummary.counts(emptyList()))
    }
}
