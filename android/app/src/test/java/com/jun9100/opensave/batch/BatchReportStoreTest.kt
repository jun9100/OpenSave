package com.jun9100.opensave.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BatchReportStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun report(index: Int, endReason: String? = null) = BatchReport(
        id = "report-$index",
        operation = BatchOperation.entries[index % BatchOperation.entries.size],
        startedAtMillis = 1_000L * index,
        finishedAtMillis = 1_000L * index + 500,
        items = listOf(
            BatchItemResult("src::game-$index", "游戏 $index", BatchItemStatus.SUCCESS),
            BatchItemResult("src::other", "Other \"quoted\"\n名字", BatchItemStatus.FAILED, "HTTP 502 - 服务器错误"),
        ),
        endReason = endReason,
    )

    @Test
    fun reportsRoundTripThroughTheFileNewestFirst() {
        val store = BatchReportStore(folder.root.resolve("reports.json"))
        assertNull(store.latest())
        val first = report(1)
        val second = report(2, endReason = BatchRunner.CANCELLED)
        store.add(first)
        store.add(second)

        val reopened = BatchReportStore(folder.root.resolve("reports.json"))
        assertEquals(listOf(second, first), reopened.list())
        assertEquals(second, reopened.latest())
    }

    @Test
    fun onlyTheLastTwentyReportsAreKept() {
        val store = BatchReportStore(folder.root.resolve("reports.json"))
        (1..25).forEach { store.add(report(it)) }
        val kept = store.list()
        assertEquals(BatchReportStore.MAX_REPORTS, kept.size)
        assertEquals("report-25", kept.first().id)
        assertEquals("report-6", kept.last().id)
    }

    @Test
    fun theSameReportIsStoredOnce() {
        val store = BatchReportStore(folder.root.resolve("reports.json"))
        store.add(report(1, endReason = BatchRunner.INTERRUPTED))
        store.add(report(1))
        assertEquals(listOf(report(1)), store.list())
    }

    @Test
    fun aDamagedFileReadsAsNoReportsAndUnknownOperationsAreDropped() {
        val file = folder.root.resolve("reports.json")
        file.writeText("{not json")
        assertTrue(BatchReportStore(file).list().isEmpty())

        val valid = BatchReportStore.encode(listOf(report(1)))
        val withUnknown = valid.replaceFirst("[", """[{"id":"x","operation":"FUTURE","startedAt":0,"finishedAt":0,"items":[]},""")
        assertEquals(listOf(report(1)), BatchReportStore.decode(withUnknown))
    }
}
