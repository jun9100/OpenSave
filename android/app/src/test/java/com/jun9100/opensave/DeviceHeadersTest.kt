package com.jun9100.opensave

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceHeadersTest {
    @Test
    fun printableAsciiNamesStayReadable() {
        assertEquals("Odin2", DeviceHeaders.encodeName("Odin2"))
        assertEquals("HUAWEI Mate 60", DeviceHeaders.encodeName("HUAWEI Mate 60"))
        assertEquals("Pixel 8 (2024)", DeviceHeaders.encodeName("  Pixel 8 (2024)  "))
    }

    @Test
    fun nonAsciiNamesUseRfc8187PercentEncoding() {
        assertEquals(
            "UTF-8''%E5%B0%8F%E6%98%8E%E7%9A%84%E6%89%8B%E6%9C%BA",
            DeviceHeaders.encodeName("小明的手机"),
        )
        assertEquals("UTF-8''Caf%C3%A9%202", DeviceHeaders.encodeName("Café 2"))
    }

    @Test
    fun controlCharactersAreRemovedBeforeTrimming() {
        assertEquals("Odin2", DeviceHeaders.encodeName("\n Odin2\t"))
        assertEquals("Pixel 8", DeviceHeaders.encodeName("Pixel\n 8\u007F"))
        assertEquals("", DeviceHeaders.encodeName(" \n\t "))
    }

    @Test
    fun encodedNameIsAcceptedByOkHttpHeaders() {
        val encoded = DeviceHeaders.encodeName("小明的手机")
        val request = Request.Builder()
            .url("http://localhost/")
            .header("X-OpenSave-Device-Name", encoded)
            .header("X-Eden-Device-Name", encoded)
            .build()
        assertEquals(encoded, request.header("X-OpenSave-Device-Name"))
        assertEquals(encoded, request.header("X-Eden-Device-Name"))
    }
}
