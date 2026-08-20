package me.kavishdevar.librepods.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestNotificationAnnouncementQueueTest {
    @Test
    fun `same conversation keeps only latest waiting message`() {
        val queue = LatestNotificationAnnouncementQueue(maxPendingConversations = 3)

        queue.offer(NotificationAnnouncement("wechat-zhang", "微信，张三：第二条"))
        queue.offer(NotificationAnnouncement("wechat-zhang", "微信，张三：第三条"))
        queue.offer(NotificationAnnouncement("wechat-zhang", "微信，张三：第四条"))

        assertEquals(
            NotificationAnnouncement("wechat-zhang", "微信，张三：第四条"),
            queue.poll()
        )
        assertNull(queue.poll())
    }

    @Test
    fun `different conversations each retain their latest message`() {
        val queue = LatestNotificationAnnouncementQueue(maxPendingConversations = 3)

        queue.offer(NotificationAnnouncement("wechat-zhang", "微信，张三：第二条"))
        queue.offer(NotificationAnnouncement("messages-li", "信息，李四：你好"))
        queue.offer(NotificationAnnouncement("wechat-zhang", "微信，张三：最新一条"))

        assertEquals("微信，张三：最新一条", queue.poll()?.spokenText)
        assertEquals("信息，李四：你好", queue.poll()?.spokenText)
    }

    @Test
    fun `oldest conversation is dropped when capacity is exceeded`() {
        val queue = LatestNotificationAnnouncementQueue(maxPendingConversations = 2)

        queue.offer(NotificationAnnouncement("first", "第一条"))
        queue.offer(NotificationAnnouncement("second", "第二条"))
        queue.offer(NotificationAnnouncement("third", "第三条"))

        assertEquals("第二条", queue.poll()?.spokenText)
        assertEquals("第三条", queue.poll()?.spokenText)
    }
}
