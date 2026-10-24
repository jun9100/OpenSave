package com.jun9100.opensave

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @Test
    fun packageAndWorkspaceAreAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.jun9100.opensave", context.packageName)
        assertTrue(context.filesDir.isDirectory)
    }

    private fun assertTrue(value: Boolean) {
        org.junit.Assert.assertTrue(value)
    }
}
