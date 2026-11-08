package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException

class SafFailuresTest {
    @Test
    fun systemExceptionsGetChineseReasons() {
        assertEquals(SafFailures.PERMISSION_REVOKED, SafFailures.reason(SecurityException("Permission Denial: reading com.android.externalstorage")))
        assertEquals(SafFailures.NOT_A_DIRECTORY, SafFailures.reason(FileNotFoundException("Missing file for primary:x")))
    }

    @Test
    fun ownMessagesPassThrough() {
        assertEquals(SafFailures.NOT_A_DIRECTORY, SafFailures.reason(IllegalArgumentException(SafFailures.NOT_A_DIRECTORY)))
        assertEquals("读取失败", SafFailures.reason(IllegalStateException()))
    }
}
