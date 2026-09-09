package me.kavishdevar.librepods.milink

/** MiLink 17.2.5's presenter modes are distinct from its runtime ANC values. */
internal object MiLinkLegacyAncSelection {
    const val TIMEOUT_MS = 2_500L

    data class Pending(val mode: Int, val deadline: Long)

    fun commandMode(presenterMode: Int): Int? = when (presenterMode) {
        1 -> MiLinkAncModeMapper.LIBREPODS_TRANSPARENCY
        0 -> MiLinkAncModeMapper.LIBREPODS_ADAPTIVE
        2 -> MiLinkAncModeMapper.LIBREPODS_NOISE_CANCELLATION
        else -> null
    }

    fun pendingAfterReport(
        pending: Pending?,
        reportedMode: Int,
        now: Long,
        isAncReport: Boolean = true,
    ): Pending? = pending?.takeIf {
        now < it.deadline && (!isAncReport || reportedMode != it.mode)
    }

    fun displayMode(reportedMode: Int, pending: Pending?, now: Long): Int =
        MiLinkAncModeMapper.toDetailPresenterMode(
            pending?.takeIf { now < it.deadline }?.mode ?: reportedMode,
        )
}
