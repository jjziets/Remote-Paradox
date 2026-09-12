package com.remoteparadox.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.ArrayDeque
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal const val RETENTION_MS = 24 * 60 * 60 * 1000L
internal const val STORAGE_BYTES = 512 * 1024
internal const val EXPORT_BYTES = 48 * 1024
private const val SEGMENT_BYTES = 16 * 1024
private const val MAX_SEGMENTS = 32
private const val DATA_BYTES = STORAGE_BYTES - 1024
private const val METADATA_BYTES = 1024

internal interface DiagnosticClock {
    fun wallMs(): Long
    fun monotonicMs(): Long
}

internal object SystemDiagnosticClock : DiagnosticClock {
    override fun wallMs() = System.currentTimeMillis().coerceAtLeast(0)
    override fun monotonicMs() = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()).coerceAtLeast(0)
}

@Serializable
private data class StoreScope(val scope: String? = null, val truncated: WireBoolean = false)

/** Access only from the recorder's single writer. The directory is owned exclusively by this store. */
internal class RollingLogStore(private val directory: File, private val clock: DiagnosticClock) {
    private data class Entry(val event: DiagnosticEvent, val encoded: ByteArray)
    private data class Segment(val file: File, val entries: MutableList<Entry>) {
        val bytes: Int get() = entries.sumOf { it.encoded.size }
    }

    private val segments = ArrayDeque<Segment>()
    private var nextSegment = 0L
    private var binding = StoreScope()
    private val scopeFile = File(directory, "scope.json")
    val truncated: Boolean get() = binding.truncated

