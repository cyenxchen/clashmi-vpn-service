package com.cyenx.clashmi.clashmi_vpn_service

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Serializes persistent VPN and quick-settings logs and keeps one active file
 * plus the two newest rotated files (`.1` is newer than `.2`).
 */
object RotatingLogWriter {
    const val MAX_FILE_BYTES = 100L * 1024L * 1024L
    const val MAX_FILE_COUNT = 3

    /** Returns true when this append rotated the active file first. */
    @Synchronized
    fun append(
        logFile: File,
        content: String,
        maxFileBytes: Long = MAX_FILE_BYTES,
        maxFileCount: Int = MAX_FILE_COUNT,
    ): Boolean {
        require(maxFileBytes > 0) { "maxFileBytes must be positive" }
        require(maxFileCount > 0) { "maxFileCount must be positive" }

        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        // A single pathological entry must not violate the configured file cap.
        require(bytes.size.toLong() <= maxFileBytes) { "log entry exceeds maxFileBytes" }

        logFile.parentFile?.mkdirs()
        val shouldRotate = logFile.isFile &&
            logFile.length() > 0L &&
            logFile.length() + bytes.size > maxFileBytes
        if (shouldRotate) {
            rotate(logFile, maxFileCount)
        }
        logFile.appendBytes(bytes)
        return shouldRotate
    }

    private fun rotate(logFile: File, maxFileCount: Int) {
        removeExpiredBackups(logFile, maxFileCount)
        if (maxFileCount == 1) {
            Files.deleteIfExists(logFile.toPath())
            return
        }

        // Move oldest to newest so no retained generation is overwritten early.
        for (index in maxFileCount - 1 downTo 1) {
            val source = if (index == 1) logFile else backupFile(logFile, index - 1)
            if (!source.isFile) {
                continue
            }
            Files.move(
                source.toPath(),
                backupFile(logFile, index).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun removeExpiredBackups(logFile: File, maxFileCount: Int) {
        val prefix = "${logFile.name}."
        logFile.parentFile?.listFiles()?.forEach { candidate ->
            val index = candidate.name
                .takeIf { it.startsWith(prefix) }
                ?.removePrefix(prefix)
                ?.toIntOrNull()
            if (index != null && index >= maxFileCount) {
                Files.deleteIfExists(candidate.toPath())
            }
        }
    }

    private fun backupFile(logFile: File, index: Int): File =
        File(logFile.parentFile, "${logFile.name}.$index")
}
