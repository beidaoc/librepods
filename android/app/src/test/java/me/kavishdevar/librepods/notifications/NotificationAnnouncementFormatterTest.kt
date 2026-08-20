package me.kavishdevar.librepods.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationAnnouncementFormatterTest {
    @Test
    fun `includes app sender and message`() {
        assertEquals(
            "微信，张三：你好",
            NotificationAnnouncementFormatter.format("微信", "张三", "你好")
        )
    }

    @Test
    fun `does not repeat app name as title`() {
        assertEquals(
            "微信，你好",
            NotificationAnnouncementFormatter.format("微信", "微信", "你好")
        )
    }

    @Test
    fun `normalizes whitespace`() {
        assertEquals(
            "Messages，Alice：Hello world",
            NotificationAnnouncementFormatter.format(" Messages ", "Alice", "Hello\n world")
        )
    }

    @Test
    fun `requires app name and content`() {
        assertNull(NotificationAnnouncementFormatter.format(null, "Alice", "Hello"))
        assertNull(NotificationAnnouncementFormatter.format("Messages", null, null))
    }
}
