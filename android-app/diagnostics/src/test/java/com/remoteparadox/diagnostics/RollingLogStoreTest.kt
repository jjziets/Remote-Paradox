package com.remoteparadox.diagnostics

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RollingLogStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val clock = TestClock()
    private fun store(file: File = temporary.newFolder()) = RollingLogStore(file, clock).apply { bind(SCOPE_A) }
    private fun RollingLogStore.export(max: Int = EXPORT_BYTES) = snapshot("phone", "1.2.3", 12, max, false)

    @Test fun `persists across restart for same scope only`() {
        val directory = temporary.newFolder()
        store(directory).append(stamped())
        val restarted = store(directory)
        assertEquals(listOf(stamped()), restarted.export().events)
        restarted.bind(SCOPE_B)
        assertTrue(restarted.export().events.isEmpty())
        assertFalse(directory.walk().filter { it.isFile }.any { it.readText().contains(PROCESS) })
    }

    @Test fun `expires on write export and startup`() {
        val directory = temporary.newFolder()
        val store = store(directory)
        store.append(stamped())
        clock.advance(RETENTION_MS + 1)
        assertTrue(store.export().events.isEmpty())
        store.append(stamped(2, clock.wall))
        clock.advance(RETENTION_MS + 1)
        store.append(stamped(3, clock.wall))
        assertEquals(listOf(3L), store.export().events.map { it.sequence })
        clock.advance(RETENTION_MS + 1)
        assertTrue(store(directory).export().events.isEmpty())
        assertFalse(directory.listFiles()!!.any { it.name.endsWith("jsonl") })
    }

    @Test fun `clock rollback cannot retain future dated records`() {
        val store = store()
        store.append(stamped())
        clock.wall -= 1000
        assertTrue(store.export().events.isEmpty())
    }

    @Test fun `disk and UTF8 serialized exports stay within byte caps`() {
        val directory = temporary.newFolder()
        val store = store(directory)
        for (i in 1..2500) {
            store.append(stamped(i.toLong()))
            if (i % 200 == 0) assertTrue(directory.walk().filter { it.isFile }.sumOf { it.length() } <= STORAGE_BYTES)
        }
        val report = store.export()
        assertTrue(report.truncated)
        assertEquals(2500L, report.events.last().sequence)
        assertTrue(DiagnosticCodec.json.encodeToString(report).toByteArray(Charsets.UTF_8).size <= EXPORT_BYTES)
        assertTrue(report.events.zipWithNext().all { (a, b) -> b.sequence == a.sequence + 1 })
        val smaller = store.export(1024)
        assertTrue(smaller.truncated)
        assertEquals(2500L, smaller.events.last().sequence)
        assertTrue(DiagnosticCodec.json.encodeToString(smaller).toByteArray().size <= 1024)
        assertTrue(store.export(Int.MAX_VALUE).events.size == report.events.size)
        assertTrue(store(directory).export().truncated)
    }

    @Test fun `small complete exports are not marked truncated`() {
        val store = store()
        store.append(stamped())
        assertFalse(store.export().truncated)
    }

    @Test fun `scope clear removes persisted payload and loss marker`() {
        val store = store()
        store.append(stamped())
        store.markTruncated()
        store.clear(SCOPE_A)
        assertTrue(store.export().events.isEmpty())
        assertFalse(store.export().truncated)
    }

    @Test fun `directory and files are owner private`() {
        val directory = temporary.newFolder()
        store(directory).append(stamped())
        val forbidden = setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)
        directory.walk().forEach { file -> assertTrue(Files.getPosixFilePermissions(file.toPath()).intersect(forbidden).isEmpty()) }
    }

    @Test fun `partial or corrupt records are discarded on startup`() {
        val directory = temporary.newFolder()
        store(directory).append(stamped())
        val segment = directory.listFiles()!!.first { it.name.endsWith("jsonl") }
        segment.appendText("{\"message\":\"secret\"}\npartial-secret")
        val restarted = store(directory)
        assertEquals(1, restarted.export().events.size)
        assertTrue(restarted.export().truncated)
        assertFalse(segment.readText().contains("secret"))
    }

    @Test fun `symlink segment cannot expose another file`() {
        val directory = temporary.newFolder()
        val outside = temporary.newFile().apply { writeText("secret") }
        val store = store(directory)
        store.append(stamped())
        val segment = directory.listFiles()!!.first { it.name.endsWith("jsonl") }
        segment.delete()
        Files.createSymbolicLink(segment.toPath(), outside.toPath())
        assertTrue(store(directory).export().events.isEmpty())
        assertEquals("secret", outside.readText())
    }

    @Test fun `unrecoverable metadata discards prior attribution and permits fresh recording`() {
        val invalid = listOf("{", "not-json", "x".repeat(1025),
            """{"scope":"invalid","truncated":false}""",
            """{"scope":false,"truncated":false}""",
            """{"scope":"$SCOPE_A","truncated":"false"}""",
            """{"scope":"$SCOPE_A","extra":"untrusted"}""")
        for (metadata in invalid) {
            val directory = temporary.newFolder()
            store(directory).append(stamped(99))
            File(directory, "scope.json").writeText(metadata)
            val recovered = RollingLogStore(directory, clock)
            assertTrue(recovered.export().events.isEmpty())
            assertTrue(recovered.export().truncated)
            recovered.bind(SCOPE_B)
            recovered.append(stamped(1))
            assertEquals(listOf(1L), recovered.export().events.map { it.sequence })
            assertTrue(recovered.export().truncated)
            val restarted = RollingLogStore(directory, clock).apply { bind(SCOPE_B) }
            assertEquals(listOf(1L), restarted.export().events.map { it.sequence })
            assertTrue(restarted.export().truncated)
            assertTrue(directory.walk().filter { it.isFile }.sumOf { it.length() } <= STORAGE_BYTES)
            assertFalse(directory.walk().filter { it.isFile }.any { it.readText().contains("\"sequence\":99") })
        }
    }

    @Test fun `missing or unbound metadata never recovers unattributed events`() {
        for (missing in listOf(true, false)) {
            val directory = temporary.newFolder()
            store(directory).append(stamped(99))
            val metadata = File(directory, "scope.json")
            if (missing) metadata.delete() else metadata.writeText("{\"truncated\":false}")
            val recovered = RollingLogStore(directory, clock).apply { bind(SCOPE_A) }
            assertTrue(recovered.export().events.isEmpty())
            assertTrue(recovered.export().truncated)
            recovered.append(stamped(1))
            assertEquals(listOf(1L), recovered.export().events.map { it.sequence })
        }
    }

    @Test fun `metadata recovery unlinks symlinks without reading or modifying their targets`() {
        for (broken in listOf(true, false)) {
            val directory = temporary.newFolder()
            store(directory).append(stamped(99))
            val outside = temporary.newFolder()
            val metadataTarget = File(outside, "scope.json")
            if (!broken) metadataTarget.writeText("""{"scope":"$SCOPE_A","truncated":false}""")
            val eventTarget = File(outside, "event.jsonl").apply { writeText("outside-private-data") }
            val metadata = File(directory, "scope.json")
            metadata.delete()
            Files.createSymbolicLink(metadata.toPath(), metadataTarget.toPath())
            Files.createSymbolicLink(File(directory, "scope.tmp").toPath(), eventTarget.toPath())
            val segment = directory.listFiles()!!.first { it.name.endsWith("jsonl") }
            segment.delete()
            Files.createSymbolicLink(segment.toPath(), eventTarget.toPath())
            val recovered = RollingLogStore(directory, clock).apply { bind(SCOPE_B) }
            recovered.append(stamped())
            assertEquals(1, recovered.export().events.size)
            assertTrue(recovered.export().truncated)
            assertEquals("outside-private-data", eventTarget.readText())
            if (broken) assertFalse(metadataTarget.exists())
            else assertEquals("""{"scope":"$SCOPE_A","truncated":false}""", metadataTarget.readText())
            assertFalse(Files.isSymbolicLink(metadata.toPath()))
            assertFalse(File(directory, "scope.tmp").exists())
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(metadata.toPath()))
        }
    }
}
