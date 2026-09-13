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

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.milink.MiLinkAirPodsBridgeContract
import me.kavishdevar.librepods.services.NotificationAnnouncementService
import me.kavishdevar.librepods.services.ServiceManager
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LogCollector private constructor(context: Context) {
    private val context = context.applicationContext
    private val stopReason = AtomicReference<String?>(null)
    private val running = AtomicBoolean(false)
    private val commandReports = mutableListOf<String>()
    data class CaptureState(
        val running: Boolean = false,
        val stopping: Boolean = false,
        val activeFile: File? = null,
        val result: CaptureResult? = null,
    )

    private val _captureState = MutableStateFlow(CaptureState())
    val captureState = _captureState.asStateFlow()

    enum class Status { COMPLETE, PARTIAL, FAILED }
    data class CaptureResult(val file: File?, val status: Status, val reason: String, val lineCount: Long)

    private fun getPackageUid(packageName: String): String? {
        val packageManagerUid = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0).uid.toString()
        }.getOrNull()
        if (packageManagerUid != null) return packageManagerUid

        return executeRootCommand("cmd package list packages -U $packageName")
            .lineSequence()
            .mapNotNull { UID_PATTERN.find(it)?.groupValues?.get(1) }
            .firstOrNull()
    }

    private fun getNamedUid(userName: String): String? =
        executeRootCommand("id -u $userName")
            .trim()
            .takeIf { it.matches(Regex("\\d+")) }

    private fun getRelevantUids(): LinkedHashMap<String, String> {
        val uids = linkedMapOf(
            "librepods" to context.applicationInfo.uid.toString(),
            "android-system" to SYSTEM_UID
        )

        listOf("com.android.bluetooth", "com.google.android.bluetooth")
            .firstNotNullOfOrNull { getPackageUid(it) }
            ?.let { uids["bluetooth"] = it }
        getPackageUid(MiLinkAirPodsBridgeContract.MI_LINK_PACKAGE)?.let { uids["milink"] = it }
        getPackageUid(KEEP_PACKAGE)?.let { uids["keep"] = it }
        getPackageUid(NotificationAnnouncementService.XIAOMI_TTS_PACKAGE)
            ?.let { uids["xiaomi-tts"] = it }
        getNamedUid("audioserver")?.let { uids["audioserver"] = it }

        return uids
    }

    private fun buildDiagnosticHeader(relevantUids: Map<String, String>): String {
        val moduleInfo = executeRootCommand(
            "if [ -f /data/adb/modules/librepods/module.prop ]; then " +
                "cat /data/adb/modules/librepods/module.prop; else echo not-installed; fi"
        ).trim()
        val keepInfo = executeRootCommand(
            "dumpsys package $KEEP_PACKAGE | grep -E -m 2 'versionCode=|versionName='"
        ).trim().ifEmpty { "not-installed-or-unavailable" }
        val announcementsEnabled = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean(NotificationAnnouncementService.PREFERENCE_ENABLED, false)
        val notificationAccess = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        }.getOrDefault(false)
        val keyguardLocked = runCatching {
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        }.getOrDefault(false)
        val xiaomiTtsAvailable = runCatching {
            NotificationAnnouncementService.isXiaomiTtsAvailable(context)
        }.getOrDefault(false)

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
            appendLine("root=verified uid 0")
            appendLine("historyRequestedSeconds=60 (limited by available logcat buffers)")
            appendLine("bluetoothEvidence=logcat across UIDs, cached state every 5s and at connection events, bluetooth_manager dump at start/end")
            appendLine("hciSnoopPackets=not collected; earbud internal decisions may be unavailable")
            appendLine("timeZone=${java.util.TimeZone.getDefault().id}")
            appendLine("maxCaptureBytes=16777216")
            appendLine("milinkLogTransport=Android logcat mirror, tag LibrePodsMiLink")
            appendLine("milinkUid=${relevantUids["milink"] ?: "unavailable"}")
            appendLine("unavailableLogSources=" +
                listOf("bluetooth", "milink", "keep", "xiaomi-tts", "audioserver")
                    .filterNot(relevantUids::containsKey).joinToString(","))
            appendLine("notificationAnnouncementsEnabled=$announcementsEnabled")
            appendLine("notificationListenerAccess=$notificationAccess")
            appendLine("keyguardLockedAtCaptureStart=$keyguardLocked")
            appendLine("xiaomiTtsAvailable=$xiaomiTtsAvailable")
            appendLine("notificationTracePrivacy=message title, sender, and body are omitted")
            appendLine(
                "capturedUids=" + relevantUids.entries.joinToString(",") { (name, uid) ->
                    "$name:$uid"
                }
            )
            appendLine(
                "systemLogFilter=audio routing, media focus/session, spatializer, " +
                    "Bluetooth manager, notification announcement trace, and relevant process " +
                    "lifecycle events"
            )
            appendLine("keepPackage:")
            appendLine(keepInfo)
            appendLine("commandDiagnostics:")
            commandReports.forEach { appendLine(it) }
            appendLine("rootModule:")
            appendLine(moduleInfo)
            appendLine("Note: logs may contain Bluetooth device names or addresses; review before sharing.")
            appendLine("============================================================")
            appendLine()
        }
    }

    /** The worker owns its lifetime so cancelling a screen's await does not cancel finalization. */
    @Synchronized
    fun startLogCollection(): Deferred<CaptureResult> {
        check(running.compareAndSet(false, true)) { "A capture is already running" }
        stopReason.set(null)
        _captureState.value = CaptureState(running = true)
        return CoroutineScope(Dispatchers.IO).async {
            val result = try {
                collect()
            } catch (error: Exception) {
                CaptureResult(_captureState.value.activeFile, Status.FAILED,
                    "capture_failed:${error.message}", 0)
            }
            synchronized(this@LogCollector) {
                running.set(false)
                _captureState.value = CaptureState(result = result)
            }
            result
        }
    }

    @Synchronized
    fun stopLogCollection(reason: String = "user_stop") {
        if (running.get() && stopReason.compareAndSet(null, reason)) {
            _captureState.update { it.copy(stopping = true) }
        }
    }

    private suspend fun collect(): CaptureResult {
        val sessionId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val historySince = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            .format(Date(startedAt - 60_000))
        val baseName = "airpods_log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(Date(startedAt)) + "_" + sessionId.take(8)
        var capture: DiagnosticCaptureFile? = null
        var status = Status.FAILED
        var reason = "initialization_failed"
        var lineCount = 0L
        var miLinkLineCount = 0L
        var exitCode: Int? = null
        var liveConfirmed = false
        var stopMarkerObserved = false
        var rootVerified = false
        var connectionEvidenceCount = 0L
        val recentConnectionEvidence = ArrayDeque<String>()
        try {
            val sink = DiagnosticCaptureFile(File(context.filesDir, "logs"), baseName,
                footerReserveBytes = 128L * 1024)
            capture = sink
            _captureState.update { it.copy(activeFile = sink.file) }
            sink.append("LibrePods diagnostic capture\nsessionId=$sessionId\n" +
                "startedAtUtc=${Instant.ofEpochMilli(startedAt)}\n" +
                "A missing CAPTURE_END footer means this capture was interrupted.\n\n")
            sink.flush()
            sink.append(buildStateSnapshot("START"))
            sink.flush()
            commandReports.clear()
            val root = runDiagnosticCommand(listOf("su", "-c", "id"), cancelled = { stopReason.get() != null })
            check(root.succeeded && Regex("(?:^|\\s)uid=0(?:\\D|$)").containsMatchIn(root.output)) {
                "root_unavailable: ${root.report()}"
            }
            rootVerified = true
            val relevantUids = getRelevantUids()
            sink.append(buildDiagnosticHeader(relevantUids))
            sink.append(buildBluetoothDump("START"))
            sink.flush()
            if (stopReason.get() != null) {
                reason = "stopped_before_logcat_start:${stopReason.get()}"
            } else {
                val filteredSystemUids = setOfNotNull(relevantUids["android-system"], relevantUids["audioserver"])
                // Select Bluetooth tags after reading: vendor and routing processes may use other UIDs.
                val command = "exec logcat -b all -T '$historySince' -v threadtime,uid"
                val retainedUids = relevantUids.values.toSet()
                val readyMarker = "<LogCollector:Ready:$sessionId>"
                val stopMarker = "<LogCollector:Stop:$sessionId>"
                val launchAt = SystemClock.elapsedRealtime()
                var stopAt: Long? = null
                var lastFlushAt = launchAt
                var lastSnapshotAt = launchAt
                var lastEventSnapshotAt = launchAt - 1_000
                DiagnosticProcess(listOf("su", "-c", command)).use { process ->
                    Log.i(LOG_TAG, readyMarker)
                    while (true) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastFlushAt >= 1_000) {
                            sink.flush()
                            lastFlushAt = now
                        }
                        if (stopReason.get() != null && stopAt == null) {
                            stopAt = now
                            Log.i(LOG_TAG, stopMarker)
                        }
                        if (stopAt != null && now - stopAt >= 2_000) {
                            reason = "stop_drain_timeout:${stopReason.get()}"
                            status = if (liveConfirmed) Status.PARTIAL else Status.FAILED
                            break
                        }
                        if (liveConfirmed && stopAt == null && now - lastSnapshotAt >= 5_000) {
                            if (!sink.append(buildStateSnapshot("PERIODIC"))) {
                                status = Status.PARTIAL
                                reason = "size_limit"
                                break
                            }
                            lastSnapshotAt = now
                        }
                        if (stopAt == null && !liveConfirmed && now - launchAt >= 10_000) {
                            reason = "logcat_start_timeout"
                            break
                        }
                        when (val event = process.poll()) {
                            is DiagnosticProcess.Event.Line -> {
                                val line = event.text
                                if (!shouldKeepLogLine(line, filteredSystemUids, retainedUids)) continue
                                if (!sink.append(line + "\n")) {
                                    status = if (liveConfirmed) Status.PARTIAL else Status.FAILED
                                    reason = "size_limit"
                                    break
                                }
                                if (THREADTIME_UID_PATTERN.containsMatchIn(line)) lineCount++
                                if (THREADTIME_UID_PATTERN.find(line)?.groupValues?.get(3)?.trim() == "LibrePodsMiLink") {
                                    miLinkLineCount++
                                }
                                if (line.contains(readyMarker)) liveConfirmed = true
                                val connectionEvent = connectionEventKind(line)
                                if (connectionEvent != null) {
                                    connectionEvidenceCount++
                                    if (recentConnectionEvidence.size == 16) recentConnectionEvidence.removeFirst()
                                    recentConnectionEvidence.addLast("$connectionEvent ${line.take(512)}")
                                    // History is retained as evidence; a current snapshot cannot reconstruct past state.
                                    if (liveConfirmed && stopAt == null && now - lastEventSnapshotAt >= 1_000) {
                                        if (!sink.append("\nCONNECTION_EVENT kind=$connectionEvent\nsourceLog=${line.take(768)}\n" +
                                                buildStateSnapshot("CONNECTION_EVENT"))) {
                                            status = Status.PARTIAL
                                            reason = "size_limit"
                                            break
                                        }
                                        lastEventSnapshotAt = now
                                        lastSnapshotAt = now
                                    }
                                }
                                if (stopAt != null && line.contains(stopMarker)) {
                                    stopMarkerObserved = true
                                    status = if (liveConfirmed) Status.COMPLETE else Status.FAILED
                                    reason = stopReason.get() ?: "user_stop"
                                    break
                                }
                            }
                            is DiagnosticProcess.Event.End -> {
                                exitCode = process.exitCode()
                                reason = "logcat_exited_unexpectedly" +
                                    (event.error?.let { ":$it" } ?: "")
                                status = if (liveConfirmed) Status.PARTIAL else Status.FAILED
                                break
                            }
                            null -> Unit
                        }
                    }
                }
            }
        } catch (error: Exception) {
            status = if (liveConfirmed) Status.PARTIAL else Status.FAILED
            reason = error.javaClass.simpleName + ": " + error.message
        }
        val sink = capture ?: return CaptureResult(null, status, reason, lineCount)
        return try {
            if (rootVerified) sink.append(buildBluetoothDump("END"), footer = true)
            sink.append(buildStateSnapshot("END"), footer = true)
            sink.append("\nCONNECTION_EVIDENCE\nrecordCount=$connectionEvidenceCount (related records may describe the same event)\n" +
                "rootCause=not inferred; reason codes and cached state do not prove a takeover\n" +
                recentConnectionEvidence.joinToString("\n") + "\nEND_CONNECTION_EVIDENCE\n", footer = true)
            check(sink.append("\nCAPTURE_END\nstatus=$status\nreason=$reason\n" +
                "finishedAtUtc=${Instant.now()}\nlineCount=$lineCount\n" +
                "liveLogcatConfirmed=$liveConfirmed\nstopMarkerObserved=$stopMarkerObserved\n" +
                "miLinkLineCount=$miLinkLineCount (zero does not prove the hook is absent)\n" +
                "logcatExitCode=${exitCode ?: "not_observed"}\n" +
                "bytesBeforeFooter=${sink.bytesWritten}\n", footer = true)) { "Unable to write capture footer" }
            CaptureResult(sink.finish(), status, reason, lineCount)
        } catch (error: Exception) {
            CaptureResult(sink.file, Status.PARTIAL, "finalization_failed:${error.message}", lineCount)
        } finally {
            runCatching { sink.close() }
        }
    }

    private fun buildBluetoothDump(phase: String): String {
        val result = runDiagnosticCommand(
            listOf("su", "-c", "dumpsys bluetooth_manager"),
            timeoutMillis = 3_000,
            maxChars = 8_192,
        )
        return "\nBLUETOOTH_MANAGER $phase\ncapturedAtUtc=${Instant.now()}\n" +
            "boundedDump=true; output_limit means this dump is truncated\n" +
            result.report() + "\nEND_BLUETOOTH_MANAGER\n"
    }

    private suspend fun buildStateSnapshot(phase: String): String = withContext(Dispatchers.Main.immediate) {
        buildString {
            appendLine("\nSTATE_SNAPSHOT $phase")
            appendLine("capturedAtUtc=${Instant.now()}, elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
            appendLine("snapshotConsistency=best-effort cached state; no connection or playback changes")
            runCatching {
                appendLine("keyguardLocked=${context.getSystemService(KeyguardManager::class.java).isKeyguardLocked}")
                val audio = context.getSystemService(AudioManager::class.java)
                appendLine("audioMode=${audio.mode}, musicActive=${audio.isMusicActive}")
                appendLine("availableAudioOutputTypes=" + audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .joinToString(",") { it.type.toString() })
            }.onFailure { appendLine("audioSnapshotError=${it.javaClass.simpleName}:${it.message}") }
            runCatching {
                appendLine(ServiceManager.getService()?.diagnosticStateSnapshot() ?: "airPodsService=unavailable")
            }.onFailure { appendLine("serviceSnapshotError=${it.javaClass.simpleName}:${it.message}") }
            appendLine("END_STATE_SNAPSHOT")
        }
    }

    private fun executeRootCommand(command: String): String {
        val result = runDiagnosticCommand(listOf("su", "-c", command), cancelled = { stopReason.get() != null })
        commandReports.add("command=$command, exitCode=${result.exitCode ?: "unknown"}, failure=${result.failure ?: "none"}" +
            if (result.succeeded) "" else ", output=${result.output.take(1024).trim()}")
        return if (result.succeeded) result.output else ""
    }

    companion object {
        @Volatile private var instance: LogCollector? = null

        fun getInstance(context: Context): LogCollector = instance ?: synchronized(this) {
            instance ?: LogCollector(context).also { instance = it }
        }

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
            "audio_hw",
            "LibrePodsMiLink"
        )
        private val CROSS_UID_BLUETOOTH_TAG_PREFIXES = listOf(
            "bluetooth", "bt_", "btif", "btm_", "bta_", "l2c", "rfcomm", "avrcp",
            "a2dp", "headset", "btadapter", "adapterservice", "btgatt", "btscan",
            "vendor.qti.bluetooth", "vendor.qti.qhci", "librepodsmilink",
            "plgin_miuiaudioswitch", "miuibluetooth", "aircore", "btdevice",
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
            "com.milink.service",
            "com.xiaomi.mibrain.speech",
            "audioserver"
        )

        internal fun shouldKeepLogLine(
            line: String,
            filteredSystemUids: Set<String>,
            retainedUids: Set<String>? = null,
        ): Boolean {
            val match = THREADTIME_UID_PATTERN.find(line) ?: return retainedUids == null ||
                line.startsWith("---------") || line.startsWith("logcat:")
            val uid = match.groupValues[1]
            val priority = match.groupValues[2].first()
            val tag = match.groupValues[3].trim()
            if (CROSS_UID_BLUETOOTH_TAG_PREFIXES.any(tag.lowercase(Locale.ROOT)::startsWith)) return true
            if (retainedUids != null && uid !in retainedUids) return false
            if (uid !in filteredSystemUids) return true
            if (RELEVANT_SYSTEM_TAG_PREFIXES.any(tag::startsWith)) return true
            if (tag == "PAL") return priority == 'W' || priority == 'E' || priority == 'F'
            if (tag in PROCESS_LIFECYCLE_TAGS) {
                return RELEVANT_PROCESS_NAMES.any { line.contains(it, ignoreCase = true) }
            }
            return false
        }

        private fun connectionEventKind(line: String): String? {
            val match = THREADTIME_UID_PATTERN.find(line) ?: return null
            val tag = match.groupValues[3].trim()
            return when {
                tag == "BluetoothRemoteDevices" && line.contains("aclStateChangeCallback:") -> "ACL_STATE"
                tag == "bt_shim_hci" && line.contains("disconnection from GD") -> "HCI_DISCONNECT"
                tag == "AirPodsConnectionTrace" -> "APP_CONNECTION_ACTION"
                tag == "AirPodsService" && line.contains("socket closed (bytesRead = -1)") -> "AACP_EOF"
                tag == "AirPodsService" && line.contains("Socket connected") -> "AACP_CONNECTED"
                else -> null
            }
        }
    }
}