    init {
        if (Files.isSymbolicLink(directory.toPath())) throw IOException("Invalid diagnostics directory")
        Files.createDirectories(directory.toPath())
        privatePermissions(directory, executable = true)
        var invalidMetadata = false
        binding = try { readScope() } catch (_: Exception) {
            invalidMetadata = true
            StoreScope(truncated = true)
        }
        val files = mutableListOf<File>()
        Files.newDirectoryStream(directory.toPath(), "events-*.jsonl").use { paths ->
            for (path in paths) {
                if (files.size >= MAX_SEGMENTS + 1) throw IOException("Too many diagnostics segments")
                files += path.toFile()
            }
        }
        if (binding.scope == null) {
            // Without trustworthy attribution, discard payloads before decoding or
            // touching their permissions. deleteIfExists unlinks symlinks themselves.
            for (file in files) Files.deleteIfExists(file.toPath())
            if (invalidMetadata) Files.deleteIfExists(scopeFile.toPath())
            binding = binding.copy(truncated = binding.truncated || files.isNotEmpty())
        }
        for (file in if (binding.scope == null) emptyList() else files.sortedBy { it.name }) {
            val number = file.name.removePrefix("events-").removeSuffix(".jsonl").toLongOrNull()
            if (number == null || number < 0 || number == Long.MAX_VALUE) throw IOException("Invalid diagnostics segment")
            nextSegment = maxOf(nextSegment, number + 1)
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() > SEGMENT_BYTES) {
                Files.deleteIfExists(file.toPath())
                binding = binding.copy(truncated = true)
                continue
            }
            privatePermissions(file)
            val text = file.readText()
            val entries = mutableListOf<Entry>()
            for (line in text.split('\n').dropLast(1)) {
                try {
                    val event = DiagnosticCodec.json.decodeFromString<DiagnosticEvent>(line)
                    entries += Entry(event, (DiagnosticCodec.json.encodeToString(event) + "\n").toByteArray(Charsets.UTF_8))
                } catch (_: Exception) {
                    binding = binding.copy(truncated = true)
                }
            }
            if (text.isNotEmpty() && !text.endsWith('\n')) binding = binding.copy(truncated = true)
            val segment = Segment(file, entries)
            segments += segment
            rewrite(segment)
        }
        prune()
        saveScope()
    }

    fun bind(scope: String?) {
        if (binding.scope != scope) {
            clearFiles()
            binding = StoreScope(scope, truncated = binding.scope == null && binding.truncated)
            saveScope()
        }
        prune()
    }

    fun clear(scope: String?) {
        clearFiles()
        binding = StoreScope(scope)
        saveScope()
    }

    fun markTruncated() {
        if (!binding.truncated) {
            binding = binding.copy(truncated = true)
            saveScope()
        }
    }

    fun append(event: DiagnosticEvent) {
        prune()
        if (binding.scope == null) return
        val now = clock.wallMs()
        if (event.timeMs < (now - RETENTION_MS).coerceAtLeast(0) || event.timeMs > now) return
        val encoded = (DiagnosticCodec.json.encodeToString(event) + "\n").toByteArray(Charsets.UTF_8)
        if (encoded.size > SEGMENT_BYTES) { markTruncated(); return }
        while (segments.isNotEmpty() && bytes() + encoded.size > DATA_BYTES) evictFirst()
        var segment = segments.peekLast()
        if (segment == null || segment.bytes + encoded.size > SEGMENT_BYTES) {
            while (segments.size >= MAX_SEGMENTS) evictFirst()
            if (nextSegment == Long.MAX_VALUE) throw IOException("Diagnostics sequence exhausted")
            segment = Segment(File(directory, "events-${(nextSegment++).toString().padStart(20, '0')}.jsonl"), mutableListOf())
            Files.createFile(segment.file.toPath())
            privatePermissions(segment.file)
            segments += segment
        }
        requireRegular(segment.file, SEGMENT_BYTES)
        Files.newOutputStream(segment.file.toPath(), StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS).use {
            it.write(encoded)
        }
        segment.entries += Entry(event, encoded)
    }

    fun snapshot(device: String, version: String, build: Long, maxBytes: Int, lost: Boolean): DeviceLog {
        prune()
        val empty = DeviceLog(device, version, build, clock.wallMs(), truncated || lost)
        val envelopeBytes = DiagnosticCodec.json.encodeToString(empty.copy(truncated = false)).toByteArray(Charsets.UTF_8).size
        require(maxBytes >= envelopeBytes) { "Diagnostic export budget is smaller than its envelope" }
        val budget = minOf(maxBytes, EXPORT_BYTES)
        var used = envelopeBytes
        val selected = ArrayDeque<DiagnosticEvent>()
        var omitted = false
        outer@ for (segment in segments.descendingIterator()) {
            for (entry in segment.entries.asReversed()) {
                val cost = entry.encoded.size - 1 + if (selected.isEmpty()) 0 else 1
                if (used + cost > budget) { omitted = true; break@outer }
                used += cost
                selected.addFirst(entry.event)
            }
        }
        return empty.copy(truncated = empty.truncated || omitted, events = selected.toList())
    }

    private fun bytes() = segments.sumOf { it.bytes }

    private fun prune() {
        val now = clock.wallMs()
        val cutoff = (now - RETENTION_MS).coerceAtLeast(0)
        val iterator = segments.iterator()
        while (iterator.hasNext()) {
            val segment = iterator.next()
            val changed = segment.entries.removeAll { it.event.timeMs < cutoff || it.event.timeMs > now }
            if (segment.entries.isEmpty()) {
                Files.deleteIfExists(segment.file.toPath())
                iterator.remove()
            } else if (changed) rewrite(segment)
        }
        while (segments.size > MAX_SEGMENTS || bytes() > DATA_BYTES) evictFirst()
    }

    private fun evictFirst() {
        Files.deleteIfExists(segments.removeFirst().file.toPath())
        markTruncated()
    }

    private fun clearFiles() {
        for (segment in segments) Files.deleteIfExists(segment.file.toPath())
        segments.clear()
    }

    private fun rewrite(segment: Segment) {
        requireRegular(segment.file, SEGMENT_BYTES)
        Files.newOutputStream(segment.file.toPath(), StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS).use { stream ->
            segment.entries.forEach { stream.write(it.encoded) }
        }
    }

    private fun readScope(): StoreScope {
        if (!Files.exists(scopeFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return StoreScope()
        requireRegular(scopeFile, METADATA_BYTES)
        val buffer = ByteArray(METADATA_BYTES + 1)
        val size = Files.newInputStream(scopeFile.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            count
        }
        if (size > METADATA_BYTES) throw IOException("Oversized diagnostics metadata")
        val metadata = DiagnosticCodec.json.decodeFromString<StoreScope>(buffer.decodeToString(0, size, throwOnInvalidSequence = true))
        if (metadata.scope != null && !Fields.scope.matches(metadata.scope)) throw IOException("Invalid diagnostics scope")
        return metadata
    }

    private fun saveScope() {
        val temporary = File(directory, "scope.tmp")
        Files.deleteIfExists(temporary.toPath())
        Files.createFile(temporary.toPath())
        privatePermissions(temporary)
        Files.newOutputStream(temporary.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use {
            it.write(DiagnosticCodec.json.encodeToString(binding).toByteArray(Charsets.UTF_8))
        }
        Files.move(temporary.toPath(), scopeFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun requireRegular(file: File, maxBytes: Int) {
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() > maxBytes) throw IOException("Invalid diagnostics file")
    }

    private fun privatePermissions(file: File, executable: Boolean = false) {
        if (!file.setReadable(false, false) || !file.setWritable(false, false) || !file.setExecutable(false, false) ||
            !file.setReadable(true, true) || !file.setWritable(true, true) || (executable && !file.setExecutable(true, true))) {
            throw IOException("Cannot protect diagnostics storage")
        }
    }
}
