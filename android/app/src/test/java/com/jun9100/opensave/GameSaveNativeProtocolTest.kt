package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSaveNativeProtocolTest {
    @Test
    fun createsDeviceUniqueNamesWithoutChangingTheTypedScope() {
        val identity = GameCloudIdentity(GameSaveTypes.SWITCH, "switch-0100000000010000", desktopCompatible = true)

        assertEquals(
            "snap_1730000000000--dev-odin2a",
            GameSaveNativeProtocol.remoteSnapshotId("snap_1730000000000", "Odin-2 A"),
        )
        assertEquals(
            "switch-0100000000010000__main__snap_1730000000000--dev-odin2a.zip",
            GameSaveNativeProtocol.archiveName(identity, "snap_1730000000000", "Odin-2 A"),
        )
        assertEquals(GameSaveTypes.SWITCH.id, identity.saveType.id)
    }

    @Test
    fun restoresOnlyAnUnchangedDescendantOfTheConfirmedNativeHead() {
        val identity = identity()
        val parent = "snap_1730000000000--dev-desktop1"
        val head = "snap_1730000100000--dev-odin2"
        val root = snapshot(identity, parent, "")
        val current = snapshot(identity, head, parent)
        val scope = scope(identity, head = current, snapshots = listOf(current, root))
        val state = localState(parent, nativeHead = parent, manifestHash = "same")

        val decision = GameSaveNativeResolver.decide(
            identity = identity,
            device = device(),
            localState = state,
            currentManifestHash = "same",
            scope = scope,
        )

        assertEquals(GameSaveRemoteAction.AUTO_RESTORE, decision.action)
        assertEquals(head, decision.candidate?.snapshot?.id)
    }

    @Test
    fun changedUnknownAndPendingLocalSavesRequireReview() {
        val identity = identity()
        val parent = "snap_1730000000000--dev-desktop1"
        val head = "snap_1730000100000--dev-odin2"
        val root = snapshot(identity, parent, "")
        val current = snapshot(identity, head, parent)
        val scope = scope(identity, head = current, snapshots = listOf(current, root))

        val changed = GameSaveNativeResolver.decide(identity, device(), localState(parent, parent, "before"), "after", scope)
        val unknown = GameSaveNativeResolver.decide(identity, device(), null, "", scope)
        val pending = GameSaveNativeResolver.decide(
            identity,
            device(),
            localState("snap_1730000200000", parent, "same"),
            "same",
            scope,
        )

        assertEquals(GameSaveRemoteAction.REVIEW_REQUIRED, changed.action)
        assertEquals(GameSaveRemoteAction.REVIEW_REQUIRED, unknown.action)
        assertEquals(GameSaveRemoteAction.REVIEW_REQUIRED, pending.action)
    }

    @Test
    fun forkedOrCyclicHistoryNeverCountsAsAContinuation() {
        val identity = identity()
        val head = "snap_1730000100000--dev-odin2"
        val other = "snap_1730000200000--dev-desktop1"
        val cyclicHead = snapshot(identity, head, other)
        val cyclicOther = snapshot(identity, other, head)
        val scope = scope(identity, cyclicHead, listOf(cyclicHead, cyclicOther))

        assertFalse(GameSaveNativeProtocol.follows(scope, "snap_1730000000000--dev-desktop1"))
        assertFalse(GameSaveNativeProtocol.follows(scope, head))
    }

    private fun identity() = GameCloudIdentity(
        saveType = GameSaveTypes.SWITCH,
        gameId = "switch-0100000000010000",
        desktopCompatible = true,
    )

    private fun device() = GameSaveDeviceIdentity("odin2", "Odin2")

    private fun scope(
        identity: GameCloudIdentity,
        head: GameSaveScopeSnapshot,
        snapshots: List<GameSaveScopeSnapshot>,
    ) = GameSaveScope(identity.saveType.id, identity.gameId, head, snapshots, emptyList())

    private fun snapshot(identity: GameCloudIdentity, id: String, parent: String) = GameSaveScopeSnapshot(
        id = id,
        archive = "${identity.gameId}__main__${id}.zip",
        parent = parent,
        contentDigest = "a".repeat(64),
        createdAt = "2026-10-26T00:00:00Z",
        receivedAt = "2026-10-26T00:00:00Z",
        deviceName = "Other device",
    )

    private fun localState(snapshotId: String, nativeHead: String, manifestHash: String) = GameSaveHeadState(
        snapshotId = snapshotId,
        fileName = "switch-0100000000010000__main__${snapshotId}.zip",
        manifestHash = manifestHash,
        chain = listOf(snapshotId),
        savedAtMillis = 1_730_000_000_000L,
        nativeHead = nativeHead,
    )
}
