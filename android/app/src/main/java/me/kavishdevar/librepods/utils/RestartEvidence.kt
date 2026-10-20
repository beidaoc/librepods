package me.kavishdevar.librepods.utils

/** Labels explicit records only; PID changes and generic errors are not causal evidence. */
internal object RestartEvidence {
    fun classify(line: String, packageName: String): String? {
        val packageToken = Regex("(?<![A-Za-z0-9_.])${Regex.escape(packageName)}(?![A-Za-z0-9_.])")
        if (!packageToken.containsMatchIn(line)) return null
        val kind = when {
            "ActivityManager:" in line && "Force stopping $packageName" in line -> "SYSTEM_FORCE_STOP"
            "ActivityManager:" in line && "Start proc " in line && "for bound-service" in line -> "SYSTEM_BOUND_SERVICE_START"
            "ActivityManager:" in line && "Start proc " in line -> "SYSTEM_PROCESS_START"
            "ActivityManager:" in line && "Scheduling restart of" in line -> "SYSTEM_SERVICE_RESTART_SCHEDULED"
            "ActivityTaskManager:" in line && "START u" in line -> "SYSTEM_ACTIVITY_START"
            "ActivityManager:" in line && "has died" in line -> "SYSTEM_PROCESS_DEATH"
            else -> return null
        }
        return "$kind sourceLog=$line"
    }
}
