package me.kavishdevar.librepods.utils

import android.content.Context
import java.time.Instant

/** Root probes run only during a user-started capture, never on the UI thread. */
internal object ProcessDiagnostics {
    fun snapshot(context: Context, phase: String): String {
        val pkg = context.packageName
        require(pkg.matches(Regex("[a-zA-Z0-9_.]+")))
        val command = """
            set -o pipefail
            echo 'clockTicksPerSecond:'; getconf CLK_TCK
            echo 'uptime:'; cat /proc/uptime
            pids=${'$'}(ps -A -o PID,NAME,ARGS | awk '${'$'}2 == "$pkg" || (${'$'}2 ~ /^app_process(32|64)?${'$'}/ && index(${'$'}0,"$pkg.utils.AvrcpVolumeRootCommand") > 0) {print ${'$'}1}') || { echo process_scan_failed; exit 1; }
            echo "matchedPids=${'$'}pids"
            for pid in ${'$'}(echo "${'$'}pids" | head -n 8); do
              echo "PROCESS pid=${'$'}pid"
              cat /proc/${'$'}pid/stat
              grep -E '^(Name|State|PPid|Uid|Threads|voluntary_ctxt_switches|nonvoluntary_ctxt_switches):' /proc/${'$'}pid/status
              echo 'wchan:'; cat /proc/${'$'}pid/wchan; echo
              count=0
              for task in /proc/${'$'}pid/task/*; do
                count=${'$'}((count+1)); [ "${'$'}count" -gt 20 ] && break
                echo "THREAD tid=${'$'}{task##*/}"; cat "${'$'}task/stat"
                cat "${'$'}task/wchan"; echo
              done
            done
        """.trimIndent()
        val result = runDiagnosticCommand(listOf("su", "-c", command), timeoutMillis = 2_500, maxChars = 24_576)
        return "\nPROCESS_SNAPSHOT $phase\nat=${Instant.now()}\n" +
            "limits=8 processes, 20 threads each; PID may exit during read\n" +
            "statFields=Linux proc stat; utime/stime/starttime in clock ticks, compare same PID+starttime across snapshots\n" +
            "contextSwitchesAreNotWakeLocks=true\n${result.report()}\nEND_PROCESS_SNAPSHOT\n"
    }

    fun services(context: Context, phase: String): String {
        val pkg = context.packageName
        require(pkg.matches(Regex("[a-zA-Z0-9_.]+")))
        val result = runDiagnosticCommand(listOf("su", "-c", "dumpsys activity services $pkg"),
            timeoutMillis = 3_000, maxChars = 16_384)
        return "\nAPP_SERVICE_BINDINGS $phase\nat=${Instant.now()}\n${result.report()}\nEND_APP_SERVICE_BINDINGS\n"
    }
}
