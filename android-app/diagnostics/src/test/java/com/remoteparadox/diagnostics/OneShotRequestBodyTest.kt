package com.remoteparadox.diagnostics

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import org.junit.Assert.*
import org.junit.Test

class OneShotRequestBodyTest {
    @Test fun `wrapper preserves payload content type and length`() {
        val bytes = byteArrayOf(0, 1, 127, -1)
        val delegate = bytes.toRequestBody("application/octet-stream".toMediaType())
        val body = OneShotRequestBody(delegate)
        assertTrue(body.isOneShot())
        assertEquals(delegate.contentType(), body.contentType())
        assertEquals(delegate.contentLength(), body.contentLength())
        assertEquals(delegate.isDuplex(), body.isDuplex())
        val buffer = Buffer()
        body.writeTo(buffer)
        assertArrayEquals(bytes, buffer.readByteArray())
    }

    @Test fun `wrapper preserves streaming length and duplex metadata`() {
        val delegate = object : RequestBody() {
            override fun contentType() = null
            override fun isDuplex() = true
            override fun writeTo(sink: BufferedSink) { sink.writeUtf8("unchanged payload") }
        }
        val body = OneShotRequestBody(delegate)
        assertTrue(body.isOneShot())
        assertTrue(body.isDuplex())
        assertNull(body.contentType())
        assertEquals(-1L, body.contentLength())
        val buffer = Buffer()
        body.writeTo(buffer)
        assertEquals("unchanged payload", buffer.readUtf8())
    }
}
