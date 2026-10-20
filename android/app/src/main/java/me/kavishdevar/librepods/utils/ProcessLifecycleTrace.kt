package me.kavishdevar.librepods.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import me.kavishdevar.librepods.BuildConfig
import java.io.File
import java.time.Instant

/** Lifecycle evidence only: no notification contents, intent extras, or polling timer. */
internal object ProcessLifecycleTrace {
    const val TAG = "LibrePodsProcess"
    private val session = "${Process.myPid()}-${SystemClock.elapsedRealtime()}"
    private var journal: BoundedDiagnosticJournal? = null

    fun initialize(context: Context) {
        journal = BoundedDiagnosticJournal(File(context.noBackupFilesDir, "process-diagnostics"))
        val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()
        record("process_created", "uid=${Process.myUid()} bootCount=${boot ?: "unknown"} " +
            "package=${context.packageName} version=${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}")
        history(context).lineSequence().forEach { record("system_history", it) }
    }

    fun record(event: String, details: String = "") {
        val line = "at=${Instant.now()} elapsedMs=${SystemClock.elapsedRealtime()} " +
            "session=$session pid=${Process.myPid()} event=$event " +
            details.replace('\n', ' ').replace('\r', ' ').take(2_000)
        Log.i(TAG, line)
        runCatching { journal?.append(line) }
            .onFailure { Log.w(TAG, "journal_write_failed type=${it.javaClass.simpleName}") }
    }

    fun persisted(context: Context): String = runCatching {
        (journal ?: BoundedDiagnosticJournal(File(context.noBackupFilesDir, "process-diagnostics"))).read()
    }.getOrElse { "journal_read_failed=${it.javaClass.simpleName}\n" }

    fun history(context: Context): String = buildString {
        val manager = context.getSystemService(ActivityManager::class.java)
        runCatching {
            val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
            appendLine("exitHistoryCount=${exits.size}")
            exits.forEach { info ->
                appendLine("previousExit at=${Instant.ofEpochMilli(info.timestamp)} pid=${info.pid} " +
                    "process=${info.processName} reason=${exitReason(info.reason)}(${info.reason}) " +
                    "status=${info.status} importance=${info.importance} pssKb=${info.pss} rssKb=${info.rss} " +
                    "description=${info.description?.replace('\n', ' ')?.take(240) ?: "unavailable"}")
            }
        }.onFailure { appendLine("exitHistoryError=${it.javaClass.simpleName}") }
        if (Build.VERSION.SDK_INT >= 35) {
            runCatching {
                val starts = manager.getHistoricalProcessStartReasons(8)
                appendLine("startHistoryCount=${starts.size}; reason/type are Android ApplicationStartInfo values")
                starts.forEach { info ->
                    appendLine("previousStart pid=${info.pid} process=${info.processName} " +
                        "reason=${info.reason} type=${info.startType} forceStopped=${info.wasForceStopped()} " +
                        "action=${info.intent?.action ?: "none"} component=${info.intent?.component?.flattenToShortString() ?: "unknown"} " +
                        "timestampsNs=${info.startupTimestamps}")
                }
            }.onFailure { appendLine("startHistoryError=${it.javaClass.simpleName}") }
        } else appendLine("startHistory=unavailable_before_API_35")
        appendLine("callerAttribution=see system ActivityManager/ActivityTaskManager lines; unknown when absent")
    }

    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        else -> "OTHER_OR_UNKNOWN"
    }
}
