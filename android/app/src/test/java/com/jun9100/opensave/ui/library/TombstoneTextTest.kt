package com.jun9100.opensave.ui.library

import com.jun9100.opensave.ScopeTombstone
import com.jun9100.opensave.ui.formatShortTime
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TombstoneTextTest {
    @Test
    fun deletedCardShowsTheTimeAndOffersStopThenLater() {
        val at = "2025-11-08T20:13:00Z"
        val text = TombstoneText.card(tombstone(at = at), dismissed = false)

        assertFalse(text.collapsed)
        assertEquals("云端存档已被删除", text.title)
        assertEquals(
            listOf(
                "这个游戏的云端存档已被管理员删除（${formatShortTime(Instant.parse(at).toEpochMilli())}）。" +
                    "本机存档不受影响，已停止云同步。",
            ),
            text.body,
        )
        assertEquals(listOf(TombstoneButton.STOP_DELETED, TombstoneButton.DISMISS), text.buttons)
    }

    @Test
    fun anUnparsableDeletedTimeLeavesOutTheParentheses() {
        val text = TombstoneText.card(tombstone(at = "yesterday"), dismissed = false)

        assertEquals(
            listOf("这个游戏的云端存档已被管理员删除。本机存档不受影响，已停止云同步。"),
            text.body,
        )
        assertEquals(null, TombstoneText.deletedAtLabel(""))
        assertEquals(null, TombstoneText.deletedAtLabel("not-a-time"))
    }

    @Test
    fun retiredCardNamesTheSuccessorAndTheLocalDuplicate() {
        val text = TombstoneText.card(
            tombstone(state = ScopeTombstone.STATE_RETIRED, successorName = "游戏 B"),
            dismissed = false,
            localSuccessorName = "本机游戏 B",
        )

        assertFalse(text.collapsed)
        assertEquals("云端存档已合并", text.title)
        assertEquals(
            listOf(
                "这个游戏的云端存档已合并到「游戏 B」。本机可能重复跟踪了同一个游戏。",
                "本机也在同步「本机游戏 B」，这一项是重复的，建议不再同步。",
            ),
            text.body,
        )
        assertEquals(listOf(TombstoneButton.STOP_RETIRED, TombstoneButton.DISMISS), text.buttons)
    }

    @Test
    fun retiredCardFallsBackToTheSuccessorIdWithoutALocalDuplicate() {
        val text = TombstoneText.card(
            tombstone(state = ScopeTombstone.STATE_RETIRED, successorGameId = "android-emu-123"),
            dismissed = false,
        )

        assertEquals(
            listOf("这个游戏的云端存档已合并到「android-emu-123」。本机可能重复跟踪了同一个游戏。"),
            text.body,
        )
        assertEquals("其他游戏", TombstoneText.successorLabel(tombstone(state = ScopeTombstone.STATE_RETIRED)))
    }

    @Test
    fun anAcknowledgedTombstoneCollapsesWithoutAnExpandButton() {
        val text = TombstoneText.card(tombstone(acknowledged = true), dismissed = false)

        assertTrue(text.collapsed)
        assertEquals(listOf("云端存档已被删除，已停止云同步"), text.body)
        assertEquals(listOf(TombstoneButton.RESUME), text.buttons)
    }

    @Test
    fun aDismissedTombstoneCollapsesAndOffersToHandleItAgain() {
        val text = TombstoneText.card(tombstone(), dismissed = true)

        assertTrue(text.collapsed)
        assertEquals(listOf("云端存档已被删除，已停止云同步"), text.body)
        assertEquals(listOf(TombstoneButton.RESUME, TombstoneButton.EXPAND), text.buttons)
    }

    @Test
    fun aDismissedRetiredTombstoneNamesTheSuccessor() {
        val text = TombstoneText.card(
            tombstone(state = ScopeTombstone.STATE_RETIRED, successorName = "游戏 B"),
            dismissed = true,
        )

        assertTrue(text.collapsed)
        assertEquals(listOf("已合并到「游戏 B」，已停止云同步"), text.body)
        // "稍后再说" is not an acknowledgement: handling it again stays possible.
        assertEquals(listOf(TombstoneButton.RESUME, TombstoneButton.EXPAND), text.buttons)
    }

    private fun tombstone(
        state: String = ScopeTombstone.STATE_DELETED,
        at: String = "",
        successorType: String = "",
        successorGameId: String = "",
        successorName: String = "",
        acknowledged: Boolean = false,
    ) = ScopeTombstone(
        type = "pc",
        gameId = "game-a",
        state = state,
        at = at,
        successorType = successorType,
        successorGameId = successorGameId,
        successorName = successorName,
        acknowledged = acknowledged,
        seenAtMillis = 1,
    )
}
