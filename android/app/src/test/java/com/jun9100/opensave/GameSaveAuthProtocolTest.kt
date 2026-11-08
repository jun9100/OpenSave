package com.jun9100.opensave

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GameSaveAuthProtocolTest {
    private val server = MockWebServer()
    private val device = GameSaveDeviceIdentity(
        id = "a1b2c3d4-device",
        name = "客厅的 Android 平板",
        version = "0.5.0",
    )

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun revokedTokenNotifiesOnceAndIsNeverRetryable() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":"revoked","code":"token_revoked"}"""),
        )
        val observer = RecordingObserver()

        val error = client(observer).list().exceptionOrNull()

        assertTrue(error is GameSaveAuthException)
        error as GameSaveAuthException
        assertEquals("token_revoked", error.code)
        assertEquals("此设备已被 SaveVault 云端移除，请重新配对", error.message)
        assertEquals(401, error.httpStatus)
        assertTrue(!error.retryable)
        assertEquals(listOf("token_revoked"), observer.rejected)
        assertEquals(0, observer.warnings)
    }

    @Test
    fun sharedTokenDisabledAndPlainUnauthorizedAreDistinguished() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":"disabled","code":"shared_token_disabled"}"""),
        )
        val disabledObserver = RecordingObserver()
        val disabled = client(disabledObserver).list().exceptionOrNull() as GameSaveAuthException

        assertEquals("shared_token_disabled", disabled.code)
        assertEquals("SaveVault 云端已停用共享同步密钥，请用配对码连接本设备", disabled.message)
        assertEquals(listOf("shared_token_disabled"), disabledObserver.rejected)

        server.enqueue(MockResponse().setResponseCode(401).setBody("not-json"))
        val plainObserver = RecordingObserver()
        val plain = client(plainObserver).list().exceptionOrNull() as GameSaveAuthException

        assertEquals("unauthorized", plain.code)
        assertEquals("同步密钥或设备令牌无效，请检查", plain.message)
        assertTrue(plainObserver.rejected.isEmpty())
    }

    @Test
    fun warningHeaderIsReportedWithoutBlocking() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("X-Game-Save-Auth-Warning", "shared-token-deprecated")
                .setBody(
                    """{"auth":"shared","token_id":"","device_id":"","name":"","shared_token_mode":"warn"}""",
                ),
        )
        val observer = RecordingObserver()

        val whoami = client(observer).whoami().getOrThrow()

        assertEquals("shared", whoami?.auth)
        assertEquals("warn", whoami?.sharedTokenMode)
        assertEquals(1, observer.warnings)
        assertTrue(observer.rejected.isEmpty())
    }

    @Test
    fun whoamiReturnsNullOnOldServicesAndParsesDeviceFields() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client(RecordingObserver()).whoami().getOrThrow())

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"auth":"device","token_id":"3f9c0a1b2d4e5f60","device_id":"a1b2c3d4-device",""" +
                        """"name":"客厅的 Android 平板","shared_token_mode":"allow"}""",
                ),
        )
        val whoami = client(RecordingObserver()).whoami().getOrThrow()

        assertEquals("device", whoami?.auth)
        assertEquals("3f9c0a1b2d4e5f60", whoami?.tokenId)
        assertEquals("a1b2c3d4-device", whoami?.deviceId)
        assertEquals("客厅的 Android 平板", whoami?.name)
        assertEquals("allow", whoami?.sharedTokenMode)
    }

    @Test
    fun claimPairingSendsNoAuthorizationAndKeepsTheDeviceId() {
        val token = "gsdt_3f9c0a1b2d4e5f60_" + "a".repeat(64)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    JSONObject()
                        .put("token", token)
                        .put("token_id", "3f9c0a1b2d4e5f60")
                        .put("device_id", device.id)
                        .put("name", "客厅的 Android 平板")
                        .put("server_version", "v1.7.0")
                        .toString(),
                ),
        )

        val result = GameSaveClient.claimPairing(serverUrl(), "abcd-efgh", device).getOrThrow()

        assertEquals(token, result.token)
        assertEquals("3f9c0a1b2d4e5f60", result.tokenId)
        assertEquals("客厅的 Android 平板", result.name)
        assertEquals("v1.7.0", result.serverVersion)
        assertTrue(!result.toString().contains(token))

        val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("没有收到配对请求")
        assertEquals("POST", request.method)
        assertEquals("/v1/pair/claim", request.path)
        assertNull(request.getHeader("Authorization"))
        assertEquals(device.id, request.getHeader("X-OpenSave-Device-ID"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("ABCDEFGH", body.getString("code"))
        assertEquals(device.id, body.getString("device_id"))
        assertEquals(device.name, body.getString("device_name"))
        assertEquals(device.platform, body.getString("platform"))
        assertEquals(device.version, body.getString("version"))
    }

    @Test
    fun claimPairingMapsItsRefusals() {
        server.enqueue(MockResponse().setResponseCode(401))
        val invalid = GameSaveClient.claimPairing(serverUrl(), "ABCDEFGH", device).exceptionOrNull()
        assertTrue(invalid is GameSaveException)
        assertEquals("配对码无效、已过期或已被使用", invalid?.message)
        assertEquals(401, (invalid as GameSaveException).httpStatus)
        assertTrue(!invalid.retryable)

        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(GameSaveClient.claimPairing(serverUrl(), "ABCDEFGH", device).exceptionOrNull() is GameSavePairingUnsupportedException)

        server.enqueue(MockResponse().setResponseCode(429))
        val limited = GameSaveClient.claimPairing(serverUrl(), "ABCDEFGH", device).exceptionOrNull() as GameSaveException
        assertEquals("配对失败次数太多，请稍后再试", limited.message)
        assertTrue(limited.retryable)

        server.enqueue(MockResponse().setResponseCode(400))
        val badRequest = GameSaveClient.claimPairing(serverUrl(), "ABCDEFGH", device).exceptionOrNull()
        assertEquals("配对请求无效", badRequest?.message)
    }

    @Test
    fun aMalformedClaimTokenFailsWithoutLeakingTheResponse() {
        val secret = "gsdt_3f9c0a1b2d4e5f60_" + "a".repeat(64) + " extra"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(JSONObject().put("token", secret).put("token_id", "id").toString()),
        )

        val error = GameSaveClient.claimPairing(serverUrl(), "ABCDEFGH", device).exceptionOrNull()

        assertEquals("SaveVault 云端返回的令牌无效", error?.message)
        assertTrue(!(error?.message.orEmpty().contains(secret)))
    }

    private fun client(observer: GameSaveAuthObserver) = GameSaveClient(
        GameSaveConfig(enabled = true, serviceUrl = serverUrl(), syncKey = "sync-key"),
        device,
        observer,
    )

    private fun serverUrl(): String = server.url("/").toString().trimEnd('/')

    private class RecordingObserver : GameSaveAuthObserver {
        val rejected = mutableListOf<String>()
        var warnings = 0

        override fun onAuthRejected(code: String) {
            rejected += code
        }

        override fun onSharedTokenWarning() {
            warnings++
        }
    }
}
