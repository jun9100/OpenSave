package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStatusResolverTest {
    private val source = SourceSelection.Root("/sdcard/Eden")
    private val sourceId = SnapshotNaming.sourceId(source.idSeed)
    private val games = GameLibrary.sourceGames(
        listOf(
            SourceScanResult(
                source = source,
                label = "Eden",
                scan = SaveScanReport(
                    rootPath = source.path,
                    scannedDirectoryCount = 2,
                    detected = listOf(
                        DetectedSave(kind = "gamehub-pc", relativePath = "a", note = "", displayName = "Game A", gameKey = "pc:a"),
                        DetectedSave(kind = "gamehub-pc", relativePath = "b", note = "", displayName = "Game B", gameKey = "pc:b"),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun nothingBackedUpIsNeverBackedUpWhateverTheSignature() {
        assertEquals(GameSyncStatus.NEVER_BACKED_UP, resolve(latest = null, current = "s1:1:1:1"))
        assertEquals(GameSyncStatus.NEVER_BACKED_UP, resolve(latest = null, current = null))
    }

    @Test
    fun aDifferentSignatureIsALocalChange() {
        val latest = record(uploaded = true, signature = "s1:2:20:100")
        assertEquals(GameSyncStatus.LOCAL_CHANGED, resolve(latest, current = "s1:2:21:100"))
        assertEquals(GameSyncStatus.SYNCED, resolve(latest, current = "s1:2:20:100"))
    }

    @Test
    fun anUnknownSignatureOnEitherSideIsNeverReportedAsAChange() {
        // Not read yet, or unreadable.
        assertEquals(GameSyncStatus.SYNCED, resolve(record(uploaded = true, signature = "s1:2:20:100"), current = null))
        // A record from an older build carries no signature.
        assertEquals(GameSyncStatus.NOT_UPLOADED, resolve(record(uploaded = false, signature = ""), current = "s1:2:20:100"))
    }

    @Test
    fun uploadStateDependsOnWhetherGameSaveIsEnabled() {
        val notUploaded = record(uploaded = false, signature = "s1:1:1:1")
        assertEquals(GameSyncStatus.NOT_UPLOADED, resolve(notUploaded, current = "s1:1:1:1", cloudEnabled = true))
        assertEquals(GameSyncStatus.LOCAL_ONLY, resolve(notUploaded, current = "s1:1:1:1", cloudEnabled = false))
    }

    @Test
    fun cloudVerdictsWinAndAStaleSafeRestoreBecomesAConflict() {
        val latest = record(uploaded = true, signature = "s1:1:1:1")
        assertEquals(GameSyncStatus.CONFLICT, resolve(latest, "s1:1:1:1", GameSaveRemoteAction.REVIEW_REQUIRED))
        assertEquals(GameSyncStatus.CLOUD_NEWER, resolve(latest, "s1:1:1:1", GameSaveRemoteAction.AUTO_RESTORE))
        // Local saves changed after the check that found the safe restore.
        assertEquals(GameSyncStatus.CONFLICT, resolve(latest, "s1:9:9:9", GameSaveRemoteAction.AUTO_RESTORE))
        // Waiting for the remote says nothing about this device's saves.
        assertEquals(GameSyncStatus.SYNCED, resolve(latest, "s1:1:1:1", GameSaveRemoteAction.WAIT_FOR_REMOTE))
        assertEquals(GameSyncStatus.CLOUD_NEWER, resolve(null, null, GameSaveRemoteAction.AUTO_RESTORE))
    }

    @Test
    fun anUploadThatDidNotBecomeTheCloudHeadIsNeverSynced() {
        val conflictCopy = record(uploaded = true, signature = "s1:1:1:1").copy(notCloudHead = true)
        assertEquals(GameSyncStatus.CONFLICT, resolve(conflictCopy, "s1:1:1:1"))
        // Saves changed since: the local change is what to act on first.
        assertEquals(GameSyncStatus.LOCAL_CHANGED, resolve(conflictCopy, "s1:2:2:2"))
        assertEquals(GameSyncStatus.SYNCED, resolve(conflictCopy.copy(notCloudHead = false), "s1:1:1:1"))
    }

    @Test
    fun onlyNeverBackedUpAndLocalChangesNeedABackup() {
        assertEquals(
            setOf(GameSyncStatus.NEVER_BACKED_UP, GameSyncStatus.LOCAL_CHANGED),
            GameSyncStatus.entries.filter { it.needsBackup }.toSet(),
        )
        assertFalse(GameSyncStatus.CONFLICT.needsBackup)
        assertTrue(GameSyncStatus.LOCAL_CHANGED.needsBackup)
    }

    @Test
    fun resolveAllUsesTheNewestOrdinarySnapshotPerGame() {
        val (a, b) = games
        val snapshots = listOf(
            record(uploaded = true, signature = "s1:1:1:1", gameKey = "pc:a", createdAt = 100),
            // Newer, but a safety copy taken before a restore: not a backup.
            record(uploaded = false, signature = "s1:5:5:5", gameKey = "pc:a", createdAt = 300, comment = "恢复前安全副本"),
            record(uploaded = false, signature = "s1:2:2:2", gameKey = "pc:b", createdAt = 50),
            record(uploaded = true, signature = "s1:3:3:3", gameKey = "pc:b", createdAt = 200),
            // Another source's snapshot of the same game key.
            record(uploaded = true, signature = "s1:1:1:1", gameKey = "pc:a", createdAt = 400, sourceId = "other"),
        )
        val statuses = GameStatusResolver.resolveAll(
            entries = games,
            snapshots = snapshots,
            signatures = mapOf(a.libraryKey to "s1:1:1:1", b.libraryKey to "s1:2:2:2"),
            cloudActions = emptyMap(),
            cloudEnabled = true,
        )
        assertEquals(GameSyncStatus.SYNCED, statuses[a.libraryKey])
        // b's saves went back to an older state than its newest backup: still a change.
        assertEquals(GameSyncStatus.LOCAL_CHANGED, statuses[b.libraryKey])
        assertEquals(2, statuses.size)
    }

    private fun resolve(
        latest: SnapshotRecord?,
        current: String?,
        cloud: GameSaveRemoteAction? = null,
        cloudEnabled: Boolean = true,
    ) = GameStatusResolver.resolve(latest, current, cloud, cloudEnabled)

    @Test
    fun rememberedVerdictHoldsUntilANewerSnapshotAndLiveOnesWin() {
        val key = "$sourceId::pc:a"
        val first = record(uploaded = true, signature = "s1:1:1:1", createdAt = 1)
        val remembered = mapOf(key to CloudVerdict(GameSaveRemoteAction.REVIEW_REQUIRED, first.id))

        assertEquals(
            mapOf(key to GameSaveRemoteAction.REVIEW_REQUIRED),
            GameStatusResolver.effectiveCloudActions(emptyList(), remembered, listOf(first)),
        )
        // A later backup or restore describes different local saves: the verdict is stale.
        val second = record(uploaded = true, signature = "s1:1:2:1", createdAt = 2)
        assertTrue(GameStatusResolver.effectiveCloudActions(emptyList(), remembered, listOf(first, second)).isEmpty())
        // A safety copy is not a newer description of the saves.
        val safety = record(uploaded = false, signature = "", createdAt = 3, comment = "恢复前安全副本")
        assertEquals(
            mapOf(key to GameSaveRemoteAction.REVIEW_REQUIRED),
            GameStatusResolver.effectiveCloudActions(emptyList(), remembered, listOf(first, safety)),
        )
        // "Up to date" is never kept, and a game never backed up keys on an empty record id.
        val neverBackedUp = mapOf("$sourceId::pc:b" to CloudVerdict(GameSaveRemoteAction.AUTO_RESTORE, ""))
        assertEquals(
            mapOf("$sourceId::pc:b" to GameSaveRemoteAction.AUTO_RESTORE),
            GameStatusResolver.effectiveCloudActions(
                emptyList(),
                neverBackedUp + (key to CloudVerdict(GameSaveRemoteAction.UP_TO_DATE, first.id)),
                listOf(first),
            ),
        )
    }

    @Test
    fun verdictStoreRoundTripsAndSkipsUnknownActions() {
        val verdicts = mapOf(
            "a::x" to CloudVerdict(GameSaveRemoteAction.REVIEW_REQUIRED, "r1"),
            "b::y" to CloudVerdict(GameSaveRemoteAction.AUTO_RESTORE, ""),
        )
        assertEquals(verdicts, CloudVerdictStore.decode(CloudVerdictStore.encode(verdicts)))
        assertEquals(
            mapOf("a::x" to CloudVerdict(GameSaveRemoteAction.WAIT_FOR_REMOTE, "r")),
            CloudVerdictStore.decode("""{"a::x":{"action":"WAIT_FOR_REMOTE","record":"r"},"b::y":{"action":"GONE"}}"""),
        )
        assertTrue(CloudVerdictStore.decode("not json").isEmpty())
    }

    private fun record(
        uploaded: Boolean,
        signature: String,
        gameKey: String = "pc:a",
        createdAt: Long = 1,
        comment: String = "",
        sourceId: String = this.sourceId,
    ) = SnapshotRecord(
        id = "$gameKey-$createdAt-$sourceId",
        sourceId = sourceId,
        remoteName = "",
        localPath = "",
        createdAtMillis = createdAt,
        sizeBytes = 1,
        sha256 = "",
        manifestHash = "",
        uploaded = uploaded,
        comment = comment,
        gameKey = gameKey,
        saveSignature = signature,
    )
}
