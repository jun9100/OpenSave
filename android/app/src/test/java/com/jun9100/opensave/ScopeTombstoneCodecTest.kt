package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JSON shape of a recorded tombstone, tested without Android's SharedPreferences. */
class ScopeTombstoneCodecTest {
    private val tombstone = ScopeTombstone(
        type = GameSaveTypes.PC.id,
        gameId = "pc-forza-horizon-5-2",
        state = ScopeTombstone.STATE_RETIRED,
        at = "2026-11-09T12:05:00Z",
        successorType = GameSaveTypes.PC.id,
        successorGameId = "forza-horizon-5",
        successorName = "Forza Horizon 5",
        acknowledged = true,
        seenAtMillis = 1_730_000_000_000L,
    )

    @Test
    fun roundTripsEveryField() {
        assertEquals(tombstone, ScopeTombstoneCodec.decode(ScopeTombstoneCodec.encode(tombstone)))
    }

    @Test
    fun keysAreTheServerScopeKey() {
        assertEquals("pc/forza-horizon-5-2", ScopeTombstoneCodec.key("pc", "forza-horizon-5-2"))
    }

    @Test
    fun malformedAndUnknownRecordsAreDropped() {
        assertNull(ScopeTombstoneCodec.decode("not-json"))
        assertNull(ScopeTombstoneCodec.decode("""{"type":"pc","gameId":"x","state":"frozen","seenAtMillis":1}"""))
        assertNull(ScopeTombstoneCodec.decode("""{"type":"nope","gameId":"x","state":"deleted","seenAtMillis":1}"""))
        assertNull(ScopeTombstoneCodec.decode("""{"type":"pc","gameId":"","state":"deleted","seenAtMillis":1}"""))
    }

    @Test
    fun defaultsFillOlderRecords() {
        val decoded = ScopeTombstoneCodec.decode("""{"type":"PC","gameId":"forza","state":"deleted","seenAtMillis":7}""")

        assertNotNull(decoded)
        assertEquals(GameSaveTypes.PC.id, decoded!!.type)
        assertEquals("", decoded.at)
        assertEquals("", decoded.successorName)
        assertFalse(decoded.acknowledged)
        assertTrue(decoded.stopNotice.contains("删除"))
    }

    @Test
    fun stopPolicySkipsExactlyTheRecordedScopes() {
        assertTrue(ScopeTombstonePolicy.shouldSkip(tombstone))
        assertFalse(ScopeTombstonePolicy.shouldSkip(null))
    }

    @Test
    fun theStopRebuiltFromATombstoneKeepsTheStateAndWording() {
        val retired = GameSaveScopeGoneException.from(tombstone)
        assertTrue(retired.retired)
        assertEquals("forza-horizon-5", retired.successorGameId)
        assertEquals("这个游戏的云端存档已合并到另一个游戏，已停止云同步", retired.message)

        val deleted = GameSaveScopeGoneException.from(
            tombstone.copy(state = ScopeTombstone.STATE_DELETED, at = "2026-11-09T12:00:00Z", successorGameId = ""),
        )
        assertTrue(deleted.deleted)
        assertEquals("2026-11-09T12:00:00Z", deleted.deletedAt)
        assertEquals("这个游戏的云端存档已被管理员删除，本机存档不受影响，已停止云同步", deleted.message)
    }
}
