package com.cyenx.clashmi.clashmi_vpn_service

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class RotatingLogWriterTest {
    @Test
    fun defaults_limitEachFileTo100MiBAndKeepThreeFilesTotal() {
        assertEquals(100L * 1024L * 1024L, RotatingLogWriter.MAX_FILE_BYTES)
        assertEquals(3, RotatingLogWriter.MAX_FILE_COUNT)
    }

    @Test
    fun append_rotatesBeforeOverflowAndKeepsNewestThreeFiles() {
        withTempDirectory { directory ->
            val active = File(directory, "service_core.log")

            RotatingLogWriter.append(active, "AAAA", maxFileBytes = 5, maxFileCount = 3)
            assertFalse(RotatingLogWriter.append(active, "B", maxFileBytes = 5, maxFileCount = 3))
            assertEquals("AAAAB", active.readText())

            assertTrue(RotatingLogWriter.append(active, "CC", maxFileBytes = 5, maxFileCount = 3))
            assertTrue(RotatingLogWriter.append(active, "DDDD", maxFileBytes = 5, maxFileCount = 3))
            assertTrue(RotatingLogWriter.append(active, "EE", maxFileBytes = 5, maxFileCount = 3))

            assertEquals("EE", active.readText())
            assertEquals("DDDD", File(directory, "service_core.log.1").readText())
            assertEquals("CC", File(directory, "service_core.log.2").readText())
            assertFalse(File(directory, "service_core.log.3").exists())
        }
    }

    @Test
    fun append_usesUtf8ByteSizeRatherThanCharacterCount() {
        withTempDirectory { directory ->
            val active = File(directory, "service_core.log")

            RotatingLogWriter.append(active, "中", maxFileBytes = 5, maxFileCount = 3)
            assertTrue(RotatingLogWriter.append(active, "文", maxFileBytes = 5, maxFileCount = 3))

            assertEquals("文", active.readText())
            assertEquals("中", File(directory, "service_core.log.1").readText())
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("clashmi-log-rotation-").toFile()
        try {
            block(directory)
        } finally {
            // Every test owns this isolated directory; never touch application logs.
            directory.deleteRecursively()
        }
    }
}
