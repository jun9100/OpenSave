package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageMessagesTest {
    @Test
    fun aSettingsResultNeverAppearsInTheLibrary() {
        val messages = PageMessages()
            .with(OpenSavePage.LIBRARY, action = "批量备份完成", error = "游戏 A：失败")
            .with(OpenSavePage.MANAGEMENT, action = "game-save 连接成功")

        assertEquals("game-save 连接成功", messages[OpenSavePage.MANAGEMENT].action)
        assertEquals("批量备份完成", messages[OpenSavePage.LIBRARY].action)
        assertEquals("游戏 A：失败", messages[OpenSavePage.LIBRARY].error)
        assertNull(messages[OpenSavePage.MANAGEMENT].error)
    }

    @Test
    fun startingAnOperationClearsOnlyItsOwnPage() {
        val messages = PageMessages()
            .with(OpenSavePage.LIBRARY, action = "已备份", error = "上传失败")
            .with(OpenSavePage.MANAGEMENT, action = "已保存")
            .with(OpenSavePage.MANAGEMENT)

        assertEquals(PageMessage(), messages[OpenSavePage.MANAGEMENT])
        assertEquals(PageMessage("已备份", "上传失败"), messages[OpenSavePage.LIBRARY])
    }

    @Test
    fun anErrorKeepsThePageLastResult() {
        val messages = PageMessages()
            .with(OpenSavePage.MANAGEMENT, action = "已启用存档来源")
            .withError(OpenSavePage.MANAGEMENT, "该来源目录不存在或不可访问，不能勾选")

        assertEquals("已启用存档来源", messages[OpenSavePage.MANAGEMENT].action)
        assertEquals("该来源目录不存在或不可访问，不能勾选", messages[OpenSavePage.MANAGEMENT].error)
        assertEquals(PageMessage(), messages[OpenSavePage.LIBRARY])
    }
}
