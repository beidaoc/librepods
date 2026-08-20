/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.notifications

data class NotificationAnnouncement(
    val conversationKey: String,
    val spokenText: String
)

/**
 * Keeps at most one waiting announcement per conversation.
 *
 * The first message is spoken immediately by the service. While it is being spoken,
 * newer messages replace the waiting item for that conversation, so Xiaomi's speech
 * stack receives only the first and the latest notification content.
 */
class LatestNotificationAnnouncementQueue(
    private val maxPendingConversations: Int
) {
    init {
        require(maxPendingConversations > 0)
    }

    private val pendingByConversation = LinkedHashMap<String, NotificationAnnouncement>()

    fun offer(announcement: NotificationAnnouncement) {
        if (!pendingByConversation.containsKey(announcement.conversationKey) &&
            pendingByConversation.size >= maxPendingConversations
        ) {
            pendingByConversation.remove(pendingByConversation.keys.first())
        }
        pendingByConversation[announcement.conversationKey] = announcement
    }

    fun poll(): NotificationAnnouncement? {
        val firstKey = pendingByConversation.keys.firstOrNull() ?: return null
        return pendingByConversation.remove(firstKey)
    }

    fun clear() {
        pendingByConversation.clear()
    }

    fun size(): Int = pendingByConversation.size
}
