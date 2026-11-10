package com.jun9100.opensave

import java.io.File
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The 410 answers a scope route gives for a deleted or merged scope (C6). */
class GameSaveScopeGoneProtocolTest {
    private val server = MockWebServer()
    private val gameId = "pc-forza-horizon-5-2"

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun deletedScopeCarriesTheTombstoneAndIsNeverRetried() {
        server.enqueue(gone("""{"code":"scope_deleted","error":"deleted","deletedAt":"2026-11-09T12:00:00Z"}"""))

        val error = client().getScope(GameSaveTypes.PC, gameId).exceptionOrNull()

        assertTrue(error is GameSaveScopeGoneException)
        error as GameSaveScopeGoneException
        assertTrue(error.deleted)
        assertFalse(error.retired)
        assertEquals("scope_deleted", error.code)
        assertEquals("2026-11-09T12:00:00Z", error.deletedAt)
        assertEquals(410, error.httpStatus)
        assertFalse(error.retryable)
        assertEquals("这个游戏的云端存档已被管理员删除，本机存档不受影响，已停止云同步", error.message)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun retiredScopeCarriesTheSuccessorOfTheSameTypeOnly() {
        server.enqueue(
            gone(
                """{"code":"scope_retired","error":"merged","successor":{"type":"pc","gameId":"forza-horizon-5"}}""",
            ),
        )

        val error = client().getScope(GameSaveTypes.PC, gameId).exceptionOrNull() as GameSaveScopeGoneException

        assertTrue(error.retired)
        assertFalse(error.deleted)
        assertEquals("pc", error.successorType)
        assertEquals("forza-horizon-5", error.successorGameId)
        assertEquals("这个游戏的云端存档已合并到另一个游戏，已停止云同步", error.message)

        // A successor of another type, or with an unsafe id, is not trusted.
        server.enqueue(
            gone(
                """{"code":"scope_retired","error":"merged","successor":{"type":"switch","gameId":"forza-horizon-5"}}""",
            ),
        )
        val otherType = client().getScope(GameSaveTypes.PC, gameId).exceptionOrNull() as GameSaveScopeGoneException
        assertEquals("", otherType.successorType)
        assertEquals("", otherType.successorGameId)

        server.enqueue(
            gone("""{"code":"scope_retired","error":"merged","successor":{"type":"pc","gameId":"../escape"}}"""),
        )
        val unsafe = client().getScope(GameSaveTypes.PC, gameId).exceptionOrNull() as GameSaveScopeGoneException
        assertEquals("", unsafe.successorGameId)
    }

    @Test
    fun unknown410IsAGenericRefusal() {
        server.enqueue(gone("""{"code":"scope_frozen","error":"frozen"}"""))

        val error = client().getScope(GameSaveTypes.PC, gameId).exceptionOrNull()

        assertTrue(error is GameSaveScopeGoneException)
        error as GameSaveScopeGoneException
        assertFalse(error.deleted)
        assertFalse(error.retired)
        assertEquals("scope_frozen", error.code)
        assertEquals("SaveVault 云端拒绝了这个存档范围（HTTP 410）", error.message)
        assertFalse(error.retryable)
    }

    @Test
    fun everyScopeWriteRouteReportsTheSameStop() {
        val root = createTempDir(prefix = "opensave-scope-gone-")
        try {
            val archiveName = "${gameId}__main__snap_1700000000000--dev-odin2.zip"
            val archive = File(root, archiveName).apply { writeText("save") }
            val candidate = GameSaveScopeCandidate(
                id = "snap_1700000000000--dev-odin2",
                archive = archiveName,
                parent = "",
                contentDigest = Hashing.sha256(archive),
                createdAt = "2026-11-09T12:00:00Z",
            )
            server.enqueue(gone("""{"code":"scope_deleted","deletedAt":"2026-11-09T12:00:00Z"}"""))
            val snapshot = client().putScopeSnapshot(GameSaveTypes.PC, gameId, candidate, "").exceptionOrNull()

            server.enqueue(gone("""{"code":"scope_retired","successor":{"type":"pc","gameId":"forza-horizon-5"}}"""))
            val conflict = client().putScopeConflict(GameSaveTypes.PC, gameId, candidate).exceptionOrNull()

            server.enqueue(gone("""{"code":"scope_deleted"}"""))
            val upload = client().upload(archive, GameSaveTypes.PC, archiveName).exceptionOrNull()

            server.enqueue(gone("""{"code":"scope_retired","successor":{"type":"pc","gameId":"forza-horizon-5"}}"""))
            val identity = client().putScopeIdentity(GameSaveTypes.PC, gameId, GameCloudFacts(name = "Forza")).exceptionOrNull()

            assertEquals(listOf(true, true, true, true), listOf(snapshot, conflict, upload, identity).map { it is GameSaveScopeGoneException })
            assertTrue((snapshot as GameSaveScopeGoneException).deleted)
            assertTrue((conflict as GameSaveScopeGoneException).retired)
            assertTrue((upload as GameSaveScopeGoneException).deleted)
            assertTrue((identity as GameSaveScopeGoneException).retired)
            // The archive PUT never happens after a 410 from the scope read.
            assertEquals(4, server.requestCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun lockedVersionsAndRetiredMembersAreParsed() {
        val snapshotBody = snapshotJson(locked = true)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"type":"pc","gameId":"$gameId","head":$snapshotBody,"snapshots":[$snapshotBody],"conflicts":[]}""",
            ),
        )
        val scope = client().getScope(GameSaveTypes.PC, gameId).getOrThrow()

        assertTrue(scope.head!!.locked)
        assertTrue(scope.snapshots.single().locked)

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"games":[{"key":"pc:forza-horizon-5","type":"pc","name":"Forza","scopes":[""" +
                    """{"type":"pc","gameId":"forza-horizon-5","name":"Forza","snapshotCount":3},""" +
                    """{"type":"pc","gameId":"$gameId","name":"Forza 2","retired":true,"snapshotCount":9}""" +
                    """]}]}""",
            ),
        )
        val members = client().getGames().getOrThrow()!!.single().members

        assertFalse(members.first().retired)
        assertTrue(members.last().retired)
    }

    private fun snapshotJson(locked: Boolean): String =
        """{"id":"snap_1700000000000--dev-odin2","archive":"${gameId}__main__snap_1700000000000--dev-odin2.zip",""" +
            """"parent":"","contentDigest":"${"a".repeat(64)}","createdAt":"2026-11-09T12:00:00Z",""" +
            """"receivedAt":"2026-11-09T12:00:01Z","device":"odin2","deviceName":"Odin2","locked":$locked}"""

    private fun gone(body: String): MockResponse = MockResponse().setResponseCode(410).setBody(body)

    private fun client(): GameSaveClient = GameSaveClient(
        GameSaveConfig(enabled = true, serviceUrl = server.url("/").toString().trimEnd('/'), syncKey = "sync-key"),
        GameSaveDeviceIdentity("odin2", "Odin2"),
    )
}
