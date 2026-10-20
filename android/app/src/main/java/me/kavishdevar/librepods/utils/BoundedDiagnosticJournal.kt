package me.kavishdevar.librepods.utils

import java.io.File
import java.io.FileOutputStream

/** Two bounded files, independent of a manual capture and readable after process death. */
internal class BoundedDiagnosticJournal(private val directory: File, private val maxBytes: Int = 32_768) {
    private val current get() = File(directory, "process-current.txt")
    private val previous get() = File(directory, "process-previous.txt")

    init { require(maxBytes >= 128) }

    @Synchronized
    fun append(line: String) {
        check(directory.isDirectory || directory.mkdirs())
        // At most four UTF-8 bytes per character; leave room for the newline.
        val entry = (line.replace('\n', ' ').replace('\r', ' ').take(maxBytes / 8) + "\n")
            .toByteArray(Charsets.UTF_8)
        if (current.length() + entry.size > maxBytes) {
            check(!previous.exists() || previous.delete())
            check(current.renameTo(previous))
        }
        // No user-space buffer: completed writes survive an app process kill.
        FileOutputStream(current, true).use { it.write(entry) }
    }

    @Synchronized
    fun read(): String = listOf(previous, current).filter(File::isFile).joinToString("") {
        it.inputStream().use { input -> String(input.readNBytes(maxBytes), Charsets.UTF_8) }
    }
}
