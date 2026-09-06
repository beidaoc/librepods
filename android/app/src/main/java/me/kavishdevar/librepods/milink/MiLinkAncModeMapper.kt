/*
 * LibrePods - AirPods liberated from Apple's ecosystem
 * Copyright (C) 2025 LibrePods contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 */

package me.kavishdevar.librepods.milink

object MiLinkAncModeMapper {
    // HyperOS renders the ANC choices in this fixed visual order:
    // 2 (left), 1 (middle), 0 (right). LibrePods repurposes them as
    // Transparency, Adaptive, and Noise Cancellation respectively.
    const val MI_LINK_NOISE_CANCELLATION = 0
    const val MI_LINK_ADAPTIVE = 1
    const val MI_LINK_TRANSPARENCY = 2
    const val MI_LINK_UNSELECTED = -1

    const val LIBREPODS_OFF = 1
    const val LIBREPODS_NOISE_CANCELLATION = 2
    const val LIBREPODS_TRANSPARENCY = 3
    const val LIBREPODS_ADAPTIVE = 4

    fun toMiLink(librePodsMode: Int): Int = when (librePodsMode) {
        LIBREPODS_NOISE_CANCELLATION -> MI_LINK_NOISE_CANCELLATION
        LIBREPODS_ADAPTIVE -> MI_LINK_ADAPTIVE
        LIBREPODS_TRANSPARENCY -> MI_LINK_TRANSPARENCY
        else -> MI_LINK_UNSELECTED
    }

    fun toLibrePods(miLinkMode: Int): Int = when (miLinkMode) {
        MI_LINK_NOISE_CANCELLATION -> LIBREPODS_NOISE_CANCELLATION
        MI_LINK_ADAPTIVE -> LIBREPODS_ADAPTIVE
        MI_LINK_TRANSPARENCY -> LIBREPODS_TRANSPARENCY
        else -> LIBREPODS_OFF
    }

    fun isSelectableLibrePodsMode(mode: Int): Boolean =
        mode == LIBREPODS_TRANSPARENCY ||
            mode == LIBREPODS_ADAPTIVE ||
            mode == LIBREPODS_NOISE_CANCELLATION

    /**
     * HeadSetsDetail does not pass its mode value straight through as a child index. Its presenter
     * maps mode 1 to the left item, mode 0 to the middle item, and mode 2 to the right item. Those
     * visual positions are repurposed as Transparency, Adaptive, and Noise Cancellation.
     */
    fun toDetailPresenterMode(librePodsMode: Int): Int = when (librePodsMode) {
        LIBREPODS_TRANSPARENCY -> 1
        LIBREPODS_ADAPTIVE -> 0
        LIBREPODS_NOISE_CANCELLATION -> 2
        else -> MI_LINK_UNSELECTED
    }

    /**
     * MiLink's cached mode alternates between -2 and stale valid values while the detail card is
     * rebuilt. LibrePods owns the AACP session, so its cached mode is authoritative whenever it can
     * be represented by the three visible controls. Preserve the host value only when LibrePods is
     * in a mode that this card intentionally does not expose.
     */
    fun resolveDisplayMode(reportedMiLinkMode: Int, librePodsMode: Int): Int {
        val presenterMode = toDetailPresenterMode(librePodsMode)
        return if (presenterMode in MI_LINK_NOISE_CANCELLATION..MI_LINK_TRANSPARENCY) {
            presenterMode
        } else {
            reportedMiLinkMode
        }
    }
}
