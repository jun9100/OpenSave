package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncInboxTest {
    private val source = SourceSelection.Root("/sdcard/Eden")
    private val games = GameLibrary.sourceGames(
        listOf(
            SourceScanResult(
                source = source,
                label = "Eden",
                scan = SaveScanReport(
                    rootPath = source.path,
                    scannedDirectoryCount = 3,
                    detected = listOf(
                        DetectedSave(kind = "gamehub-pc", relativePath = "a", note = "", displayName = "Alpha", gameKey = "pc:a"),
                        DetectedSave(kind = "gamehub-pc", relativePath = "b", note = "", displayName = "Beta", gameKey = "pc:b"),
                        DetectedSave(kind = "gamehub-pc", relativePath = "c", note = "", displayName = "Gamma", gameKey = "pc:c"),
                    ),
                ),
            ),
        ),
    )
    private val alpha = games.single { it.game.gameKey == "pc:a" }
    private val beta = games.single { it.game.gameKey == "pc:b" }
    private val gamma = games.single { it.game.gameKey == "pc:c" }

    @Test
    fun listsConflictsFirstWithTheCheckOwnReason() {
        val statuses = mapOf(
            alpha.libraryKey to GameSyncStatus.CLOUD_NEWER,
            beta.libraryKey to GameSyncStatus.CONFLICT,
            gamma.libraryKey to GameSyncStatus.SYNCED,
        )
        val live = listOf(
            update(alpha, GameSaveRemoteAction.AUTO_RESTORE, "Windows-PC 有延续当前存档的更新"),
            update(beta, GameSaveRemoteAction.REVIEW_REQUIRED, "远端存档与本机历史分叉，已等待确认"),
        )
        val items = SyncInbox.items(games, statuses, live, GameStatusResolver.cloudActions(live))
        assertEquals(listOf(beta.libraryKey, alpha.libraryKey), items.map { it.key })
        assertEquals(SyncItemKind.CONFLICT, items[0].kind)
        assertEquals("远端存档与本机历史分叉，已等待确认", items[0].reason)
        assertEquals("Windows-PC 有延续当前存档的更新", items[1].reason)
        assertEquals("1 款冲突需确认 · 1 款云端较新", SyncInbox.bannerText(items))
    }

    @Test
    fun aSafeRestoreWhoseSavesChangedSinceSaysSo() {
        val live = listOf(update(alpha, GameSaveRemoteAction.AUTO_RESTORE, "Windows-PC 有延续当前存档的更新"))
        val items = SyncInbox.items(games, mapOf(alpha.libraryKey to GameSyncStatus.CONFLICT), live, emptyMap())
        assertEquals("Windows-PC 有延续当前存档的更新；但本机存档之后也有变化", items.single().reason)
    }

    @Test
    fun rememberedVerdictsAndConflictCopiesAreListedWithoutALiveCheck() {
        val statuses = mapOf(
            alpha.libraryKey to GameSyncStatus.CONFLICT,
            beta.libraryKey to GameSyncStatus.CLOUD_NEWER,
            gamma.libraryKey to GameSyncStatus.CONFLICT,
        )
        val remembered = mapOf(
            alpha.libraryKey to GameSaveRemoteAction.REVIEW_REQUIRED,
            beta.libraryKey to GameSaveRemoteAction.AUTO_RESTORE,
        )
        val items = SyncInbox.items(games, statuses, emptyList(), remembered).associateBy { it.key }
        assertTrue(items.values.all { it.live == null })
        assertEquals("上次检查发现需要确认的云端版本；处理前会重新检查云端", items.getValue(alpha.libraryKey).reason)
        assertEquals("上次检查发现云端有延续当前存档的更新", items.getValue(beta.libraryKey).reason)
        // No verdict at all: the newest local version is a conflict copy.
        assertEquals("本机最新版本是冲突副本，云端当前版本不是它", items.getValue(gamma.libraryKey).reason)
    }

    @Test
    fun laterHidesAGameForTheSessionAndAnEmptyInboxHasNoBanner() {
        val statuses = mapOf(alpha.libraryKey to GameSyncStatus.CONFLICT)
        val items = SyncInbox.items(games, statuses, emptyList(), emptyMap(), dismissed = setOf(alpha.libraryKey))
        assertTrue(items.isEmpty())
        assertNull(SyncInbox.bannerText(items))
        assertNull(SyncInbox.bannerText(emptyList()))
    }

    private fun update(entry: SourceGameEntry, action: GameSaveRemoteAction, message: String) =
        GameSaveCloudUpdate(entry, nativeDecision = GameSaveNativeDecision(action = action, message = message))
}
