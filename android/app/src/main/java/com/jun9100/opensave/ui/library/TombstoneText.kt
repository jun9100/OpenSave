package com.jun9100.opensave.ui.library

import com.jun9100.opensave.ScopeTombstone
import com.jun9100.opensave.ui.formatShortTime
import java.time.Instant

/** One action of a tombstone card; the detail pane maps each to its callback. */
internal enum class TombstoneButton(val label: String) {
    STOP_DELETED("不再同步这个游戏"),
    STOP_RETIRED("不再同步这一项（推荐）"),
    DISMISS("稍后再说"),
    EXPAND("处理"),
    RESUME("重新开启云同步"),
}

/** Everything a tombstone card shows; plain data so the wording choice can be unit tested. */
internal data class TombstoneCardText(
    /** Collapsed to one small line plus [buttons] (acknowledged, or put off for this run). */
    val collapsed: Boolean,
    val title: String,
    val body: List<String>,
    val buttons: List<TombstoneButton>,
)

/**
 * Wording of the cloud-stop card of one game (C6). The enum keeps the generic
 * status labels; only the detail pane knows the successor's name.
 */
internal object TombstoneText {
    /** "已合并到「X」": the successor's title, else its id, else a generic name. */
    fun successorLabel(tombstone: ScopeTombstone): String =
        tombstone.successorName.ifBlank { tombstone.successorGameId.ifBlank { OTHER_GAME } }

    fun card(
        tombstone: ScopeTombstone,
        dismissed: Boolean,
        localSuccessorName: String? = null,
    ): TombstoneCardText {
        val successor = successorLabel(tombstone)
        // Acknowledged or put off: the stop is already handled, only a compact reminder stays.
        if (tombstone.acknowledged || dismissed) {
            return TombstoneCardText(
                collapsed = true,
                title = "",
                body = listOf(
                    if (tombstone.retired) {
                        "已合并到「$successor」，已停止云同步"
                    } else {
                        "云端存档已被删除，已停止云同步"
                    },
                ),
                buttons = buildList {
                    add(TombstoneButton.RESUME)
                    // "稍后再说" is not a decision: offer the way back into the card.
                    if (dismissed && !tombstone.acknowledged) add(TombstoneButton.EXPAND)
                },
            )
        }
        if (tombstone.retired) {
            return TombstoneCardText(
                collapsed = false,
                title = "云端存档已合并",
                body = buildList {
                    add("这个游戏的云端存档已合并到「$successor」。本机可能重复跟踪了同一个游戏。")
                    if (localSuccessorName != null) {
                        add("本机也在同步「$localSuccessorName」，这一项是重复的，建议不再同步。")
                    }
                },
                buttons = listOf(TombstoneButton.STOP_RETIRED, TombstoneButton.DISMISS),
            )
        }
        val time = deletedAtLabel(tombstone.at)
        return TombstoneCardText(
            collapsed = false,
            title = "云端存档已被删除",
            body = listOf(
                "这个游戏的云端存档已被管理员删除${time?.let { "（$it）" }.orEmpty()}。本机存档不受影响，已停止云同步。",
            ),
            buttons = listOf(TombstoneButton.STOP_DELETED, TombstoneButton.DISMISS),
        )
    }

    /** "10-30 21:49" for the server's `deletedAt`; null when blank or not a timestamp. */
    fun deletedAtLabel(at: String): String? {
        val millis = runCatching { Instant.parse(at.trim()).toEpochMilli() }.getOrNull() ?: return null
        return runCatching { formatShortTime(millis) }.getOrNull()
    }

    private const val OTHER_GAME = "其他游戏"
}
