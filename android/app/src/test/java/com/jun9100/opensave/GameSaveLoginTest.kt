// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package com.jun9100.opensave

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GameSaveLoginTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `login posts the credentials and returns the token`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"token":"tok-123","vault":"alice"}"""))

        val token = GameSaveClient.login(server.url("/").toString(), " alice ", "s3cret-pass").getOrThrow()

        assertEquals("tok-123", token)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/login", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"username\":\"alice\""))
        assertTrue(body.contains("\"password\":\"s3cret-pass\""))
    }

    @Test
    fun `a rejected password reports the account message`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid credentials"}"""))

        val error = GameSaveClient.login(server.url("/").toString(), "alice", "wrong").exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("账号或密码不正确"))
    }

    @Test
    fun `an old server is reported as missing the route`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val error = GameSaveClient.login(server.url("/").toString(), "alice", "s3cret-pass").exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("没有账号登录接口"))
    }

    @Test
    fun `an empty token is refused`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"vault":"alice"}"""))

        val error = GameSaveClient.login(server.url("/").toString(), "alice", "s3cret-pass").exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("没有返回登录令牌"))
    }
}
