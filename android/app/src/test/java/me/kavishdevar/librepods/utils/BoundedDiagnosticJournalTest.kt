package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoundedDiagnosticJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun anotherProcessSessionCanReadEvidenceWithoutCaptureFinalization() {
        BoundedDiagnosticJournal(temporary.root).append("event=process_created pid=123")
        BoundedDiagnosticJournal(temporary.root).append("event=user_stop_detected pid=456")
        val restored = BoundedDiagnosticJournal(temporary.root).read()
        assertTrue(restored.contains("pid=123"))
        assertTrue(restored.contains("pid=456"))
    }

    @Test fun rotationPreservesNewestEventsWithinTwoFileLimit() {
        val journal = BoundedDiagnosticJournal(temporary.root, maxBytes = 256)
        repeat(100) { journal.append("event=$it " + "x".repeat(40)) }
        assertTrue(journal.read().contains("event=99"))
        assertFalse(journal.read().contains("event=0 "))
        assertTrue(temporary.root.listFiles()!!.size <= 2)
        assertTrue(temporary.root.listFiles()!!.all { it.length() <= 256 })
    }

    @Test fun unicodeAndEmbeddedNewlinesCannotBreakRecordBounds() {
        val journal = BoundedDiagnosticJournal(temporary.root, maxBytes = 128)
        repeat(10) { journal.append("中\n文\r".repeat(100)) }
        assertTrue(temporary.root.listFiles()!!.all { it.length() <= 128 })
        assertFalse(journal.read().contains('\uFFFD'))
        assertFalse(journal.read().contains('\r'))
    }
}
