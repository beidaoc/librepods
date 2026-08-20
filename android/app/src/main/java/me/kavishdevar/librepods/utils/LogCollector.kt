/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.utils

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.services.NotificationAnnouncementService
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.time.Instant

class LogCollector(private val context: Context) {
    @Volatile
    private var isCollecting = false
    private var logProcess: Process? = null

    private suspend fun getPackageUid(packageName: String): String? {
        val packageManagerUid = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0).uid.toString()
        }.getOrNull()
        if (packageManagerUid != null) return packageManagerUid

        return executeRootCommand("cmd package list packages -U $packageName")
            .lineSequence()
            .mapNotNull { UID_PATTERN.find(it)?.groupValues?.get(1) }
            .firstOrNull()
    }

    private suspend fun getNamedUid(userName: String): String? =
        executeRootCommand("id -u $userName")
            .trim()
            .takeIf { it.matches(Regex("\\d+")) }

    private suspend fun getRelevantUids(): LinkedHashMap<String, String> {
        val uids = linkedMapOf(
            "librepods" to context.applicationInfo.uid.toString(),
            "android-system" to SYSTEM_UID
        )

        listOf("com.android.bluetooth", "com.google.android.bluetooth")
            .firstNotNullOfOrNull { getPackageUid(it) }
            ?.let { uids["bluetooth"] = it }
        getPackageUid(KEEP_PACKAGE)?.let { uids["keep"] = it }
        getPackageUid(NotificationAnnouncementService.XIAOMI_TTS_PACKAGE)
            ?.let { uids["xiaomi-tts"] = it }
        getNamedUid("audioserver")?.let { uids["audioserver"] = it }

        return uids
    }

    private suspend fun buildDiagnosticHeader(relevantUids: Map<String, String>): String {
        val rootIdentity = executeRootCommand("id").lineSequence().firstOrNull().orEmpty()
        val moduleInfo = executeRootCommand(
            "if [ -f /data/adb/modules/librepods/module.prop ]; then " +
                "cat /data/adb/modules/librepods/module.prop; else echo not-installed; fi"
        ).trim()
        val keepInfo = executeRootCommand(
            "dumpsys package $KEEP_PACKAGE | grep -E -m 2 'versionCode=|versionName='"
        ).trim().ifEmpty { "not-installed-or-unavailable" }
        val announcementsEnabled = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean(NotificationAnnouncementService.PREFERENCE_ENABLED, false)

        return buildString {
            appendLine("============================================================")
            appendLine("LibrePods diagnostic report")
            appendLine("capturedAtUtc=${Instant.now()}")
            appendLine(
                "app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
                    "flavor=${BuildConfig.FLAVOR}, buildType=${BuildConfig.BUILD_TYPE}"
            )
            appendLine(
                "device=${Build.MANUFACTURER} ${Build.MODEL}, " +
                    "android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
                    "build=${Build.DISPLAY}"
            )
            appendLine("root=${rootIdentity.ifEmpty { "unavailable" }}")
            appendLine("notificationAnnouncementsEnabled=$announcementsEnabled")
            appendLine(
                "capturedUids=" + relevantUids.entries.joinToString(",") { (name, uid) ->
                    "$name:$uid"
                }
            )
            appendLine(
                "systemLogFilter=audio routing, media focus/session, spatializer, " +
                    "Bluetooth manager, and relevant process lifecycle events"
            )
            appendLine("keepPackage:")
            appendLine(keepInfo)
            appendLine("rootModule:")
            appendLine(moduleInfo)
            appendLine("Note: logs may contain Bluetooth device names or addresses; review before sharing.")
            appendLine("============================================================")
            appendLine()
        }
    }

    suspend fun startLogCollection(listener: (String) -> Unit): String {
        return withContext(Dispatchers.IO) {
            isCollecting = true
            val relevantUids = getRelevantUids()
            val uidFilter = relevantUids.values.distinct().joinToString(",")
            val filteredSystemUids = setOfNotNull(
                relevantUids["android-system"],
                relevantUids["audioserver"]
            )
            val logcatCommand = buildString {
                append("logcat -b all -T 1 -v threadtime,uid")
                if (uidFilter.isNotEmpty()) append(" --uid=$uidFilter")
            }

            val logs = StringBuilder()
            try {
                logProcess = ProcessBuilder("su", "-c", logcatCommand)
                    .redirectErrorStream(true)
                    .start()
                logs.append(buildDiagnosticHeader(relevantUids))
                addLogMarker(LogMarkerType.START)
                val reader = BufferedReader(InputStreamReader(logProcess!!.inputStream))
                var line: String? = null

                while (isCollecting && reader.readLine().also { line = it } != null) {
                    line?.let {
                        if (!shouldKeepLogLine(it, filteredSystemUids)) return@let

                        if (it.contains("<LogCollector:")) {
                            logs.append("\n=============\n")
                        }

                        logs.append(it).append("\n")
                        listener(it)

                        if (it.contains("<LogCollector:")) {
                            logs.append("=============\n\n")
                        }
                    }
                }
            } catch (e: Exception) {
                if (isCollecting) {
                    logs.append("Error collecting logs: ${e.message}\n")
                    e.printStackTrace()
                }
            }
            logs.toString()
        }
    }

    fun stopLogCollection() {
        isCollecting = false
        logProcess?.destroy()
        logProcess = null
    }

    suspend fun saveLogToInternalStorage(fileName: String, content: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val logsDir = File(context.filesDir, "logs")
                if (!logsDir.exists()) {
                    logsDir.mkdir()
                }

                val file = File(logsDir, fileName)
                file.writeText(content)
                return@withContext file
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext null
            }
        }
    }

    fun addLogMarker(markerType: LogMarkerType, details: String = "") {
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())

        val marker = when (markerType) {
            LogMarkerType.START -> "<LogCollector:Start> [$timestamp] Beginning diagnostic capture"
            LogMarkerType.SUCCESS -> "<LogCollector:Complete:Success> [$timestamp] Capture completed successfully"
            LogMarkerType.FAILURE -> "<LogCollector:Complete:Failed> [$timestamp] Capture failed"
            LogMarkerType.CUSTOM -> "<LogCollector:Custom:$details> [$timestamp]"
        }

        Log.i(LOG_TAG, marker)
    }

    enum class LogMarkerType {
        START,
        SUCCESS,
        FAILURE,
        CUSTOM
    }

    private suspend fun executeRootCommand(command: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = StringBuilder()
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }

                process.waitFor()
                output.toString()
            } catch (e: Exception) {
                e.printStackTrace()
                ""
            }
        }
    }

    companion object {
        private const val LOG_TAG = "LibrePodsLogCollector"
        private const val KEEP_PACKAGE = "com.gotokeep.keep"
        private const val SYSTEM_UID = "1000"
        private val UID_PATTERN = Regex("uid:(\\d+)")
        private val THREADTIME_UID_PATTERN = Regex(
            "^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+" +
                "(\\d+)\\s+\\d+\\s+\\d+\\s+([VDIWEFAS])\\s+([^:]+):"
        )
        private val RELEVANT_SYSTEM_TAG_PREFIXES = listOf(
            "Audio",
            "AS.",
            "Spatializer",
            "MediaFocus",
            "MediaSession",
            "BluetoothManager",
            "BtHelper",
            "APM_",
            "audio_hw"
        )
        private val PROCESS_LIFECYCLE_TAGS = setOf(
            "ActivityManager",
            "am_anr",
            "am_crash",
            "am_kill",
            "am_proc_died"
        )
        private val RELEVANT_PROCESS_NAMES = listOf(
            "me.kavishdevar.librepods",
            "com.android.bluetooth",
            "com.google.android.bluetooth",
            "com.gotokeep.keep",
            "audioserver"
        )

        internal fun shouldKeepLogLine(line: String, filteredSystemUids: Set<String>): Boolean {
            val match = THREADTIME_UID_PATTERN.find(line) ?: return true
            val uid = match.groupValues[1]
            if (uid !in filteredSystemUids) return true

            val priority = match.groupValues[2].first()
            val tag = match.groupValues[3].trim()
            if (RELEVANT_SYSTEM_TAG_PREFIXES.any(tag::startsWith)) return true
            if (tag == "PAL") return priority == 'W' || priority == 'E' || priority == 'F'
            if (tag in PROCESS_LIFECYCLE_TAGS) {
                return RELEVANT_PROCESS_NAMES.any { line.contains(it, ignoreCase = true) }
            }
            return false
        }
    }
}
