/*
 * LibrePods - AirPods liberated from Apple's ecosystem
 * Copyright (C) 2025 LibrePods contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 */

package me.kavishdevar.librepods.utils

import android.content.Context
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

data class RootAvrcpVolumeResult(
    val success: Boolean,
    val output: String
)

/** Uses the APK's root app_process entry point when the app is not privileged. */
class RootAvrcpVolumeController(context: Context) {
    private val apkPath = context.applicationInfo.sourceDir
    private val sequence = AtomicLong()
    private val processLock = Any()
    private var prewarmedProcess: Command? = null
    private var prewarmedAddress: String? = null
    private var activeProcess: Command? = null

    fun prewarm(address: String, reason: String): RootAvrcpVolumeResult = synchronized(processLock) {
        val existing = prewarmedProcess
        if (existing?.isAlive == true && prewarmedAddress == address) {
            ProcessLifecycleTrace.record("root_prewarm_reused", "commandId=${existing.id} reason=$reason")
            return@synchronized RootAvrcpVolumeResult(
                true,
                "Root AVRCP bridge already prewarmed"
            )
        }
        existing?.let { closeCommand(it, "replace_prewarm") }
        return@synchronized try {
            prewarmedProcess = startPrewarmCommand(address, reason)
            prewarmedAddress = address
            RootAvrcpVolumeResult(true, "Root AVRCP bridge prewarming")
        } catch (error: IOException) {
            ProcessLifecycleTrace.record("root_prewarm_error", "reason=$reason type=${error.javaClass.simpleName}")
            prewarmedProcess = null
            prewarmedAddress = null
            RootAvrcpVolumeResult(false, error.message ?: "Unable to prewarm root command")
        }
    }

    fun resend(
        address: String,
        pulseVolume: Int,
        restoreVolume: Int,
        awaitPlayback: Boolean = false,
        reason: String,
    ): RootAvrcpVolumeResult {
        var process: Command? = null
        return try {
            process = synchronized(processLock) {
                activeProcess?.let { closeCommand(it, "superseded") }
                val existing = prewarmedProcess
                val existingAddress = prewarmedAddress
                prewarmedProcess = null
                prewarmedAddress = null
                val usingPrewarmed = existing?.isAlive == true && existingAddress == address
                val selected = if (usingPrewarmed) {
                    existing
                } else {
                    existing?.let { closeCommand(it, "replace_prewarm") }
                    if (awaitPlayback) {
                        startPrewarmCommand(address, reason)
                    } else {
                        startDirectCommand(address, pulseVolume, restoreVolume, reason)
                    }
                }
                if (usingPrewarmed || awaitPlayback) {
                    val command = "go ${pulseVolume.coerceIn(0, 255)} " +
                        "${restoreVolume.coerceIn(0, 255)}\n"
                    try {
                        selected.process.outputStream.write(command.toByteArray(Charsets.UTF_8))
                        selected.process.outputStream.flush()
                    } catch (error: IOException) {
                        closeCommand(selected, "trigger_write_failed")
                        throw error
                    }
                }
                ProcessLifecycleTrace.record("root_trigger_sent",
                    "commandId=${selected.id} awaitPlayback=$awaitPlayback prewarmed=$usingPrewarmed reason=$reason")
                activeProcess = selected
                selected
            }
            val running = process
            if (!running.process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                closeCommand(running, "command_timeout")
                RootAvrcpVolumeResult(false, "Root AVRCP command timed out")
            } else {
                running.reader.join(300)
                val output = running.output()
                RootAvrcpVolumeResult(running.process.exitValue() == 0, output)
            }
        } catch (error: IOException) {
            ProcessLifecycleTrace.record("root_request_error", "type=${error.javaClass.simpleName}")
            RootAvrcpVolumeResult(false, error.message ?: "Unable to start root command")
        } finally {
            process?.let { closeCommand(it, "request_finished") }
            synchronized(processLock) {
                if (activeProcess === process) activeProcess = null
            }
        }
    }

    fun cancel() {
        synchronized(processLock) {
            prewarmedProcess?.let { closeCommand(it, "cancel_prewarm") }
            prewarmedProcess = null
            prewarmedAddress = null
            activeProcess?.let { closeCommand(it, "cancel_active") }
            activeProcess = null
        }
    }

    private fun closeCommand(command: Command, reason: String) {
        ProcessLifecycleTrace.record("root_close_requested",
            "commandId=${command.id} reason=$reason alive=${command.isAlive}")
        // Closing the lease reaches the root child even if destroying su does not.
        runCatching { command.process.outputStream.close() }
        command.process.destroyForcibly()
    }

    private fun startPrewarmCommand(address: String, reason: String): Command {
        return startCommand("${shellQuote(address)} --wait", reason)
    }

    private fun startDirectCommand(
        address: String,
        pulseVolume: Int,
        restoreVolume: Int,
        reason: String,
    ): Command {
        val arguments = "${shellQuote(address)} ${pulseVolume.coerceIn(0, 255)} " +
            restoreVolume.coerceIn(0, 255)
        return startCommand(arguments, reason)
    }

    private fun startCommand(arguments: String, reason: String): Command {
        val id = "${android.os.Process.myPid()}-${android.os.SystemClock.elapsedRealtime()}-${sequence.incrementAndGet()}"
        val command = "CLASSPATH=${shellQuote(apkPath)} exec /system/bin/app_process " +
            "/system/bin $COMMAND_CLASS $arguments --session=$id"
        ProcessLifecycleTrace.record("root_create_requested", "commandId=$id waitMode=${arguments.endsWith("--wait")} reason=$reason")
        return Command(ProcessBuilder("su", "-c", command).redirectErrorStream(true).start(), id)
    }

    private class Command(val process: Process, val id: String) {
        val isAlive: Boolean get() = process.isAlive
        private val tail = StringBuilder()
        fun output(): String = synchronized(tail) { tail.toString() }
        // Drain while running: root framework output must not fill a pipe and stall exit.
        val reader = thread(name = "avrcp-output", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().use { input ->
                    while (true) {
                        val line = input.readLine() ?: break
                        synchronized(tail) {
                            tail.append(line.takeLast(4_096)).append('\n')
                            if (tail.length > 4_096) tail.delete(0, tail.length - 4_096)
                        }
                        if (line.startsWith("ROOT_AVRCP ")) {
                            ProcessLifecycleTrace.record("root_output", line.removePrefix("ROOT_AVRCP "))
                        }
                    }
                }
                val exit = runCatching { process.exitValue() }.getOrNull()
                ProcessLifecycleTrace.record("root_output_eof", "commandId=$id alive=${process.isAlive} " +
                    "exitCode=${exit ?: "not_observed"} tail=${output().takeLast(1_024)}")
            } catch (error: IOException) {
                ProcessLifecycleTrace.record("root_output_error", "commandId=$id type=${error.javaClass.simpleName}")
            }
        }
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"

    private companion object {
        const val COMMAND_CLASS =
            "me.kavishdevar.librepods.utils.AvrcpVolumeRootCommand"
        const val COMMAND_TIMEOUT_SECONDS = 8L
    }
}
