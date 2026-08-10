/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationalAwarenessNotificationTest {
    @Test
    fun completeBetaStatusPacketUpdatesStatus() {
        val notification = AirPodsNotifications.ConversationalAwarenessNotification()

        assertTrue(notification.setData(conversationPacket(0x0B)))
        assertEquals(0x0B.toByte(), notification.status)
    }

    @Test
    fun fragmentedPacketIsRejectedWithoutChangingLastStatus() {
        val notification = AirPodsNotifications.ConversationalAwarenessNotification()
        assertTrue(notification.setData(conversationPacket(0x03)))

        val firstNineBytes = conversationPacket(0x0B).copyOfRange(0, 9)

        assertFalse(notification.setData(firstNineBytes))
        assertEquals(0x03.toByte(), notification.status)
    }

    @Test
    fun unrelatedTenBytePacketIsRejected() {
        val notification = AirPodsNotifications.ConversationalAwarenessNotification()
        val unrelated = conversationPacket(0x0B).apply { this[4] = 0x4A }

        assertFalse(notification.setData(unrelated))
        assertEquals(0x00.toByte(), notification.status)
    }

    private fun conversationPacket(status: Int): ByteArray = byteArrayOf(
        0x04, 0x00, 0x04, 0x00, 0x4B, 0x00, 0x02, 0x00, 0x01, status.toByte()
    )
}
