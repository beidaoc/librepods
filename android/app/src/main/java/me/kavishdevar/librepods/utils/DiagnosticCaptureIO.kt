package me.kavishdevar.librepods.utils

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A bounded pipe reader: stopping a quiet command never waits on readLine(). */
internal class DiagnosticProcess(command: List<String>) : Closeable {
    sealed interface Event {
        data class Line(val text: String) : Event
        data class End(val error: String? = null) : Event
    }

    // These commands never consume input. In particular, su must not wait for an open stdin.
    private val process = ProcessBuilder(command).redirectErrorStream(true).start().also {
        it.outputStream.close()
    }
    private val events = ArrayBlockingQueue<Event>(128)
    private val reader = thread(name = "diagnostic-pipe", isDaemon = true) {
        try {
            process.inputStream.bufferedReader().use { input ->
                while (true) events.put(Event.Line(input.readLine() ?: break))
            }
            events.put(Event.End())
        } catch (_: InterruptedException) {
            // The owner has closed the process.
        } catch (error: Exception) {
            try {
                events.put(Event.End(error.message))
            } catch (_: InterruptedException) {
                // The owner no longer needs the terminal event.
            }
        }
    }

    fun poll(timeoutMillis: Long = 100): Event? = events.poll(timeoutMillis, TimeUnit.MILLISECONDS)

    fun exitCode(): Int? = if (process.waitFor(100, TimeUnit.MILLISECONDS)) process.exitValue() else null

    override fun close() {
        process.destroy()
        if (!process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        reader.interrupt()
        // Do not close BufferedReader from another thread while it holds its read lock.
        runCatching { process.inputStream.close() }
        runCatching { process.outputStream.close() }
        runCatching { process.errorStream.close() }
        reader.join(300)
    }
}

internal data class DiagnosticCommandResult(
    val output: String,
    val exitCode: Int? = null,
    val failure: String? = null,
) {
    val succeeded: Boolean get() = exitCode == 0 && failure == null
    fun report(): String = "exitCode=${exitCode ?: "unknown"}, failure=${failure ?: "none"}\n$output"
}

internal fun runDiagnosticCommand(
    command: List<String>,
    timeoutMillis: Long = 3_000,
    maxChars: Int = 32_768,
    cancelled: () -> Boolean = { false },
): DiagnosticCommandResult {
    if (cancelled()) return DiagnosticCommandResult("", failure = "cancelled")
    val output = StringBuilder()
    return try {
        DiagnosticProcess(command).use { process ->
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            var outputEnded = false
            while (true) {
                if (cancelled()) return DiagnosticCommandResult(output.toString(), failure = "cancelled")
                if (System.nanoTime() >= deadline) {
                    return DiagnosticCommandResult(output.toString(), failure = "timeout")
                }
                // Pipe EOF and process exit are separate events (including with KernelSU).
                // Keep waiting within the original deadline instead of treating 100 ms as failure.
                if (outputEnded) {
                    val exitCode = process.exitCode()
                    if (exitCode != null) return DiagnosticCommandResult(output.toString(), exitCode)
                    continue
                }
                when (val event = process.poll()) {
                    is DiagnosticProcess.Event.Line -> {
                        val remaining = maxChars - output.length
                        output.append((event.text + "\n").take(remaining.coerceAtLeast(0)))
                        if (event.text.length + 1 > remaining) {
                            return DiagnosticCommandResult(output.toString(), failure = "output_limit")
                        }
                    }
                    is DiagnosticProcess.Event.End -> {
                        if (event.error != null) return DiagnosticCommandResult(
                            output.toString(), failure = "read_failed:${event.error}",
                        )
                        outputEnded = true
                    }
                    null -> Unit
                }
            }
            @Suppress("UNREACHABLE_CODE")
            DiagnosticCommandResult(output.toString())
        }
    } catch (error: Exception) {
        DiagnosticCommandResult(output.toString(), failure = error.javaClass.simpleName + ": " + error.message)
    }
}

/** Leaves a readable .partial.txt on process death; reserves space for a final snapshot/footer. */
internal class DiagnosticCaptureFile(
    directory: File,
    private val baseName: String,
    private val maxBytes: Long = 16L * 1024 * 1024,
    private val footerReserveBytes: Long = 64L * 1024,
) : Closeable {
    var file: File = File(directory, "$baseName.partial.txt")
        private set
    private val stream: FileOutputStream
    private val output: java.io.BufferedOutputStream
    var bytesWritten: Long = 0
        private set

    init {
        require(footerReserveBytes in 1 until maxBytes)
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create log directory" }
        check(file.createNewFile()) { "Capture file already exists" }
        stream = FileOutputStream(file)
        output = stream.buffered()
    }

    fun append(text: String, footer: Boolean = false): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val limit = if (footer) maxBytes else maxBytes - footerReserveBytes
        if (bytesWritten + bytes.size > limit) return false
        output.write(bytes)
        bytesWritten += bytes.size
        return true
    }

    fun flush() = output.flush()

    fun finish(): File {
        flush()
        stream.fd.sync()
        output.close()
        val destination = File(file.parentFile, "$baseName.txt")
        check(file.renameTo(destination)) { "Unable to finalize capture; partial file retained" }
        file = destination
        return destination
    }

    override fun close() = output.close()
}
