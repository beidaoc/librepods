package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class DiagnosticCaptureIOTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun flushPreservesDataBeforeCaptureFinishes() {
        val sink = DiagnosticCaptureFile(temporary.root, "capture")
        sink.use {
            assertTrue(it.append("captured before interruption\n"))
            it.flush()
            assertTrue(it.file.name.endsWith(".partial.txt"))
            assertEquals("captured before interruption\n", it.file.readText())
        }
        assertTrue(sink.file.exists())
        assertFalse(sink.file.readText().contains("CAPTURE_END"))
    }

    @Test fun utf8SizeLimitReservesRoomForFooter() {
        DiagnosticCaptureFile(temporary.root, "limited", maxBytes = 64, footerReserveBytes = 16).use {
            assertTrue(it.append("耳".repeat(16)))
            assertFalse(it.append("x"))
            assertTrue(it.append("\nCAPTURE_END\n", footer = true))
            val file = it.finish()
            assertEquals("limited.txt", file.name)
            assertFalse(temporary.root.resolve("limited.partial.txt").exists())
            assertTrue(file.readText().endsWith("CAPTURE_END\n"))
            assertTrue(file.length() <= 64)
        }
    }

    @Test fun finalizationFailureKeepsPartialData() {
        DiagnosticCaptureFile(temporary.root, "occupied").use {
            it.append("important evidence\n")
            temporary.root.resolve("occupied.txt").mkdir()
            temporary.root.resolve("occupied.txt/child").writeText("existing")
            assertThrows(IllegalStateException::class.java) { it.finish() }
            assertEquals("important evidence\n", it.file.readText())
        }
    }

    @Test fun existingPartialFileIsNeverOverwritten() {
        temporary.root.resolve("same.partial.txt").writeText("old evidence")
        assertThrows(IllegalStateException::class.java) { DiagnosticCaptureFile(temporary.root, "same") }
        assertEquals("old evidence", temporary.root.resolve("same.partial.txt").readText())
    }

    @Test fun nonzeroExitRetainsStderrAndIsNotSuccess() {
        val result = runDiagnosticCommand(listOf("sh", "-c", "echo denied >&2; exit 7"))
        assertEquals(7, result.exitCode)
        assertTrue(result.output.contains("denied"))
        assertFalse(result.succeeded)
    }

    @Test fun stdoutEofBeforeSuccessfulExitIsNotFailure() {
        val result = runDiagnosticCommand(listOf("sh", "-c",
            "echo 'uid=0(root)'; exec 1>&- 2>&-; sleep 0.4; exit 0"))
        assertTrue(result.report(), result.succeeded)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("uid=0(root)"))
    }

    @Test fun stdoutEofBeforeFailedExitPreservesRealExitCode() {
        val result = runDiagnosticCommand(listOf("sh", "-c",
            "echo 'uid=0(root)'; exec 1>&- 2>&-; sleep 0.4; exit 9"))
        assertFalse(result.succeeded)
        assertEquals(9, result.exitCode)
    }

    @Test fun stdoutEofDoesNotRemoveOverallTimeout() {
        val result = runDiagnosticCommand(listOf("sh", "-c",
            "echo 'uid=0(root)'; exec 1>&- 2>&-; exec sleep 10"), timeoutMillis = 200)
        assertFalse(result.succeeded)
        assertEquals("timeout", result.failure)
        assertTrue(result.output.contains("uid=0(root)"))
    }

    @Test fun cancellationStillWorksAfterStdoutEof() {
        val stopped = AtomicBoolean(false)
        val stopper = thread { Thread.sleep(200); stopped.set(true) }
        val result = runDiagnosticCommand(listOf("sh", "-c",
            "echo 'uid=0(root)'; exec 1>&- 2>&-; exec sleep 10"), cancelled = stopped::get)
        stopper.join()
        assertEquals("cancelled", result.failure)
    }

    @Test fun nonInteractiveCommandReceivesStdinEof() {
        val result = runDiagnosticCommand(listOf("sh", "-c", "cat >/dev/null; echo finished"))
        assertTrue(result.report(), result.succeeded)
        assertEquals("finished\n", result.output)
    }

    @Test fun exitDrainsAllQueuedLines() {
        val result = runDiagnosticCommand(listOf("sh", "-c", "i=0; while [ \"\$i\" -lt 500 ]; do echo \"line-\$i\"; i=\$((i+1)); done"))
        assertTrue(result.report(), result.succeeded)
        assertEquals(500, result.output.lineSequence().count { it.startsWith("line-") })
        assertTrue(result.output.endsWith("line-499\n"))
    }

    @Test fun quietProcessTimesOutWithoutWaitingForALine() {
        val started = System.nanoTime()
        val result = runDiagnosticCommand(listOf("sh", "-c", "exec sleep 10"), timeoutMillis = 100)
        assertEquals("timeout", result.failure)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000)
    }

    @Test fun stopDuringQuietCommandIsPrompt() {
        val stop = AtomicBoolean(false)
        val stopper = thread { Thread.sleep(100); stop.set(true) }
        val started = System.nanoTime()
        val result = runDiagnosticCommand(listOf("sh", "-c", "exec sleep 10"), cancelled = stop::get)
        stopper.join()
        assertEquals("cancelled", result.failure)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000)
    }

    @Test fun cancellationBeforeStartDoesNotLaunchCommand() {
        val result = runDiagnosticCommand(listOf("nonexistent-diagnostic-command"), cancelled = { true })
        assertEquals("cancelled", result.failure)
        assertEquals("", result.output)
    }

    @Test fun noisyCommandHasBoundedOutput() {
        val result = runDiagnosticCommand(listOf("sh", "-c", "while :; do echo abcdefghijklmnop; done"), maxChars = 40)
        assertEquals("output_limit", result.failure)
        assertEquals(40, result.output.length)
        assertFalse(result.succeeded)
    }

    @Test fun missingCommandIsReportedAsFailure() {
        val result = runDiagnosticCommand(listOf("nonexistent-diagnostic-command"))
        assertFalse(result.succeeded)
        assertNotNull(result.failure)
    }
}
