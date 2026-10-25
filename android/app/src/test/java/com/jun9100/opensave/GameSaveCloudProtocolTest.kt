package com.jun9100.opensave

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSaveCloudProtocolTest {
    @Test
    fun readsTheNewestPreservedSaveMtimeFromPortableSnapshots() {
        val root = createTempDir(prefix = "opensave-head-clock-")
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "older.dat").apply {
                writeText("older")
                setLastModified(1_730_000_000_000L)
            }
            File(source, "newer.dat").apply {
                writeText("newer")
                setLastModified(1_730_000_020_000L)
            }
            val archive = File(root, "save.zip")
            SnapshotEngine().createSnapshot(source, archive).getOrThrow()

            assertEquals(1_730_000_020_000L, GameSaveSnapshotClock.contentModifiedAtMillis(archive))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writesDesktopCompatibleHeadArchive() {
        val root = createTempDirectory("opensave-head-").toFile()
        try {
            val snapshot = "snap_1730000000000"
            val archive = "switch-0100000000010000__main__${snapshot}.zip"
            val head = GameSaveCloudHead(
                deviceId = "android-device-123",
                deviceName = "Odin2",
                gameId = "switch-0100000000010000",
                snapshot = snapshot,
                file = archive,
                chain = listOf("snap_1729999999000", snapshot),
                savedAt = "2026-10-24T00:00:00Z",
            )
            val file = File(root, GameSaveCloudProtocol.headFileName(head.gameId, head.deviceId, 1730000000001))

            GameSaveCloudProtocol.writeHead(file, head)

            assertEquals(
                "switch-0100000000010000__head-androiddevice123-1730000000001.zip",
                file.name,
            )
            assertEquals(head, GameSaveCloudProtocol.readHead(file))
            assertEquals(head.gameId, GameSaveCloudProtocol.parseHeadFileName(file.name))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun parsesOnlySafePortableSnapshotNames() {
        val parsed = GameSaveCloudProtocol.parseArchiveName(
            "switch-0100000000010000__main__snap_1730000000000.zip",
        )

        requireNotNull(parsed)
        assertEquals("switch-0100000000010000", parsed.gameId)
        assertEquals("main", parsed.branch)
        assertEquals("snap_1730000000000", parsed.snapshotId)
        assertEquals(1730000000000L, parsed.timestampMillis)
        assertEquals(null, GameSaveCloudProtocol.parseArchiveName("switch-game__main__manual.zip"))
        assertEquals(null, GameSaveCloudProtocol.parseArchiveName("../switch-game__main__snap_1.zip"))
        assertEquals("other", GameSaveCloudProtocol.parseArchiveName("switch-game__other__snap_1.zip")?.branch)
        assertEquals(null, GameSaveCloudProtocol.parseArchiveName("switch-game__feature/test__snap_1.zip"))
    }

    @Test
    fun retainsOnlyTheNewestBoundedChain() {
        val chain = (1..GameSaveCloudProtocol.MAX_HEAD_CHAIN)
            .map { "snap_$it" }
        val next = GameSaveCloudProtocol.appendChain(chain, "snap_65")

        assertEquals(GameSaveCloudProtocol.MAX_HEAD_CHAIN, next.size)
        assertFalse(next.contains("snap_1"))
        assertEquals("snap_65", next.last())
        assertEquals(next, GameSaveCloudProtocol.appendChain(next, "snap_65"))
        assertTrue(GameSaveCloudProtocol.deviceKey("A_B-C 12").contains("abc12"))
    }

    @Test
    fun resolverAutoRestoresOnlyAContinuousHeadOverAnUnchangedBaseline() {
        val identity = switchIdentity()
        val localSnapshot = "snap_1730000000000"
        val remoteSnapshot = "snap_1730000100000"
        val remoteArchive = archive(identity, remoteSnapshot)
        val announcement = announcement(identity, "desktop-1", 1730000200000L)
        val decision = GameSaveHeadResolver.decide(
            identity = identity,
            localDeviceId = "odin-2",
            localState = localState(localSnapshot, "same-content"),
            currentManifestHash = "same-content",
            remoteSnapshots = listOf(announcement, remoteArchive),
            readHead = {
                GameSaveCloudHead(
                    deviceId = "desktop-1",
                    deviceName = "Windows Eden",
                    gameId = identity.gameId,
                    snapshot = remoteSnapshot,
                    file = remoteArchive.name,
                    chain = listOf(localSnapshot, remoteSnapshot),
                    savedAt = "2024-10-27T11:35:00Z",
                    contentModifiedAt = "2024-10-27T11:35:00Z",
                )
            },
        )

        assertEquals(GameSaveRemoteAction.AUTO_RESTORE, decision.action)
        assertEquals(remoteSnapshot, decision.candidate?.head?.snapshot)
        assertTrue(decision.candidate?.followsLocal == true)
    }

    @Test
    fun resolverRequiresReviewForChangedUnknownOrForkedSaves() {
        val identity = switchIdentity()
        val localSnapshot = "snap_1730000000000"
        val remoteSnapshot = "snap_1730000100000"
        val remoteArchive = archive(identity, remoteSnapshot)
        val announcement = announcement(identity, "desktop-1", 1730000200000L)
        val head = GameSaveCloudHead(
            deviceId = "desktop-1",
            deviceName = "Windows Eden",
            gameId = identity.gameId,
            snapshot = remoteSnapshot,
            file = remoteArchive.name,
            chain = listOf("snap_1729999000000", remoteSnapshot),
            savedAt = "2024-10-27T11:35:00Z",
        )

        val changed = GameSaveHeadResolver.decide(
            identity = identity,
            localDeviceId = "odin-2",
            localState = localState(localSnapshot, "before"),
            currentManifestHash = "after",
            remoteSnapshots = listOf(announcement, remoteArchive),
            readHead = { head },
        )
        val unknown = GameSaveHeadResolver.decide(
            identity = identity,
            localDeviceId = "odin-2",
            localState = null,
            currentManifestHash = "",
            remoteSnapshots = listOf(announcement, remoteArchive),
            readHead = { head },
        )

        assertEquals(GameSaveRemoteAction.REVIEW_REQUIRED, changed.action)
        assertEquals(GameSaveLocalSaveState.CHANGED, changed.localState)
        assertEquals(GameSaveRemoteAction.REVIEW_REQUIRED, unknown.action)
        assertEquals(GameSaveLocalSaveState.UNKNOWN, unknown.localState)
    }

    @Test
    fun resolverWaitsForTheArchiveInsteadOfPublishingPastAnIncompleteHead() {
        val identity = switchIdentity()
        val remoteSnapshot = "snap_1730000100000"
        val announcement = announcement(identity, "desktop-1", 1730000200000L)
        val missingArchiveName = "${identity.gameId}__main__${remoteSnapshot}.zip"
        val decision = GameSaveHeadResolver.decide(
            identity = identity,
            localDeviceId = "odin-2",
            localState = localState("snap_1730000000000", "same-content"),
            currentManifestHash = "same-content",
            remoteSnapshots = listOf(announcement),
            readHead = {
                GameSaveCloudHead(
                    deviceId = "desktop-1",
                    deviceName = "Windows Eden",
                    gameId = identity.gameId,
                    snapshot = remoteSnapshot,
                    file = missingArchiveName,
                    chain = listOf("snap_1730000000000", remoteSnapshot),
                    savedAt = "2024-10-27T11:35:00Z",
                )
            },
        )

        assertEquals(GameSaveRemoteAction.WAIT_FOR_REMOTE, decision.action)
    }

    @Test
    fun resolverIgnoresThisDevicesOwnAnnouncements() {
        val identity = switchIdentity()
        val localSnapshot = "snap_1730000000000"
        val remoteArchive = archive(identity, localSnapshot)
        val ownAnnouncement = announcement(identity, "odin-2", 1730000200000L)
        val decision = GameSaveHeadResolver.decide(
            identity = identity,
            localDeviceId = "odin-2",
            localState = localState(localSnapshot, "same-content"),
            currentManifestHash = "same-content",
            remoteSnapshots = listOf(ownAnnouncement, remoteArchive),
            readHead = { error("own announcement must not be read") },
        )

        assertEquals(GameSaveRemoteAction.UP_TO_DATE, decision.action)
    }

    @Test
    fun resolverNeverAutoRestoresLegacyOrLateUploadedOlderContent() {
        val identity = switchIdentity()
        val localSnapshot = "snap_1730000000000"
        val remoteSnapshot = "snap_1730000100000"
        val remoteArchive = archive(identity, remoteSnapshot)
        val announcement = announcement(identity, "desktop-1", 1730000200000L)
        val local = localState(localSnapshot, "same-content")

        val legacy = GameSaveHeadResolver.decide(
            identity = identity,
            localDeviceId = "odin-2",
            localState = local,
            currentManifestHash = "same-content",
            remoteSnapshots = listOf(announcement, remoteArchive),
            readHead = {
                GameSaveCloudHead(
                    deviceId = "desktop-1",
                    deviceName = "Windows Eden",
                    gameId = identity.gameId,
                    snapshot = remoteSnapshot,
                    file = remoteArchive.name,
                    chain = listOf(localSnapshot, remoteSnapshot),
                    // Older clients only wrote savedAt. It might describe a
                    // late backup rather than the save content itself.
                    savedAt = "2024-10-27T11:35:00Z",
                )
            },
        )
        val stale = GameSaveHeadResolver.decide(
            identity = identity,
            localDeviceId = "odin-2",
            localState = local,
            currentManifestHash = "same-content",
            remoteSnapshots = listOf(announcement, remoteArchive),
            readHead = {
                GameSaveCloudHead(
                    deviceId = "desktop-1",
                    deviceName = "Windows Eden",
                    gameId = identity.gameId,
                    snapshot = remoteSnapshot,
                    file = remoteArchive.name,
                    chain = listOf(localSnapshot, remoteSnapshot),
                    savedAt = "2024-10-27T11:35:00Z",
                    contentModifiedAt = "2024-10-27T03:00:00Z",
                )
            },
        )

        assertEquals(GameSaveRemoteAction.REVIEW_REQUIRED, legacy.action)
        assertEquals(GameSaveRemoteAction.UP_TO_DATE, stale.action)
    }

    @Test
    fun restoredArchiveWithoutTrustedContentTimeIsSavedButNotRepublished() {
        val root = createTempDirectory("opensave-restore-plan-").toFile()
        try {
            val identity = switchIdentity()
            val remote = archive(identity, "snap_1730000100000")
            val emptyArchive = File(root, remote.name)
            ZipOutputStream(FileOutputStream(emptyArchive)).use { }
            val legacyHead = remoteHead(
                identity = identity,
                remote = remote,
                contentModifiedAtMillis = 0L,
                savedAtMillis = 1_730_000_200_000L,
            )

            val plan = requireNotNull(
                GameSaveRemoteRestorePlanner.plan(
                    identity = identity,
                    remote = remote,
                    manifestHash = "restored-content",
                    archiveFile = emptyArchive,
                    remoteHead = legacyHead,
                    previous = localState("snap_1730000000000", "before"),
                ),
            )

            assertFalse(plan.shouldPublish)
            assertEquals(0L, plan.state.contentModifiedAtMillis)
            assertEquals(1_730_000_200_000L, plan.state.savedAtMillis)
            assertEquals(remote.name, plan.state.fileName)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun restoredArchiveCanRepublishOnlyAMatchingTrustedContentTime() {
        val root = createTempDirectory("opensave-restore-plan-").toFile()
        try {
            val identity = switchIdentity()
            val remote = archive(identity, "snap_1730000100000")
            val emptyArchive = File(root, remote.name)
            ZipOutputStream(FileOutputStream(emptyArchive)).use { }
            val trustedContentTime = 1_730_000_100_000L
            val plan = requireNotNull(
                GameSaveRemoteRestorePlanner.plan(
                    identity = identity,
                    remote = remote,
                    manifestHash = "restored-content",
                    archiveFile = emptyArchive,
                    remoteHead = remoteHead(
                        identity = identity,
                        remote = remote,
                        contentModifiedAtMillis = trustedContentTime,
                        savedAtMillis = 1_730_000_200_000L,
                    ),
                    previous = null,
                ),
            )

            assertTrue(plan.shouldPublish)
            assertEquals(trustedContentTime, plan.state.contentModifiedAtMillis)
            assertEquals(trustedContentTime, plan.state.savedAtMillis)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun switchIdentity() = GameCloudIdentity(
        saveType = GameSaveTypes.SWITCH,
        gameId = "switch-0100000000010000",
        desktopCompatible = true,
    )

    private fun archive(identity: GameCloudIdentity, snapshot: String) = RemoteSnapshot(
        type = identity.saveType.id,
        name = "${identity.gameId}__main__${snapshot}.zip",
        sizeBytes = 1L,
        modified = "2024-10-27T00:01:40Z",
    )

    private fun announcement(identity: GameCloudIdentity, deviceId: String, at: Long) = RemoteSnapshot(
        type = identity.saveType.id,
        name = GameSaveCloudProtocol.headFileName(identity.gameId, deviceId, at),
        sizeBytes = 1L,
        modified = "2024-10-27T00:02:00Z",
    )

    private fun remoteHead(
        identity: GameCloudIdentity,
        remote: RemoteSnapshot,
        contentModifiedAtMillis: Long,
        savedAtMillis: Long,
    ) = GameSaveRemoteHead(
        announcement = announcement(identity, "desktop-1", 1_730_000_300_000L),
        archive = remote,
        head = GameSaveCloudHead(
            deviceId = "desktop-1",
            deviceName = "Windows Eden",
            gameId = identity.gameId,
            snapshot = GameSaveCloudProtocol.parseArchiveName(remote.name)!!.snapshotId,
            file = remote.name,
            chain = listOf(GameSaveCloudProtocol.parseArchiveName(remote.name)!!.snapshotId),
            savedAt = "2024-10-27T11:35:00Z",
            contentModifiedAt = if (contentModifiedAtMillis > 0L) "2024-10-27T11:35:00Z" else "",
        ),
        announcedAtMillis = 1_730_000_300_000L,
        savedAtMillis = savedAtMillis,
        contentModifiedAtMillis = contentModifiedAtMillis,
        followsLocal = true,
    )

    private fun localState(snapshot: String, manifestHash: String) = GameSaveHeadState(
        snapshotId = snapshot,
        fileName = "switch-0100000000010000__main__${snapshot}.zip",
        manifestHash = manifestHash,
        chain = listOf(snapshot),
        savedAtMillis = 1_730_000_000_000L,
        contentModifiedAtMillis = 1_730_000_000_000L,
        publishedSnapshotId = snapshot,
    )
}
