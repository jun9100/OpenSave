package com.jun9100.opensave.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ActivityLogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun entry(
        text: String,
        at: Long,
        kind: ActivityKind = ActivityKind.AUTO_BACKUP,
        level: ActivityLevel = ActivityLevel.INFO,
        needsAction: Boolean = false,
    ) = ActivityEntry(id = "id-$text-$at", atMillis = at, kind = kind, level = level, text = text, needsAction = needsAction)

    @Test
    fun newestFirstAndCappedAtTheLimit() {
        var entries = emptyList<ActivityEntry>()
        repeat(ActivityLog.MAX_ENTRIES + 5) { index ->
            entries = ActivityLog.append(entries, entry("扫描 $index", at = index.toLong(), kind = ActivityKind.SCAN)).entries
        }
        assertEquals(ActivityLog.MAX_ENTRIES, entries.size)
        assertEquals("扫描 ${ActivityLog.MAX_ENTRIES + 4}", entries.first().text)
        assertEquals("扫描 5", entries.last().text)
    }

    @Test
    fun anIdenticalRecentLineIsFoldedAndMovedToTheTop() {
        val skipped = entry("自动备份已跳过：game-save 未配置", at = 1)
        val scan = entry("扫描完成", at = 2, kind = ActivityKind.SCAN)
        val start = ActivityLog.append(ActivityLog.append(emptyList(), skipped).entries, scan).entries

        val again = ActivityLog.append(start, entry("自动备份已跳过：game-save 未配置", at = 3))

        assertTrue(again.folded)
        assertEquals(2, again.entries.size)
        assertEquals(skipped.id, again.entries.first().id)
        assertEquals(2, again.entries.first().repeats)
        assertEquals(3L, again.entries.first().atMillis)
    }

    @Test
    fun aLineThatDiffersInLevelOrActionIsNotFolded() {
        val start = ActivityLog.append(emptyList(), entry("游戏 A：云端有冲突", at = 1, level = ActivityLevel.WARNING, needsAction = true)).entries
        val other = ActivityLog.append(start, entry("游戏 A：云端有冲突", at = 2, level = ActivityLevel.WARNING))
        assertFalse(other.folded)
        assertEquals(2, other.entries.size)
        assertEquals(1, ActivityLog.needingAction(other.entries).size)
    }

    @Test
    fun linesOlderThanTheFoldWindowAreNotFolded() {
        var entries = ActivityLog.append(emptyList(), entry("同一行", at = 0)).entries
        repeat(ActivityLog.FOLD_WINDOW) { index ->
            entries = ActivityLog.append(entries, entry("其他 $index", at = index + 1L, kind = ActivityKind.SCAN)).entries
        }
        val appended = ActivityLog.append(entries, entry("同一行", at = 100))
        assertFalse(appended.folded)
        assertEquals(2, appended.entries.count { it.text == "同一行" })
    }

    @Test
    fun storeRoundTripsAndSkipsUnknownKinds() {
        val file = folder.root.resolve("activity.json")
        val store = ActivityLogStore(file)
        assertEquals(emptyList<ActivityEntry>(), store.list())
        val first = entry("备份 \"A\"\n完成", at = 1, kind = ActivityKind.BACKUP, level = ActivityLevel.SUCCESS)
        val second = entry("后台没有获得 Root 权限", at = 2, kind = ActivityKind.PERMISSION, level = ActivityLevel.WARNING, needsAction = true)
            .copy(detail = "请在 Magisk 中选择“永久”")
        store.add(first)
        val appended = store.add(second)
        assertFalse(appended.folded)
        assertEquals(listOf(second, first), ActivityLogStore(file).list())

        val withUnknown = file.readText().replaceFirst("\"PERMISSION\"", "\"FROM_THE_FUTURE\"")
        assertEquals(listOf(first), ActivityLog.decode(withUnknown))
        assertEquals(emptyList<ActivityEntry>(), ActivityLog.decode("not json"))
    }
}
