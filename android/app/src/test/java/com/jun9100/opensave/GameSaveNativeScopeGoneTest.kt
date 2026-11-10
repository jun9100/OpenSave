package com.jun9100.opensave

import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The native sync's stop on a deleted/merged scope: no archive upload, no CAS
 * write, the confirmed head forgotten and the tombstone recorded. The store
 * and the device identity are injected, so no Android storage is involved.
 */
class GameSaveNativeScopeGoneTest {
    private val server = MockWebServer()
    private val gameId = "pc-forza-horizon-5-2"
    private val identity = GameCloudIdentity(GameSaveTypes.PC, gameId, desktopCompatible = false)
    private val heads = FakeHeads()
    private val tombstones = FakeTombstones()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun checkStopsWithoutUploadAndForgetsTheConfirmedHead() {
        server.enqueue(gone("""{"code":"scope_deleted","deletedAt":"2026-11-09T12:00:00Z"}"""))
        heads.states[gameId] = headState(nativeHead = "snap_1700000000000--dev-odin2")

        val error = sync().check(identity, "same").exceptionOrNull()

        assertTrue(error is GameSaveScopeGoneException)
        assertTrue((error as GameSaveScopeGoneException).deleted)
        assertEquals("", heads.states[gameId]?.nativeHead)
        assertEquals(ScopeTombstone.STATE_DELETED, tombstones.get(identity)?.state)
        assertEquals("2026-11-09T12:00:00Z", tombstones.get(identity)?.at)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("没有收到请求")
        assertEquals("GET", request.method)
        assertEquals("/v1/opensave/scopes/pc/$gameId", request.path)
        // The snapshot id is kept: only the cloud head is forgotten.
        assertEquals("snap_1700000000000", heads.states[gameId]?.snapshotId)

        // Every later sync skips the game without a single request.
        assertTrue(sync().check(identity, "same").exceptionOrNull() is GameSaveScopeGoneException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun publishSnapshotWithARecordedTombstoneSendsNothing() {
        tombstones.record(identity, GameSaveScopeGoneException.of(GameSaveScopeGoneException.DELETED, deletedAt = "2026-11-09T12:00:00Z"))
        val root = createTempDir(prefix = "opensave-native-gone-")
        try {
            val archive = File(root, "${gameId}__main__snap_1700000000000.zip").apply { writeText("save") }

            val error = sync().publishSnapshot(identity, record(archive), archive, GameCloudFacts(name = "Forza")).exceptionOrNull()

            assertTrue(error is GameSaveScopeGoneException)
            assertEquals("这个游戏的云端存档已被管理员删除，本机存档不受影响，已停止云同步", error?.message)
            assertEquals(0, server.requestCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun a410OnTheScopeReadUploadsNoArchiveAndWritesNoScopeVersion() {
        server.enqueue(gone("""{"code":"scope_retired","successor":{"type":"pc","gameId":"forza-horizon-5"}}"""))
        heads.states[gameId] = headState(nativeHead = "snap_1700000000000--dev-odin2")
        val root = createTempDir(prefix = "opensave-native-gone-")
        try {
            val archive = File(root, "${gameId}__main__snap_1700000000000.zip").apply { writeText("save") }

            val error = sync().publishSnapshot(identity, record(archive), archive).exceptionOrNull()

            assertTrue(error is GameSaveScopeGoneException)
            assertEquals("forza-horizon-5", (error as GameSaveScopeGoneException).successorGameId)
            assertTrue(error.retired)
            assertEquals(1, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
            assertFalse(heads.states[gameId]?.nativeHead?.isNotBlank() ?: true)
            assertEquals(ScopeTombstone.STATE_RETIRED, tombstones.get(identity)?.state)
            assertEquals("forza-horizon-5", tombstones.get(identity)?.successorGameId)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun anUnknown410IsNotRecordedAndKeepsTheHead() {
        server.enqueue(gone("""{"code":"scope_frozen"}"""))
        server.enqueue(gone("""{"code":"scope_frozen"}"""))
        heads.states[gameId] = headState(nativeHead = "snap_1700000000000--dev-odin2")

        val first = sync().check(identity, "same").exceptionOrNull() as GameSaveScopeGoneException

        assertEquals("scope_frozen", first.code)
        assertFalse(first.deleted || first.retired)
        assertFalse(first.retryable)
        assertNull(tombstones.get(identity))
        assertEquals("snap_1700000000000--dev-odin2", heads.states[gameId]?.nativeHead)

        // Not stopped: the next sync asks the server again.
        assertTrue(sync().check(identity, "same").exceptionOrNull() is GameSaveScopeGoneException)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun markRemoteRestoreDoesNotWriteStateForAStoppedScope() {
        tombstones.record(identity, GameSaveScopeGoneException.of(GameSaveScopeGoneException.RETIRED, successorType = "pc", successorGameId = "forza-horizon-5"))
        val root = createTempDir(prefix = "opensave-native-gone-")
        try {
            val archive = File(root, "${gameId}__main__snap_1700000000000.zip").apply { writeText("save") }
            val candidate = GameSaveNativeCandidate(
                remote = RemoteSnapshot(GameSaveTypes.PC.id, archive.name, archive.length(), "2026-11-09T12:00:00Z"),
                snapshot = scopeSnapshot(archive),
            )

            val error = sync().markRemoteRestore(identity, candidate, "same", archive).exceptionOrNull()

            assertTrue(error is GameSaveScopeGoneException)
            assertNull(heads.states[gameId])
            assertEquals(0, server.requestCount)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sync(): GameSaveNativeSync = GameSaveNativeSync(
        client = GameSaveClient(
            GameSaveConfig(enabled = true, serviceUrl = server.url("/").toString().trimEnd('/'), syncKey = "sync-key"),
            GameSaveDeviceIdentity("odin2", "Odin2"),
        ),
        tombstones = tombstones,
        loadState = heads::load,
        saveState = heads::save,
        deviceIdentity = { GameSaveDeviceIdentity("odin2", "Odin2") },
    )

    private fun headState(nativeHead: String) = GameSaveHeadState(
        snapshotId = "snap_1700000000000",
        fileName = "${gameId}__main__snap_1700000000000.zip",
        manifestHash = "same",
        chain = listOf("snap_1700000000000"),
        savedAtMillis = 1L,
        nativeHead = nativeHead,
    )

    private fun record(archive: File) = SnapshotRecord(
        id = "game_1",
        sourceId = "source",
        remoteName = archive.name,
        localPath = archive.name,
        createdAtMillis = 1_730_000_000_000L,
        sizeBytes = archive.length(),
        sha256 = Hashing.sha256(archive),
        manifestHash = "same",
        gameKey = "pc:forza",
        gameName = "Forza",
        saveType = GameSaveTypes.PC.id,
        cloudGameId = gameId,
    )

    private fun scopeSnapshot(archive: File) = GameSaveScopeSnapshot(
        id = "snap_1700000000000--dev-desktop1",
        archive = archive.name,
        parent = "",
        contentDigest = Hashing.sha256(archive),
        createdAt = "2026-11-09T12:00:00Z",
        receivedAt = "2026-11-09T12:00:01Z",
    )

    private fun gone(body: String): MockResponse = MockResponse().setResponseCode(410).setBody(body)

    private class FakeHeads {
        val states = mutableMapOf<String, GameSaveHeadState>()

        fun load(identity: GameCloudIdentity): GameSaveHeadState? = states[identity.gameId]

        fun save(identity: GameCloudIdentity, state: GameSaveHeadState) {
            states[identity.gameId] = state
        }
    }

    private class FakeTombstones : ScopeTombstoneRepository {
        private val entries = mutableMapOf<String, ScopeTombstone>()

        override fun get(identity: GameCloudIdentity): ScopeTombstone? = entries[key(identity)]

        override fun all(): List<ScopeTombstone> = entries.values.toList()

        override fun record(identity: GameCloudIdentity, error: GameSaveScopeGoneException): Boolean {
            if (!error.deleted && !error.retired) return false
            val recorded = ScopeTombstone(
                type = identity.saveType.id,
                gameId = identity.gameId,
                state = if (error.retired) ScopeTombstone.STATE_RETIRED else ScopeTombstone.STATE_DELETED,
                at = error.deletedAt,
                successorType = error.successorType,
                successorGameId = error.successorGameId,
                seenAtMillis = 1L,
            )
            val changed = entries[key(identity)] != recorded
            entries[key(identity)] = recorded
            return changed
        }

        override fun acknowledge(identity: GameCloudIdentity) = Unit

        override fun setSuccessorName(identity: GameCloudIdentity, name: String) = Unit

        override fun clear(identity: GameCloudIdentity) {
            entries.remove(key(identity))
        }

        private fun key(identity: GameCloudIdentity): String = "${identity.saveType.id}/${identity.gameId}"
    }
}
