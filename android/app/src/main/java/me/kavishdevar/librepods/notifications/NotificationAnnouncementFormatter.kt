/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.notifications

object NotificationAnnouncementFormatter {
    private const val MAX_CONTENT_LENGTH = 300
    private val whitespace = Regex("\\s+")

    fun format(appName: CharSequence?, title: CharSequence?, text: CharSequence?): String? {
        val cleanAppName = appName.clean() ?: return null
        val cleanTitle = title.clean()
        val cleanText = text.clean()

        val content = when {
            cleanText == null -> cleanTitle
            cleanTitle == null -> cleanText
            cleanTitle.equals(cleanAppName, ignoreCase = true) -> cleanText
            cleanTitle.equals(cleanText, ignoreCase = true) -> cleanText
            else -> "$cleanTitle：$cleanText"
        }?.take(MAX_CONTENT_LENGTH)?.trim() ?: return null

        return "$cleanAppName，$content"
    }

    private fun CharSequence?.clean(): String? = this
        ?.toString()
        ?.replace(whitespace, " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
