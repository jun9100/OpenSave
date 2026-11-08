package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameSaveCloudGamesTest {
    private val client = GameSaveClient(GameSaveConfig(enabled = true, serviceUrl = "http://127.0.0.1:1", syncKey = "k"))

    private val head = """{"id":"pc-1","archive":"switch-0100000000010000__main__pc-1.zip","parent":"",
        "contentDigest":"${"a".repeat(64)}","createdAt":"2026-11-08T05:00:00Z","receivedAt":"2026-11-08T05:00:01Z",
        "deviceName":"Windows PC"}"""

    @Test
    fun gamesListOtherDevicesSaveLocationsOfOneType() {
        val games = client.parseGames(
            """{"games":[{"key":"switch/title:0100000000010000","type":"switch","name":"超级马力欧 奥德赛","scopes":[
                {"type":"switch","gameId":"android-switch-0123456789abcdef","link":"auto","name":"超级马力欧 奥德赛","head":null,"snapshotCount":2,"conflictCount":0},
                {"type":"switch","gameId":"switch-0100000000010000","link":"auto","head":$head,"snapshotCount":5,"conflictCount":1},
                {"type":"pc","gameId":"smuggled","link":"manual","head":null,"snapshotCount":1,"conflictCount":0}
            ]}]}""",
        )
        val game = games.single()
        assertEquals("超级马力欧 奥德赛", game.name)
        // A member of another type is never trusted into the game.
        assertEquals(listOf("android-switch-0123456789abcdef", "switch-0100000000010000"), game.members.map { it.gameId })

        val others = game.othersThan("switch", "android-switch-0123456789abcdef")
        assertEquals(1, others.size)
        assertEquals("Windows PC", others.single().head?.deviceName)
        assertEquals(5, others.single().snapshotCount)
        assertNull(game.members.first().head)
    }

    @Test
    fun malformedGamesAreDropped() {
        assertEquals(emptyList<GameSaveCloudGame>(), client.parseGames("""{"games":[{"type":"bogus type","scopes":[]}]}"""))
        assertEquals(emptyList<GameSaveCloudGame>(), client.parseGames("""{}"""))
    }
}
