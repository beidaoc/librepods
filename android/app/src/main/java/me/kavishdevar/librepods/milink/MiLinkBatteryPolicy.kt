package me.kavishdevar.librepods.milink

internal object MiLinkBatteryPolicy {
    fun owns(live: Boolean, connected: Boolean, currentAddress: String, requestedAddress: String?): Boolean =
        live && connected && currentAddress.isNotBlank() && !requestedAddress.isNullOrBlank() &&
            currentAddress.equals(requestedAddress, ignoreCase = true)
}
