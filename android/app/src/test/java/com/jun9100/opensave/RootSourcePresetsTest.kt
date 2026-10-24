package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootSourcePresetsTest {
    @Test
    fun providesCommonAndroidGameAndEmulatorRoots() {
        assertEquals(
            "GameHub PC",
            RootSourcePresets.find("/data/user/0/com.xiaoji.egggame/files")?.label,
        )
        assertEquals(
            "Eden Switch",
            RootSourcePresets.find("/data/user/0/dev.eden.eden_emulator/files/")?.label,
        )
        assertEquals(
            "Android 应用数据",
            RootSourcePresets.find("/storage/emulated/0/Android/data")?.label,
        )
        assertEquals(
            "Sudachi",
            RootSourcePresets.find("/data/user/0/org.sudachi.sudachi_emu/files")?.label,
        )
        assertNull(RootSourcePresets.find("/data/user/0/custom.emulator/files"))
    }
}
