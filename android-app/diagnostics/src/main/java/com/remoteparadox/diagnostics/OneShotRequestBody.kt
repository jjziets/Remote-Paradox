package com.remoteparadox.diagnostics

import okhttp3.RequestBody
import okio.BufferedSink

/** Prevents OkHttp follow-up requests from replaying a side-effecting POST. */
class OneShotRequestBody(private val delegate: RequestBody) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun isDuplex() = delegate.isDuplex()
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
}
